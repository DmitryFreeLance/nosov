package com.speedcallerbot.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BotConfig {
    private final String token;
    private final String username;
    private final Path dbPath;
    private final Set<Long> ownerIds;

    private BotConfig(String token, String username, Path dbPath, Set<Long> ownerIds) {
        this.token = token;
        this.username = username;
        this.dbPath = dbPath;
        this.ownerIds = ownerIds;
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

        return new BotConfig(token, username, dbPath, Collections.unmodifiableSet(ownerIds));
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
}
