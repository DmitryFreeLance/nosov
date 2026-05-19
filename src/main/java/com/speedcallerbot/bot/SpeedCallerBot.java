package com.speedcallerbot.bot;

import com.speedcallerbot.config.BotConfig;
import com.speedcallerbot.db.DatabaseService;
import com.speedcallerbot.model.ContactRecord;
import com.speedcallerbot.model.ImportReport;
import com.speedcallerbot.model.ParsedBatch;
import com.speedcallerbot.model.UserMode;
import com.speedcallerbot.model.UserState;
import com.speedcallerbot.model.UserSummary;
import com.speedcallerbot.service.NumberImportService;
import com.speedcallerbot.util.TextFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
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
    private final ConcurrentMap<Long, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    public SpeedCallerBot(BotConfig config, DatabaseService db) {
        super(config.getToken());
        this.config = config;
        this.db = db;
        this.importService = new NumberImportService();
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
            db.saveUserState(state);
            showMainMenu(chatId, state, null);
            safeDeleteMessage(chatId, message.getMessageId());
            return;
        }

        if (message.hasText() && message.getText().startsWith("/admin")) {
            if (!db.isAdmin(userId)) {
                showAccessDenied(chatId, state, null);
            } else {
                state.setMode(UserMode.NONE);
                db.saveUserState(state);
                showAdminMenu(chatId, state, null, null);
            }
            safeDeleteMessage(chatId, message.getMessageId());
            return;
        }

        if (message.hasDocument()) {
            processDocumentUpload(chatId, state, message);
            safeDeleteMessage(chatId, message.getMessageId());
            return;
        }

        if (state.getMode() == UserMode.WAITING_TEXT && message.hasText()) {
            processPastedText(chatId, state, message.getText());
            safeDeleteMessage(chatId, message.getMessageId());
            return;
        }

        if (state.getMode() == UserMode.WAITING_ADMIN_ID && message.hasText()) {
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
                showCallCard(chatId, state, callbackMessageId, null);
            }
            case BotCallbacks.OPEN_LOAD_MENU -> {
                state.setMode(UserMode.NONE);
                db.saveUserState(state);
                showLoadMenu(chatId, state, callbackMessageId, null);
            }
            case BotCallbacks.OPEN_MAIN_MENU -> {
                state.setMode(UserMode.NONE);
                db.saveUserState(state);
                showMainMenu(chatId, state, null);
            }
            case BotCallbacks.CALL_SKIP -> {
                shiftIndex(state, +1);
                showCallCard(chatId, state, callbackMessageId, null);
            }
            case BotCallbacks.CALL_BACK -> {
                shiftIndex(state, -1);
                showCallCard(chatId, state, callbackMessageId, null);
            }
            case BotCallbacks.LOAD_FILE -> {
                state.setMode(UserMode.WAITING_FILE);
                db.saveUserState(state);
                showLoadMenu(chatId, state, callbackMessageId,
                    "📤 Send a <b>.xlsx</b>, <b>.txt</b> or <b>.csv</b> file in the next message.\n"
                        + "I will import numbers and remove duplicates automatically.");
            }
            case BotCallbacks.LOAD_TEXT -> {
                state.setMode(UserMode.WAITING_TEXT);
                db.saveUserState(state);
                showLoadMenu(chatId, state, callbackMessageId,
                    "📝 Paste your phone list in the next message.\n"
                        + "Supported formats: one number per line, or <code>Name, +1234567890</code>.");
            }
            case BotCallbacks.REMOVE_DUPLICATES -> {
                int removed = db.removeDuplicates(userId);
                clampIndex(state);
                showLoadMenu(chatId, state, callbackMessageId,
                    "♻️ Duplicate cleanup complete. Removed: <b>" + removed + "</b>.");
            }
            case BotCallbacks.CLEAR_ALL -> {
                int removed = db.clearContacts(userId);
                state.setCurrentIndex(0);
                state.setMode(UserMode.NONE);
                db.saveUserState(state);
                showLoadMenu(chatId, state, callbackMessageId,
                    "🧹 Database cleared. Deleted <b>" + removed + "</b> entries.");
            }
            case BotCallbacks.OPEN_ADMIN_MENU -> {
                if (!db.isAdmin(userId)) {
                    showAccessDenied(chatId, state, callbackMessageId);
                } else {
                    state.setMode(UserMode.NONE);
                    db.saveUserState(state);
                    showAdminMenu(chatId, state, callbackMessageId, null);
                }
            }
            case BotCallbacks.ADMIN_USERS -> {
                if (!db.isAdmin(userId)) {
                    showAccessDenied(chatId, state, callbackMessageId);
                } else {
                    showUsersReport(chatId, state, callbackMessageId);
                }
            }
            case BotCallbacks.ADMIN_ADD -> {
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
                if (!db.isAdmin(userId)) {
                    showAccessDenied(chatId, state, callbackMessageId);
                } else {
                    exportMergedNumbers(chatId, state, callbackMessageId);
                }
            }
            default -> showMainMenu(chatId, state, null);
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
            state.setMode(UserMode.NONE);
            db.saveUserState(state);

            showLoadMenu(chatId, state, null, buildImportSummary("✅ File imported successfully.", report));
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
        state.setMode(UserMode.NONE);
        db.saveUserState(state);

        showLoadMenu(chatId, state, null, buildImportSummary("✅ Text list imported.", report));
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
            callbackButton("♻️ REMOVE DUPLICATES", BotCallbacks.REMOVE_DUPLICATES),
            callbackButton("🧹 CLEAR ALL", BotCallbacks.CLEAR_ALL),
            callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)
        );

        renderScreen(chatId, state, preferredMessageId, text.toString(), markup);
    }

    private void showCallCard(long chatId, UserState state, Integer preferredMessageId, String statusMessage) {
        int total = db.countContacts(state.getUserId());
        if (total <= 0) {
            String text = "📭 <b>No numbers yet</b>\n\n"
                + "Load your first database to start calling.\n"
                + "You can upload <b>.xlsx</b> or send a text list.";

            InlineKeyboardMarkup markup = keyboard(
                callbackButton("📥 LOAD NUMBERS", BotCallbacks.OPEN_LOAD_MENU),
                callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)
            );

            renderScreen(chatId, state, preferredMessageId, text, markup);
            return;
        }

        clampIndex(state);
        Optional<ContactRecord> optionalContact = db.getContactByIndex(state.getUserId(), state.getCurrentIndex());
        if (optionalContact.isEmpty()) {
            state.setCurrentIndex(0);
            db.saveUserState(state);
            optionalContact = db.getContactByIndex(state.getUserId(), 0);
            if (optionalContact.isEmpty()) {
                showCallCard(chatId, state, preferredMessageId, statusMessage);
                return;
            }
        }

        ContactRecord contact = optionalContact.get();
        int currentPosition = state.getCurrentIndex() + 1;

        StringBuilder text = new StringBuilder();
        text.append("👤 <b>Client:</b> ")
            .append(TextFormatter.esc(contact.getDisplayName()))
            .append("\n\n")
            .append("📞 <b>Tel:</b> <code>")
            .append(TextFormatter.esc(contact.getPhone()))
            .append("</code>\n\n")
            .append("📊 <b>Progress:</b> ")
            .append(currentPosition)
            .append("/")
            .append(total)
            .append("\n\n")
            .append("Tap <b>CALL</b> to open your dialer instantly.");

        if (statusMessage != null && !statusMessage.isBlank()) {
            text.append("\n\n").append(statusMessage);
        }

        InlineKeyboardMarkup markup = keyboard(
            urlButton("📞 CALL", "tel:" + contact.getPhone()),
            callbackButton("⏭ SKIP", BotCallbacks.CALL_SKIP),
            callbackButton("⏮ BACK", BotCallbacks.CALL_BACK),
            callbackButton("🏠 MAIN MENU", BotCallbacks.OPEN_MAIN_MENU)
        );

        renderScreen(chatId, state, preferredMessageId, text.toString(), markup);
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
        Integer targetMessageId = preferredMessageId != null ? preferredMessageId : state.getLastBotMessageId();

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
                log.debug("Unable to edit message {}, fallback to send", targetMessageId, e);
            }
        }

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(Long.toString(chatId));
        sendMessage.setText(text);
        sendMessage.setParseMode("HTML");
        sendMessage.setReplyMarkup(markup);

        try {
            Message sent = execute(sendMessage);
            state.setLastBotMessageId(sent.getMessageId());
            db.saveUserState(state);
        } catch (TelegramApiException e) {
            log.error("Failed to send screen message", e);
        }
    }

    private void shiftIndex(UserState state, int delta) {
        int total = db.countContacts(state.getUserId());
        if (total <= 0) {
            state.setCurrentIndex(0);
            db.saveUserState(state);
            return;
        }

        int next = state.getCurrentIndex() + delta;
        if (next < 0) {
            next = 0;
        }
        if (next >= total) {
            next = total - 1;
        }

        state.setCurrentIndex(next);
        db.saveUserState(state);
    }

    private void clampIndex(UserState state) {
        int total = db.countContacts(state.getUserId());
        if (total <= 0) {
            state.setCurrentIndex(0);
        } else if (state.getCurrentIndex() >= total) {
            state.setCurrentIndex(total - 1);
        } else if (state.getCurrentIndex() < 0) {
            state.setCurrentIndex(0);
        }
        db.saveUserState(state);
    }

    private String buildImportSummary(String title, ImportReport report) {
        return title + "\n"
            + "• Added: <b>" + report.getAdded() + "</b>\n"
            + "• Duplicates skipped: <b>" + report.getDuplicates() + "</b>\n"
            + "• Invalid rows: <b>" + report.getInvalid() + "</b>";
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

            InlineKeyboardButton tgButton = new InlineKeyboardButton();
            tgButton.setText(button.text());
            if (button.url() != null) {
                tgButton.setUrl(button.url());
            } else {
                tgButton.setCallbackData(button.callbackData());
            }

            List<InlineKeyboardButton> row = new ArrayList<>();
            row.add(tgButton);
            rows.add(row);
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
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
