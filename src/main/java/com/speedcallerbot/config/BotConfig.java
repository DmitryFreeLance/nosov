package com.speedcallerbot.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BotConfig {
    private final String token;
    private final String username;
    private final Path dbPath;
    private final Set<Long> ownerIds;
    private final boolean adsgramEnabled;
    private final String adsgramToken;
    private final List<String> adsgramBlockIds;
    private final String adsgramLanguage;
    private final int adsEveryCards;
    private final int adsgramCandidatesPerBlock;

    private BotConfig(String token,
                      String username,
                      Path dbPath,
                      Set<Long> ownerIds,
                      boolean adsgramEnabled,
                      String adsgramToken,
                      List<String> adsgramBlockIds,
                      String adsgramLanguage,
                      int adsEveryCards,
                      int adsgramCandidatesPerBlock) {
        this.token = token;
        this.username = username;
        this.dbPath = dbPath;
        this.ownerIds = ownerIds;
        this.adsgramEnabled = adsgramEnabled;
        this.adsgramToken = adsgramToken;
        this.adsgramBlockIds = adsgramBlockIds;
        this.adsgramLanguage = adsgramLanguage;
        this.adsEveryCards = adsEveryCards;
        this.adsgramCandidatesPerBlock = adsgramCandidatesPerBlock;
    }

    public static BotConfig fromEnv() {
        String token = requireEnv("BOT_TOKEN");
        String username = requireEnv("BOT_USERNAME");

        String rawDbPath = System.getenv().getOrDefault("DB_PATH", "data/speedcallerbot.db");
        Path dbPath = Path.of(rawDbPath).toAbsolutePath();

        Set<Long> ownerIds = parseOwnerIds(System.getenv().getOrDefault("OWNER_TG_IDS", ""));
        String singleOwner = System.getenv("OWNER_TG_ID");
        if (singleOwner != null && !singleOwner.isBlank()) {
            try {
                ownerIds.add(Long.parseLong(singleOwner.trim()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed OWNER_TG_ID to keep startup resilient.
            }
        }

        String adsgramToken = normalizeNullable(System.getenv("ADSGRAM_TOKEN"));
        List<String> adsgramBlockIds = parseAdsgramBlockIds(
            System.getenv("ADSGRAM_BLOCK_IDS"),
            System.getenv("ADSGRAM_BLOCK_ID")
        );
        String adsgramLanguage = parseLanguage(System.getenv("ADSGRAM_LANGUAGE"));
        int adsEveryCards = parsePositiveInt(System.getenv("ADS_EVERY_CARDS"), 40);
        int adsgramCandidatesPerBlock = parsePositiveInt(System.getenv("ADSGRAM_CANDIDATES_PER_BLOCK"), 2);

        boolean adsgramReady = adsgramToken != null && !adsgramBlockIds.isEmpty() && adsEveryCards > 0;
        boolean adsgramEnabled = parseBoolean(System.getenv("ADSGRAM_ENABLED"), adsgramReady);
        if (!adsgramReady) {
            adsgramEnabled = false;
        }

        return new BotConfig(
            token,
            username,
            dbPath,
            Collections.unmodifiableSet(ownerIds),
            adsgramEnabled,
            adsgramToken,
            List.copyOf(adsgramBlockIds),
            adsgramLanguage,
            adsEveryCards,
            adsgramCandidatesPerBlock
        );
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required.");
        }
        return value;
    }

    private static Set<Long> parseOwnerIds(String raw) {
        Set<Long> ids = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }

        String[] parts = raw.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                // Ignore malformed IDs.
            }
        }
        return ids;
    }

    private static List<String> parseAdsgramBlockIds(String rawMany, String rawSingle) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        collectAdsgramBlockIds(unique, rawMany);
        collectAdsgramBlockIds(unique, rawSingle);
        return new ArrayList<>(unique);
    }

    private static void collectAdsgramBlockIds(Set<String> target, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] parts = raw.split("[,;\\s]+");
        for (String part : parts) {
            String normalized = normalizeBlockId(part);
            if (normalized != null) {
                target.add(normalized);
            }
        }
    }

    private static String normalizeBlockId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("bot-")) {
            value = value.substring(4).trim();
        }

        if (value.isEmpty()) {
            return null;
        }
        return value;
    }

    private static String parseLanguage(String raw) {
        String value = normalizeNullable(raw);
        if (value == null) {
            return "en";
        }
        return value;
    }

    private static int parsePositiveInt(String raw, int fallback) {
        String value = normalizeNullable(raw);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        String value = normalizeNullable(raw);
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            case "0", "false", "no", "n", "off" -> false;
            default -> fallback;
        };
    }

    private static String normalizeNullable(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    public String getToken() {
        return token;
    }

    public String getUsername() {
        return username;
    }

    public Path getDbPath() {
        return dbPath;
    }

    public Set<Long> getOwnerIds() {
        return ownerIds;
    }

    public boolean isAdsgramEnabled() {
        return adsgramEnabled;
    }

    public String getAdsgramToken() {
        return adsgramToken;
    }

    public List<String> getAdsgramBlockIds() {
        return adsgramBlockIds;
    }

    public String getAdsgramLanguage() {
        return adsgramLanguage;
    }

    public int getAdsEveryCards() {
        return adsEveryCards;
    }

    public int getAdsgramCandidatesPerBlock() {
        return adsgramCandidatesPerBlock;
    }
}
