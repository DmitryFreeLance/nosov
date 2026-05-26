package com.speedcallerbot.bot;

import com.speedcallerbot.config.BotConfig;
import com.speedcallerbot.db.DatabaseService;
import com.speedcallerbot.model.ContactRecord;
import com.speedcallerbot.model.ImportReport;
import com.speedcallerbot.model.ParsedBatch;
import com.speedcallerbot.model.AdsgramAd;
import com.speedcallerbot.model.UserMode;
import com.speedcallerbot.model.UserState;
import com.speedcallerbot.model.UserSummary;
import com.speedcallerbot.service.AdsgramService;
import com.speedcallerbot.service.NumberImportService;
import com.speedcallerbot.util.TextFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendContact;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public class SpeedCallerBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(SpeedCallerBot.class);
    private static final DateTimeFormatter EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final BotConfig config;
    private final DatabaseService db;
    private final NumberImportService importService;
    private final AdsgramService adsgramService;
    private final ConcurrentMap<Long, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    public SpeedCallerBot(BotConfig config, DatabaseService db) {
        super(config.getToken());
        this.config = config;
        this.db = db;
        this.importService = new NumberImportService();
        this.adsgramService = new AdsgramService(config);
    }

    @Override
    public String getBotUsername() {
        return config.getUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        Long userId = extractUserId(update);
        if (userId == null || userId <= 0) {
            return;
        }

        ReentrantLock lock = userLocks.computeIfAbsent(userId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            handleUpdate(update);
        } catch (Exception e) {
            log.error("Unexpected error while processing update", e);
        } finally {
            lock.unlock();
        }
    }

    private void handleUpdate(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }
        if (update.hasMessage()) {
            handleMessage(update.getMessage());
        }
    }

    private void handleMessage(Message message) {
        if (message.getFrom() == null || message.getChatId() == null) {
            return;
        }

        long userId = message.getFrom().getId();
        long chatId = message.getChatId();
        registerUser(message.getFrom());

        UserState state = db.getUserState(userId);

        if (message.hasText() && message.getText().startsWith("/start")) {
            state.setMode(UserMode.NONE);
            state.setPendingAdContactIndex(null);
            state.setLastBotMessageId(null);
            db.saveUserState(state);
            clearDialContactMessage(chatId, state);
            showMainMenu(chatId, state, null);
            return;
        }

        if (message.hasText() && message.getText().startsWith("/admin")) {
            if (!db.isAdmin(userId)) {
                clearDialContactMessage(chatId, state);
                showAccessDenied(chatId, state, null);
            } else {
                state.setMode(UserMode.NONE);
                db.saveUserState(state);
                clearDialContactMessage(chatId, state);
                showAdminMenu(chatId, state, null, null);
            }
            safeDeleteMessage(chatId, message.getMessageId());
            return;
        }

        if (message.hasDocument()) {
            clearDialContactMessage(chatId, state);
            processDocumentUpload(chatId, state, message);
            safeDeleteMessage(chatId, message.getMessageId());
            return;
        }

        if (state.getMode() == UserMode.WAITING_TEXT && message.hasText()) {
            clearDialContactMessage(chatId, state);
            processPastedText(chatId, state, message.getText());
            safeDeleteMessage(chatId, message.getMessageId());
            return;
        }

        if (state.getMode() == UserMode.WAITING_ADMIN_ID && message.hasText()) {
            clearDialContactMessage(chatId, state);
            processAdminGrant(chatId, state, message.getText());
            safeDeleteMessage(chatId, message.getMessageId());
            return;
        }

        if (message.hasText() && message.getText().startsWith("/")) {
            safeDeleteMessage(chatId, message.getMessageId());
            return;
        }

        if (message.hasText()) {
            String helperText = "📌 <b>How to continue</b>\n\n"
                + "Use the buttons to navigate:\n"
                + "• <b>LOAD NUMBERS</b> to upload a file or paste contacts\n"
                + "• <b>START</b> to begin calling\n"
                + "\nEverything is handled inline to keep your chat clean.";
            clearDialContactMessage(chatId, state);
            showMainMenu(chatId, state, helperText);
            safeDeleteMessage(chatId, message.getMessageId());
        }
    }

    private void handleCallback(CallbackQuery callbackQuery) {
        if (callbackQuery.getFrom() == null || callbackQuery.getMessage() == null) {
            return;
        }

        registerUser(callbackQuery.getFrom());

        long userId = callbackQuery.getFrom().getId();
        long chatId = callbackQuery.getMessage().getChatId();
        Integer callbackMessageId = callbackQuery.getMessage().getMessageId();
        String adLanguage = normalizeAdsLanguage(callbackQuery.getFrom().getLanguageCode());

        UserState state = db.getUserState(userId);
        state.setLastBotMessageId(callbackMessageId);
        db.saveUserState(state);

        answerCallback(callbackQuery.getId(), null);

        String data = callbackQuery.getData();
        if (data == null) {
            return;
        }

        switch (data) {
            case BotCallbacks.START_CALLING -> {
                state.setMode(UserMode.NONE);
                db.saveUserState(state);
                clearDialContactMessage(chatId, state);
                showCallCard(chatId, state, callbackMessageId, null, adLanguage);
            }
            case BotCallbacks.OPEN_LOAD_MENU -> {
                state.setMode(UserMode.NONE);
                db.saveUserState(state);
                clearDialContactMessage(chatId, state);
                showLoadMenu(chatId, state, callbackMessageId, null);
            }
            case BotCallbacks.OPEN_MAIN_MENU -> {
                state.setMode(UserMode.NONE);
                db.saveUserState(state);
                clearDialContactMessage(chatId, state);
                showMainMenu(chatId, state, null);
            }
            case BotCallbacks.CALL_NOW -> {
                Optional<ContactRecord> currentContact = db.getContactByIndex(state.getUserId(), state.getCurrentIndex());
                String status;
                if (currentContact.isPresent()) {
                    boolean sent = sendDialContact(chatId, state, currentContact.get());
                    status = sent
                        ? "✅ Contact card sent. Tap <b>Call</b> in the contact card to dial immediately."
                        : "⚠️ Could not send contact card. Tap the phone number in this message.";
                } else {
                    status = "⚠️ Contact is no longer available.";
                }
                showCallCard(chatId, state, callbackMessageId, status, true, adLanguage);
            }
            case BotCallbacks.CALL_SKIP -> {
                handleCallSkip(chatId, state, callbackMessageId, adLanguage);
            }
            case BotCallbacks.CALL_BACK -> {
                handleCallBack(chatId, state, callbackMessageId, adLanguage);
            }
            case BotCallbacks.LOAD_FILE -> {
                state.setMode(UserMode.WAITING_FILE);
                db.saveUserState(state);
                clearDialContactMessage(chatId, state);
                showLoadMenu(chatId, state, callbackMessageId,
                    "📤 Send a <b>.xlsx</b>, <b>.txt</b> or <b>.csv</b> file in the next message.\n"
                        + "I will import numbers and remove duplicates automatically.");
            }
            case BotCallbacks.LOAD_TEXT -> {
                state.setMode(UserMode.WAITING_TEXT);
                db.saveUserState(state);
                clearDialContactMessage(chatId, state);
                showLoadMenu(chatId, state, callbackMessageId,
                    "📝 Paste your phone list in the next message.\n"
                        + "Supported formats: one number per line, or <code>Name, +1234567890</code>.");
            }
            case BotCallbacks.REMOVE_DUPLICATES -> {
                clearDialContactMessage(chatId, state);
                int removed = db.removeDuplicates(userId);
                clampIndex(state);
                showLoadMenu(chatId, state, callbackMessageId,
                    "♻️ Duplicate cleanup complete. Removed: <b>" + removed + "</b>.");
            }
            case BotCallbacks.CLEAR_ALL -> {
                clearDialContactMessage(chatId, state);
                int removed = db.clearContacts(userId);
                state.setCurrentIndex(0);
                state.setPendingAdContactIndex(null);
                state.setMode(UserMode.NONE);
                db.saveUserState(state);
                showLoadMenu(chatId, state, callbackMessageId,
                    "🧹 Database cleared. Deleted <b>" + removed + "</b> entries.");
            }
            case BotCallbacks.OPEN_ADMIN_MENU -> {
                clearDialContactMessage(chatId, state);
                if (!db.isAdmin(userId)) {
                    showAccessDenied(chatId, state, callbackMessageId);
                } else {
                    state.setMode(UserMode.NONE);
                    db.saveUserState(state);
                    showAdminMenu(chatId, state, callbackMessageId, null);
                }
            }
            case BotCallbacks.ADMIN_USERS -> {
                clearDialContactMessage(chatId, state);
                if (!db.isAdmin(userId)) {
                    showAccessDenied(chatId, state, callbackMessageId);
                } else {
                    showUsersReport(chatId, state, callbackMessageId);
                }
            }
            case BotCallbacks.ADMIN_ADD -> {
                clearDialContactMessage(chatId, state);
                if (!db.isAdmin(userId)) {
                    showAccessDenied(chatId, state, callbackMessageId);
                } else {
                    state.setMode(UserMode.WAITING_ADMIN_ID);
                    db.saveUserState(state);
                    showAdminMenu(chatId, state, callbackMessageId,
                        "➕ Send Telegram ID in the next message.\nExample: <code>123456789</code>");
                }
            }
            case BotCallbacks.ADMIN_EXPORT -> {
                clearDialContactMessage(chatId, state);
                if (!db.isAdmin(userId)) {
                    showAccessDenied(chatId, state, callbackMessageId);
                } else {
                    exportMergedNumbers(chatId, state, callbackMessageId);
                }
            }
            default -> {
                clearDialContactMessage(chatId, state);
                showMainMenu(chatId, state, null);
            }
        }
    }

    private void processDocumentUpload(long chatId, UserState state, Message message) {
        if (state.getMode() != UserMode.WAITING_FILE && state.getMode() != UserMode.NONE) {
            showLoadMenu(chatId, state, null,
                "⚠️ File upload is not expected right now.\nOpen <b>LOAD NUMBERS</b> and choose <b>LOAD FILE</b>.");
            return;
        }

        Document document = message.getDocument();
        String fileName = document.getFileName();
        if (fileName == null || fileName.isBlank()) {
            showLoadMenu(chatId, state, null,
                "⚠️ Could not detect file name. Please upload a valid <b>.xlsx</b>, <b>.txt</b> or <b>.csv</b> file.");
            return;
        }

        java.io.File downloaded = null;
        try {
            org.telegram.telegrambots.meta.api.objects.File telegramFile = execute(new GetFile(document.getFileId()));
            downloaded = downloadFile(telegramFile);
            ParsedBatch parsed;
            try (InputStream in = Files.newInputStream(downloaded.toPath())) {
                parsed = importService.parseByFileName(fileName, in);
            }

            ImportReport report = db.addContacts(state.getUserId(), parsed.getContacts(), parsed.getInvalidCount());
            clampIndex(state);
            state.setPendingAdContactIndex(null);
            state.setMode(UserMode.NONE);
            db.saveUserState(state);

            String summary = buildImportSummary("✅ File imported successfully.", report)
                + "\n\n<b>You can now press \"SEND CONTACT\" to begin calling.</b>";
            showLoadMenu(chatId, state, null, summary);
        } catch (IllegalArgumentException e) {
            showLoadMenu(chatId, state, null,
                "⚠️ " + TextFormatter.esc(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process uploaded file", e);
            showLoadMenu(chatId, state, null,
                "❌ Failed to parse file. Please check format and try again.");
        } finally {
            if (downloaded != null && downloaded.exists()) {
                try {
                    Files.deleteIfExists(downloaded.toPath());
                } catch (IOException ignored) {
                    // Nothing critical.
                }
            }
        }
    }

    private void processPastedText(long chatId, UserState state, String text) {
        ParsedBatch parsed = importService.parsePlainText(text);
        ImportReport report = db.addContacts(state.getUserId(), parsed.getContacts(), parsed.getInvalidCount());

        clampIndex(state);
        state.setPendingAdContactIndex(null);
        state.setMode(UserMode.NONE);
        db.saveUserState(state);

        String summary = buildImportSummary("✅ Text list imported.", report)
            + "\n\n<b>You can now press \"SEND CONTACT\" to begin calling.</b>";
        showLoadMenu(chatId, state, null, summary);
    }

    private void processAdminGrant(long chatId, UserState state, String rawId) {
        if (!db.isAdmin(state.getUserId())) {
            showAccessDenied(chatId, state, null);
            return;
        }

        String trimmed = rawId == null ? "" : rawId.trim();
        if (!trimmed.matches("^-?\\d+$")) {
            showAdminMenu(chatId, state, null,
                "⚠️ Invalid ID format. Please send digits only, for example <code>123456789</code>.");
            return;
        }

        long targetId;
        try {
            targetId = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            showAdminMenu(chatId, state, null,
                "⚠️ ID is too large. Please send a valid Telegram numeric ID.");
            return;
        }

        if (targetId <= 0) {
            showAdminMenu(chatId, state, null,
                "⚠️ Telegram ID must be a positive number.");
            return;
        }

        db.addAdmin(targetId);
        state.setMode(UserMode.NONE);
        db.saveUserState(state);
        showAdminMenu(chatId, state, null,
            "✅ Admin rights granted to <code>" + targetId + "</code>.");
    }

    private void exportMergedNumbers(long chatId, UserState state, Integer preferredMessageId) {
        List<String> phones = db.getAllUniquePhones();
        if (phones.isEmpty()) {
            showAdminMenu(chatId, state, preferredMessageId,
                "📭 Global export is empty. No numbers found in user databases yet.");
            return;
        }

        Path exportFile = null;
        try {
            Path tmpDir = Files.createTempDirectory("speedcaller_export_");
            String fileName = "all_numbers_" + EXPORT_TIMESTAMP.format(LocalDateTime.now()) + ".txt";
            exportFile = tmpDir.resolve(fileName);

            try (BufferedWriter writer = Files.newBufferedWriter(exportFile)) {
                for (String phone : phones) {
                    writer.write(phone);
                    writer.newLine();
                }
            }

            SendDocument sendDocument = new SendDocument();
            sendDocument.setChatId(Long.toString(chatId));
            sendDocument.setDocument(new InputFile(exportFile.toFile(), fileName));
            sendDocument.setCaption("🗃 Global numbers export\nUnique numbers: " + phones.size());
            execute(sendDocument);

            showAdminMenu(chatId, state, preferredMessageId,
                "✅ Export generated and sent.\nUnique numbers in file: <b>" + phones.size() + "</b>.");
        } catch (Exception e) {
            log.error("Failed to export merged numbers", e);
            showAdminMenu(chatId, state, preferredMessageId,
                "❌ Failed to generate export file. Please try again.");
        } finally {
            if (exportFile != null) {
                try {
                    Files.deleteIfExists(exportFile);
                    Path parent = exportFile.getParent();
                    if (parent != null) {
                        Files.deleteIfExists(parent);
                    }
                } catch (IOException ignored) {
                    // Temporary files cleanup only.
                }
            }
        }
    }

    private void showMainMenu(long chatId, UserState state, String statusMessage) {
        int total = db.countContacts(state.getUserId());
        StringBuilder text = new StringBuilder();
        text.append("⚡ <b>SpeedCallerBot</b>\n\n")
            .append("Fast, clean and convenient calling workflow for large phone lists.\n\n")
            .append("📦 <b>Your current database:</b> ")
            .append(total)
            .append(" number(s).\n\n")
            .append("Choose your next step below:");

        if (statusMessage != null && !statusMessage.isBlank()) {
            text.append("\n\n").append(statusMessage);
        }

        InlineKeyboardMarkup markup = keyboard(
            callbackButton("🚀 START", BotCallbacks.START_CALLING),
            callbackButton("📥 LOAD NUMBERS", BotCallbacks.OPEN_LOAD_MENU),
            db.isAdmin(state.getUserId()) ? callbackButton("🛠 ADMIN PANEL", BotCallbacks.OPEN_ADMIN_MENU) : null
        );

        renderScreen(chatId, state, null, text.toString(), markup);
    }

    private void showLoadMenu(long chatId, UserState state, Integer preferredMessageId, String statusMessage) {
        int total = db.countContacts(state.getUserId());
        StringBuilder text = new StringBuilder();
        text.append("📥 <b>Load Numbers Center</b>\n\n")
            .append("Upload more contacts without losing your existing list.\n")
            .append("Supported formats: <b>.xlsx</b>, <b>.txt</b>, <b>.csv</b>.\n\n")
            .append("📦 <b>Stored now:</b> ")
            .append(total)
            .append(" number(s).\n")
            .append("Duplicates are prevented automatically.");

        if (statusMessage != null && !statusMessage.isBlank()) {
            text.append("\n\n").append(statusMessage);
        }

        InlineKeyboardMarkup markup = keyboard(
            callbackButton("📤 LOAD FILE", BotCallbacks.LOAD_FILE),
            callbackButton("📝 PASTE TEXT LIST", BotCallbacks.LOAD_TEXT),
            callbackButton("📇 OPEN CONTACT CARDS", BotCallbacks.START_CALLING),
            callbackButton("♻️ REMOVE DUPLICATES", BotCallbacks.REMOVE_DUPLICATES),
            callbackButton("🧹 CLEAR ALL", BotCallbacks.CLEAR_ALL),
            callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)
        );

        renderScreen(chatId, state, preferredMessageId, text.toString(), markup);
    }

    private void showCallCard(long chatId, UserState state, Integer preferredMessageId, String statusMessage) {
        showCallCard(chatId, state, preferredMessageId, statusMessage, null);
    }

    private void showCallCard(long chatId, UserState state, Integer preferredMessageId, String statusMessage, String adLanguage) {
        showCallCard(chatId, state, preferredMessageId, statusMessage, false, adLanguage);
    }

    private void showCallCard(long chatId, UserState state, Integer preferredMessageId, String statusMessage, boolean forceReplace) {
        showCallCard(chatId, state, preferredMessageId, statusMessage, forceReplace, null);
    }

    private void showCallCard(long chatId,
                              UserState state,
                              Integer preferredMessageId,
                              String statusMessage,
                              boolean forceReplace,
                              String adLanguage) {
        int total = db.countContacts(state.getUserId());
        if (total <= 0) {
            if (state.getPendingAdContactIndex() != null) {
                state.setPendingAdContactIndex(null);
                db.saveUserState(state);
            }
            String text = "📭 <b>No numbers yet</b>\n\n"
                + "Load your first database to start calling.\n"
                + "You can upload <b>.xlsx</b> or send a text list.";

            InlineKeyboardMarkup markup = keyboard(
                callbackButton("📥 LOAD NUMBERS", BotCallbacks.OPEN_LOAD_MENU),
                callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)
            );

            renderScreen(chatId, state, preferredMessageId, text, markup, forceReplace);
            return;
        }

        clampIndex(state);
        Integer pendingAdContactIndex = state.getPendingAdContactIndex();
        if (pendingAdContactIndex != null) {
            if (!adsgramService.isEnabled() || pendingAdContactIndex < 0 || pendingAdContactIndex >= total) {
                state.setPendingAdContactIndex(null);
                db.saveUserState(state);
            } else {
                showAdsCard(chatId, state, preferredMessageId, total, forceReplace, adLanguage);
                return;
            }
        }

        Optional<ContactRecord> optionalContact = db.getContactByIndex(state.getUserId(), state.getCurrentIndex());
        if (optionalContact.isEmpty()) {
            state.setCurrentIndex(0);
            db.saveUserState(state);
            optionalContact = db.getContactByIndex(state.getUserId(), 0);
            if (optionalContact.isEmpty()) {
                showCallCard(chatId, state, preferredMessageId, statusMessage, adLanguage);
                return;
            }
        }

        ContactRecord contact = optionalContact.get();
        int currentPosition = state.getCurrentIndex() + 1;
        String displayName = contact.getDisplayName() == null ? "" : contact.getDisplayName().trim();
        boolean genericClientName = displayName.isBlank() || "Client".equalsIgnoreCase(displayName);

        StringBuilder text = new StringBuilder();
        if (genericClientName) {
            text.append("👤 <b>Client</b>\n\n");
        } else {
            text.append("👤 <b>Client:</b> ")
                .append(TextFormatter.esc(displayName))
                .append("\n\n");
        }
        text
            .append("📞 <b>Tel:</b> ")
            .append(buildPhoneHtml(contact.getPhone()))
            .append("\n")
            .append("Tip: use SEND CONTACT to open the dialer quickly.\n\n")
            .append("📊 <b>Progress:</b> ")
            .append(currentPosition)
            .append("/")
            .append(total)
            .append("\n\n")
            .append("Press <b>SEND CONTACT</b> to get a one-tap call card.");

        if (statusMessage != null && !statusMessage.isBlank()) {
            text.append("\n\n").append(statusMessage);
        }

        InlineKeyboardMarkup markup = keyboardRows(
            new Button[]{callbackButton("📇 SEND CONTACT", BotCallbacks.CALL_NOW)},
            new Button[]{callbackButton("⏮ BACK", BotCallbacks.CALL_BACK), callbackButton("⏭ SKIP", BotCallbacks.CALL_SKIP)},
            new Button[]{callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)}
        );

        renderScreen(chatId, state, preferredMessageId, text.toString(), markup, forceReplace);
    }

    private void showAdsCard(long chatId,
                             UserState state,
                             Integer preferredMessageId,
                             int totalContacts,
                             boolean forceReplace,
                             String adLanguage) {
        Optional<AdsgramAd> maybeAd = adsgramService.pickBestAd(state.getUserId(), adLanguage);
        if (maybeAd.isEmpty()) {
            state.setPendingAdContactIndex(null);
            db.saveUserState(state);
            showCallCard(chatId, state, preferredMessageId, null, forceReplace, adLanguage);
            return;
        }

        AdsgramAd ad = maybeAd.get();
        int pendingIndex = Math.max(0, state.getPendingAdContactIndex() == null ? 0 : state.getPendingAdContactIndex());

        StringBuilder text = new StringBuilder();
        text.append("📣 <b>Sponsored</b>\n\n")
            .append(ad.getTextHtml())
            .append("\n\n")
            .append("You can skip this offer at any time.\n")
            .append("📊 <b>Progress:</b> ")
            .append(Math.min(pendingIndex, totalContacts))
            .append("/")
            .append(totalContacts);

        List<Button[]> rows = new ArrayList<>();
        if (isHttpUrl(ad.getClickUrl())) {
            rows.add(new Button[]{urlButton(normalizeAdButtonLabel(ad.getButtonName()), ad.getClickUrl())});
        }
        rows.add(new Button[]{callbackButton("⏮ BACK", BotCallbacks.CALL_BACK), callbackButton("⏭ SKIP", BotCallbacks.CALL_SKIP)});
        rows.add(new Button[]{callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)});

        InlineKeyboardMarkup markup = keyboardRows(rows.toArray(new Button[0][]));
        if (isHttpUrl(ad.getImageUrl())) {
            renderPhotoScreen(chatId, state, preferredMessageId, ad.getImageUrl(), text.toString(), markup, true);
            return;
        }
        renderScreen(chatId, state, preferredMessageId, text.toString(), markup, true, true);
    }

    private void showAdminMenu(long chatId, UserState state, Integer preferredMessageId, String statusMessage) {
        int users = db.getUsersSummary().size();
        int uniquePhones = db.getAllUniquePhones().size();

        StringBuilder text = new StringBuilder();
        text.append("🛠 <b>Admin Panel</b>\n\n")
            .append("Centralized control for the whole bot database.\n\n")
            .append("👥 <b>Users:</b> ")
            .append(users)
            .append("\n")
            .append("📦 <b>Global unique numbers:</b> ")
            .append(uniquePhones);

        if (statusMessage != null && !statusMessage.isBlank()) {
            text.append("\n\n").append(statusMessage);
        }

        InlineKeyboardMarkup markup = keyboard(
            callbackButton("👥 SHOW ALL USERS", BotCallbacks.ADMIN_USERS),
            callbackButton("➕ ADD ADMIN BY TG ID", BotCallbacks.ADMIN_ADD),
            callbackButton("🗃 EXPORT ALL NUMBERS", BotCallbacks.ADMIN_EXPORT),
            callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)
        );

        renderScreen(chatId, state, preferredMessageId, text.toString(), markup);
    }

    private void showUsersReport(long chatId, UserState state, Integer preferredMessageId) {
        List<UserSummary> users = db.getUsersSummary();
        if (users.isEmpty()) {
            showAdminMenu(chatId, state, preferredMessageId,
                "📭 Users list is empty.");
            return;
        }

        StringBuilder text = new StringBuilder();
        text.append("👥 <b>All Users</b>\n\n");

        int displayLimit = Math.min(users.size(), 50);
        for (int i = 0; i < displayLimit; i++) {
            UserSummary u = users.get(i);
            String usernamePart = (u.getUsername() == null || u.getUsername().isBlank())
                ? "no_username"
                : "@" + TextFormatter.esc(u.getUsername());

            text.append(i + 1)
                .append(". <code>")
                .append(u.getTgId())
                .append("</code>")
                .append(u.isAdmin() ? " 👑" : "")
                .append("\n")
                .append("   ")
                .append(usernamePart)
                .append(" | ")
                .append(TextFormatter.esc(TextFormatter.fullName(u.getFirstName(), u.getLastName())))
                .append("\n")
                .append("   Numbers: <b>")
                .append(u.getContactsCount())
                .append("</b>\n\n");
        }

        if (users.size() > displayLimit) {
            text.append("…and ").append(users.size() - displayLimit).append(" more users.");
        }

        InlineKeyboardMarkup markup = keyboard(
            callbackButton("⬅️ BACK TO ADMIN", BotCallbacks.OPEN_ADMIN_MENU),
            callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)
        );

        renderScreen(chatId, state, preferredMessageId, text.toString(), markup);
    }

    private void showAccessDenied(long chatId, UserState state, Integer preferredMessageId) {
        String text = "⛔ <b>Access denied</b>\n\n"
            + "This section is available only for administrators.";
        InlineKeyboardMarkup markup = keyboard(
            callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)
        );
        renderScreen(chatId, state, preferredMessageId, text, markup);
    }

    private void renderScreen(long chatId,
                              UserState state,
                              Integer preferredMessageId,
                              String text,
                              InlineKeyboardMarkup markup) {
        renderScreen(chatId, state, preferredMessageId, text, markup, false);
    }

    private void renderScreen(long chatId,
                              UserState state,
                              Integer preferredMessageId,
                              String text,
                              InlineKeyboardMarkup markup,
                              boolean forceReplace) {
        renderScreen(chatId, state, preferredMessageId, text, markup, forceReplace, false);
    }

    private void renderScreen(long chatId,
                              UserState state,
                              Integer preferredMessageId,
                              String text,
                              InlineKeyboardMarkup markup,
                              boolean forceReplace,
                              boolean protectContent) {
        Integer targetMessageId = preferredMessageId != null ? preferredMessageId : state.getLastBotMessageId();

        if ((forceReplace || protectContent) && targetMessageId != null) {
            safeDeleteMessage(chatId, targetMessageId);
            targetMessageId = null;
            state.setLastBotMessageId(null);
            db.saveUserState(state);
        }

        if (targetMessageId != null) {
            EditMessageText editMessage = new EditMessageText();
            editMessage.setChatId(Long.toString(chatId));
            editMessage.setMessageId(targetMessageId);
            editMessage.setText(text);
            editMessage.setParseMode("HTML");
            editMessage.setReplyMarkup(markup);

            try {
                execute(editMessage);
                state.setLastBotMessageId(targetMessageId);
                db.saveUserState(state);
                return;
            } catch (TelegramApiException e) {
                if (isMessageNotModified(e)) {
                    state.setLastBotMessageId(targetMessageId);
                    db.saveUserState(state);
                    return;
                }
                log.debug("Unable to edit message {}, fallback to send", targetMessageId, e);
            }
        }

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(Long.toString(chatId));
        sendMessage.setText(text);
        sendMessage.setParseMode("HTML");
        sendMessage.setReplyMarkup(markup);
        if (protectContent) {
            sendMessage.setProtectContent(true);
        }

        try {
            Message sent = execute(sendMessage);
            state.setLastBotMessageId(sent.getMessageId());
            db.saveUserState(state);
        } catch (TelegramApiException e) {
            log.error("Failed to send screen message", e);
        }
    }

    private void renderPhotoScreen(long chatId,
                                   UserState state,
                                   Integer preferredMessageId,
                                   String photoUrl,
                                   String caption,
                                   InlineKeyboardMarkup markup,
                                   boolean protectContent) {
        Integer targetMessageId = preferredMessageId != null ? preferredMessageId : state.getLastBotMessageId();
        if (targetMessageId != null) {
            EditMessageMedia editMessageMedia = new EditMessageMedia();
            editMessageMedia.setChatId(Long.toString(chatId));
            editMessageMedia.setMessageId(targetMessageId);

            InputMediaPhoto media = new InputMediaPhoto();
            media.setMedia(photoUrl);
            media.setCaption(trimCaption(caption));
            media.setParseMode("HTML");

            editMessageMedia.setMedia(media);
            editMessageMedia.setReplyMarkup(markup);

            try {
                execute(editMessageMedia);
                state.setLastBotMessageId(targetMessageId);
                db.saveUserState(state);
                return;
            } catch (TelegramApiException e) {
                if (isMessageNotModified(e)) {
                    state.setLastBotMessageId(targetMessageId);
                    db.saveUserState(state);
                    return;
                }
                log.debug("Unable to edit photo screen {}, fallback to send", targetMessageId, e);
            }
        }

        SendPhoto sendPhoto = new SendPhoto();
        sendPhoto.setChatId(Long.toString(chatId));
        sendPhoto.setPhoto(new InputFile(photoUrl));
        sendPhoto.setCaption(trimCaption(caption));
        sendPhoto.setParseMode("HTML");
        sendPhoto.setReplyMarkup(markup);
        if (protectContent) {
            sendPhoto.setProtectContent(true);
        }

        try {
            Message sent = execute(sendPhoto);
            state.setLastBotMessageId(sent.getMessageId());
            db.saveUserState(state);
        } catch (TelegramApiException e) {
            log.error("Failed to send photo screen message", e);
            renderScreen(chatId, state, preferredMessageId, caption, markup, true, protectContent);
        }
    }

    private void handleCallSkip(long chatId, UserState state, Integer preferredMessageId, String adLanguage) {
        clearDialContactMessage(chatId, state);

        int total = db.countContacts(state.getUserId());
        if (total <= 0) {
            state.setCurrentIndex(0);
            state.setPendingAdContactIndex(null);
            db.saveUserState(state);
            showCallCard(chatId, state, preferredMessageId, null, adLanguage);
            return;
        }

        clampIndex(state);

        Integer pendingAdIndex = state.getPendingAdContactIndex();
        if (pendingAdIndex != null) {
            if (pendingAdIndex >= total) {
                state.setPendingAdContactIndex(null);
                db.saveUserState(state);
                showCallCard(chatId, state, preferredMessageId, null, adLanguage);
                return;
            }

            state.setCurrentIndex(Math.max(0, pendingAdIndex));
            state.setPendingAdContactIndex(null);
            db.saveUserState(state);
            showCallCard(chatId, state, preferredMessageId, null, true, adLanguage);
            return;
        }

        int currentIndex = state.getCurrentIndex();
        if (currentIndex >= total - 1) {
            return;
        }

        int nextIndex = currentIndex + 1;
        state.setCurrentIndex(nextIndex);
        if (shouldShowAdBeforeContact(nextIndex)) {
            state.setPendingAdContactIndex(nextIndex);
        } else {
            state.setPendingAdContactIndex(null);
        }
        db.saveUserState(state);

        showCallCard(chatId, state, preferredMessageId, null, adLanguage);
    }

    private void handleCallBack(long chatId, UserState state, Integer preferredMessageId, String adLanguage) {
        clearDialContactMessage(chatId, state);

        int total = db.countContacts(state.getUserId());
        if (total <= 0) {
            state.setCurrentIndex(0);
            state.setPendingAdContactIndex(null);
            db.saveUserState(state);
            showCallCard(chatId, state, preferredMessageId, null, adLanguage);
            return;
        }

        clampIndex(state);

        Integer pendingAdIndex = state.getPendingAdContactIndex();
        if (pendingAdIndex != null) {
            int previousContactIndex = pendingAdIndex - 1;
            if (previousContactIndex < 0) {
                previousContactIndex = 0;
            }

            state.setCurrentIndex(previousContactIndex);
            state.setPendingAdContactIndex(null);
            db.saveUserState(state);
            showCallCard(chatId, state, preferredMessageId, null, true, adLanguage);
            return;
        }

        int currentIndex = state.getCurrentIndex();
        if (currentIndex <= 0) {
            return;
        }

        if (shouldShowAdBeforeContact(currentIndex)) {
            state.setPendingAdContactIndex(currentIndex);
            db.saveUserState(state);
            showCallCard(chatId, state, preferredMessageId, null, adLanguage);
            return;
        }

        state.setCurrentIndex(currentIndex - 1);
        state.setPendingAdContactIndex(null);
        db.saveUserState(state);
        showCallCard(chatId, state, preferredMessageId, null, adLanguage);
    }

    private boolean shouldShowAdBeforeContact(int contactIndex) {
        int adsEvery = adsgramService.getAdsEveryCards();
        return adsgramService.isEnabled()
            && adsEvery > 0
            && contactIndex > 0
            && contactIndex % adsEvery == 0;
    }

    private void clampIndex(UserState state) {
        int total = db.countContacts(state.getUserId());
        if (total <= 0) {
            state.setCurrentIndex(0);
            state.setPendingAdContactIndex(null);
        } else if (state.getCurrentIndex() >= total) {
            state.setCurrentIndex(total - 1);
        } else if (state.getCurrentIndex() < 0) {
            state.setCurrentIndex(0);
        }
        Integer pendingAdContactIndex = state.getPendingAdContactIndex();
        if (pendingAdContactIndex != null) {
            if (pendingAdContactIndex <= 0 || pendingAdContactIndex >= total) {
                state.setPendingAdContactIndex(null);
            }
        }
        db.saveUserState(state);
    }

    private String buildImportSummary(String title, ImportReport report) {
        return title + "\n"
            + "• Added: <b>" + report.getAdded() + "</b>\n"
            + "• Duplicates skipped: <b>" + report.getDuplicates() + "</b>\n"
            + "• Invalid rows: <b>" + report.getInvalid() + "</b>";
    }

    private boolean sendDialContact(long chatId, UserState state, ContactRecord contact) {
        clearDialContactMessage(chatId, state);

        SendContact sendContact = new SendContact();
        sendContact.setChatId(Long.toString(chatId));
        sendContact.setPhoneNumber(contact.getPhone());
        sendContact.setFirstName(buildContactFirstName(contact.getDisplayName()));

        try {
            Message sent = execute(sendContact);
            state.setLastCallContactMessageId(sent.getMessageId());
            db.saveUserState(state);
            return true;
        } catch (TelegramApiException e) {
            log.error("Failed to send dial contact", e);
            return false;
        }
    }

    private void clearDialContactMessage(long chatId, UserState state) {
        if (state == null || state.getLastCallContactMessageId() == null) {
            return;
        }
        safeDeleteMessage(chatId, state.getLastCallContactMessageId());
        state.setLastCallContactMessageId(null);
        db.saveUserState(state);
    }

    private String buildContactFirstName(String displayName) {
        String normalized = displayName == null ? "" : displayName.trim();
        String name = (normalized.isBlank() || "No Name".equalsIgnoreCase(normalized) || "Client".equalsIgnoreCase(normalized))
            ? "Client"
            : normalized;
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private String labelOrDefault(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return fallback;
        }
        String singleLine = normalized.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() > 64 ? singleLine.substring(0, 64) : singleLine;
    }

    private String normalizeAdButtonLabel(String rawLabel) {
        String normalized = labelOrDefault(rawLabel, "🔗 Open Offer");
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (lowered.contains("start") || lowered.contains("начать")) {
            return "🔗 Open Offer";
        }
        return normalized;
    }

    private String buildPhoneHtml(String rawPhone) {
        String display = rawPhone == null ? "" : rawPhone.trim();
        if (display.isEmpty()) {
            return "—";
        }
        return TextFormatter.esc(display);
    }

    private String trimCaption(String value) {
        if (value == null) {
            return "";
        }
        final int maxCaptionLength = 1024;
        return value.length() <= maxCaptionLength
            ? value
            : value.substring(0, maxCaptionLength - 1) + "…";
    }

    private boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("https://") || normalized.startsWith("http://");
    }

    private boolean isMessageNotModified(TelegramApiException e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String lower = e.getMessage().toLowerCase(Locale.ROOT);
        return lower.contains("message is not modified");
    }

    private String normalizeAdsLanguage(String rawLanguageCode) {
        if (rawLanguageCode == null) {
            return null;
        }
        String value = rawLanguageCode.trim().toLowerCase(Locale.ROOT);
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
        return value.length() >= 2 ? value.substring(0, 2) : value;
    }

    private void registerUser(org.telegram.telegrambots.meta.api.objects.User user) {
        if (user == null) {
            return;
        }
        db.registerOrUpdateUser(
            user.getId(),
            user.getUserName(),
            user.getFirstName(),
            user.getLastName()
        );

        if (config.getOwnerIds().contains(user.getId())) {
            db.addAdmin(user.getId());
        }
    }

    private InlineKeyboardMarkup keyboard(Button... buttons) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Button button : buttons) {
            if (button == null) {
                continue;
            }
            rows.add(buildButtonRow(button));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup keyboardRows(Button[]... rowsButtons) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Button[] rowButtons : rowsButtons) {
            if (rowButtons == null || rowButtons.length == 0) {
                continue;
            }

            List<InlineKeyboardButton> row = new ArrayList<>();
            for (Button button : rowButtons) {
                if (button == null) {
                    continue;
                }
                row.add(buildInlineButton(button));
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private List<InlineKeyboardButton> buildButtonRow(Button button) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(buildInlineButton(button));
        return row;
    }

    private InlineKeyboardButton buildInlineButton(Button button) {
        InlineKeyboardButton tgButton = new InlineKeyboardButton();
        tgButton.setText(button.text());
        if (button.url() != null) {
            tgButton.setUrl(button.url());
        } else {
            tgButton.setCallbackData(button.callbackData());
        }
        return tgButton;
    }

    private Button callbackButton(String text, String callbackData) {
        return new Button(text, callbackData, null);
    }

    private Button urlButton(String text, String url) {
        return new Button(text, null, url);
    }

    private void safeDeleteMessage(long chatId, Integer messageId) {
        if (messageId == null) {
            return;
        }
        try {
            DeleteMessage deleteMessage = new DeleteMessage(Long.toString(chatId), messageId);
            execute(deleteMessage);
        } catch (TelegramApiException ignored) {
            // Some messages cannot be deleted depending on chat settings or age.
        }
    }

    private void answerCallback(String callbackId, String text) {
        if (callbackId == null || callbackId.isBlank()) {
            return;
        }

        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackId);
        if (text != null && !text.isBlank()) {
            answer.setText(text);
        }

        try {
            execute(answer);
        } catch (TelegramApiException ignored) {
            // Callback answers are optional for UX only.
        }
    }

    private Long extractUserId(Update update) {
        if (update == null) {
            return null;
        }
        if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
            return update.getCallbackQuery().getFrom().getId();
        }
        if (update.hasMessage() && update.getMessage().getFrom() != null) {
            return update.getMessage().getFrom().getId();
        }
        return null;
    }

    private record Button(String text, String callbackData, String url) {
    }
}
