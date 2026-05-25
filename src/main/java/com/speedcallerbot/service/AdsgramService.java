package com.speedcallerbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedcallerbot.config.BotConfig;
import com.speedcallerbot.model.AdsgramAd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class AdsgramService {
    private static final Logger log = LoggerFactory.getLogger(AdsgramService.class);

    private static final List<String> PRIORITY_1_SIM = List.of(
        "sim", "sms", "virtual number", "phone number", "otp", "rent number", "receive sms",
        "аренда sim", "sim-карт", "сим", "номер для смс", "покупка номера", "activation code"
    );

    private static final List<String> PRIORITY_2_ACCOUNTS = List.of(
        "account rent", "telegram account", "whatsapp account", "messenger account", "social account",
        "activation account", "аренда аккаун", "аккаунт telegram", "аккаунт whatsapp", "мессенджер"
    );

    private static final List<String> PRIORITY_3_MINI_GAMES = List.of(
        "mini app", "mini game", "crypto game", "tap game", "web3 game", "play to earn", "gamefi"
    );

    private static final List<String> PRIORITY_4_DATING = List.of(
        "dating", "meet", "relationship", "find friends", "знакомств", "dating app"
    );

    private static final List<String> PRIORITY_5_CRYPTO_INVEST = List.of(
        "crypto", "wallet", "blockchain", "investment", "invest", "trading", "copy trading", "copy-trading",
        "broker", "robinhood", "robin hood", "forex", "stocks", "stock market", "etf", "mutual fund",
        "fiat investment", "exchange", "крипто", "инвести", "трейдинг", "биржа"
    );

    private static final List<String> PRIORITY_6_GAMBLING = List.of(
        "casino", "bet", "sportsbook", "slot", "roulette", "poker", "ставк", "казино", "букмекер"
    );

    private final BotConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public AdsgramService(BotConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();
        this.mapper = new ObjectMapper();
    }

    public boolean isEnabled() {
        return config.isAdsgramEnabled();
    }

    public int getAdsEveryCards() {
        return config.getAdsEveryCards();
    }

    public Optional<AdsgramAd> pickBestAd(long tgUserId) {
        return pickBestAd(tgUserId, null);
    }

    public Optional<AdsgramAd> pickBestAd(long tgUserId, String userLanguageCode) {
        if (!isEnabled()) {
            return Optional.empty();
        }

        List<AdsgramAd> candidates = new ArrayList<>();
        String language = normalizeLanguageCode(userLanguageCode);
        if (language == null) {
            language = normalizeLanguageCode(config.getAdsgramLanguage());
        }
        if (language == null) {
            language = "en";
        }

        for (String blockId : config.getAdsgramBlockIds()) {
            for (int i = 0; i < config.getAdsgramCandidatesPerBlock(); i++) {
                Optional<AdsgramAd> maybeAd = requestAd(tgUserId, blockId, language);
                maybeAd.ifPresent(candidates::add);
            }
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        return candidates.stream()
            .max(Comparator.comparingInt(AdsgramAd::getPriorityScore));
    }

    private Optional<AdsgramAd> requestAd(long tgUserId, String blockId, String language) {
        try {
            String encodedLanguage = URLEncoder.encode(language, StandardCharsets.UTF_8);
            String url = "https://api.adsgram.ai/advbot?tgid=" + tgUserId
                + "&blockid=" + URLEncoder.encode(blockId, StandardCharsets.UTF_8)
                + "&language=" + encodedLanguage
                + "&token=" + URLEncoder.encode(config.getAdsgramToken(), StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.body());
            String textHtml = clean(root.path("text_html").asText(null));
            if (textHtml == null || textHtml.isBlank()) {
                return Optional.empty();
            }

            String clickUrl = clean(root.path("click_url").asText(null));
            String buttonName = clean(root.path("button_name").asText(null));
            String rewardUrl = clean(root.path("reward_url").asText(null));
            String rewardButtonName = clean(root.path("button_reward_name").asText(null));
            String imageUrl = clean(root.path("image_url").asText(null));

            int score = scoreAd(textHtml, clickUrl, buttonName, rewardUrl, rewardButtonName, imageUrl);

            return Optional.of(new AdsgramAd(
                textHtml,
                clickUrl,
                buttonName,
                rewardUrl,
                rewardButtonName,
                imageUrl,
                blockId,
                score
            ));
        } catch (Exception e) {
            log.debug("AdsGram request failed for block {}", blockId, e);
            return Optional.empty();
        }
    }

    private String normalizeLanguageCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }

        int separatorIndex = value.indexOf('-');
        if (separatorIndex > 0) {
            value = value.substring(0, separatorIndex);
        }
        separatorIndex = value.indexOf('_');
        if (separatorIndex > 0) {
            value = value.substring(0, separatorIndex);
        }

        if (value.length() < 2) {
            return null;
        }
        return value.substring(0, 2);
    }

    private int scoreAd(String textHtml,
                        String clickUrl,
                        String buttonName,
                        String rewardUrl,
                        String rewardButtonName,
                        String imageUrl) {
        String payload = String.join(" ",
            safeLower(textHtml),
            safeLower(clickUrl),
            safeLower(buttonName),
            safeLower(rewardUrl),
            safeLower(rewardButtonName),
            safeLower(imageUrl)
        );

        if (containsAny(payload, PRIORITY_1_SIM)) {
            return 600;
        }
        if (containsAny(payload, PRIORITY_2_ACCOUNTS)) {
            return 500;
        }
        if (containsAny(payload, PRIORITY_3_MINI_GAMES)) {
            return 400;
        }
        if (containsAny(payload, PRIORITY_4_DATING)) {
            return 300;
        }
        if (containsAny(payload, PRIORITY_5_CRYPTO_INVEST)) {
            return 200;
        }
        if (containsAny(payload, PRIORITY_6_GAMBLING)) {
            return 100;
        }

        return 250;
    }

    private boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String safeLower(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
