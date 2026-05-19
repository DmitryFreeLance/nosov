package com.speedcallerbot;

import com.speedcallerbot.bot.SpeedCallerBot;
import com.speedcallerbot.config.BotConfig;
import com.speedcallerbot.db.DatabaseService;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) throws Exception {
        BotConfig config = BotConfig.fromEnv();

        DatabaseService databaseService = new DatabaseService(config.getDbPath());
        databaseService.init();
        databaseService.grantAdmins(config.getOwnerIds());

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new SpeedCallerBot(config, databaseService));

        System.out.println("SpeedCallerBot started successfully.");
    }
}
