package com.speedcallerbot.db;

import com.speedcallerbot.model.ContactRecord;
import com.speedcallerbot.model.ImportReport;
import com.speedcallerbot.model.ParsedContact;
import com.speedcallerbot.model.UserMode;
import com.speedcallerbot.model.UserState;
import com.speedcallerbot.model.UserSummary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DatabaseService {
    private final String jdbcUrl;

    public DatabaseService(Path dbPath) {
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create DB directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
    }

    public void init() {
        try (Connection connection = openConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    tg_id INTEGER PRIMARY KEY,
                    username TEXT,
                    first_name TEXT,
                    last_name TEXT,
                    is_admin INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_state (
                    tg_id INTEGER PRIMARY KEY,
                    current_index INTEGER NOT NULL DEFAULT 0,
                    last_bot_message_id INTEGER,
                    last_call_contact_message_id INTEGER,
                    mode TEXT NOT NULL DEFAULT 'NONE',
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (tg_id) REFERENCES users(tg_id) ON DELETE CASCADE
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tg_id INTEGER NOT NULL,
                    display_name TEXT NOT NULL DEFAULT 'No Name',
                    phone TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (tg_id) REFERENCES users(tg_id) ON DELETE CASCADE
                )
                """);

            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_contacts_unique ON contacts(tg_id, phone)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_contacts_tg_id ON contacts(tg_id)");

            ensureColumnExists(connection, "user_state", "last_call_contact_message_id INTEGER");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize database schema", e);
        }
    }

    public void registerOrUpdateUser(long tgId, String username, String firstName, String lastName) {
        String sql = """
            INSERT INTO users (tg_id, username, first_name, last_name, created_at, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT(tg_id) DO UPDATE SET
                username = excluded.username,
                first_name = excluded.first_name,
                last_name = excluded.last_name,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            ps.setString(2, username);
            ps.setString(3, firstName);
            ps.setString(4, lastName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to register user", e);
        }

        String stateSql = "INSERT OR IGNORE INTO user_state (tg_id) VALUES (?)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(stateSql)) {
            ps.setLong(1, tgId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to ensure user state", e);
        }
    }

    public void grantAdmins(Set<Long> tgIds) {
        if (tgIds == null || tgIds.isEmpty()) {
            return;
        }
        for (Long tgId : tgIds) {
            if (tgId != null && tgId > 0) {
                addAdmin(tgId);
            }
        }
    }

    public UserState getUserState(long tgId) {
        String sql = "SELECT tg_id, current_index, last_bot_message_id, last_call_contact_message_id, mode FROM user_state WHERE tg_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String rawMode = rs.getString("mode");
                    UserMode mode;
                    try {
                        mode = UserMode.valueOf(rawMode);
                    } catch (Exception ignored) {
                        mode = UserMode.NONE;
                    }
                    int rawMessageId = rs.getInt("last_bot_message_id");
                    Integer messageId = rs.wasNull() ? null : rawMessageId;
                    int rawCallContactMessageId = rs.getInt("last_call_contact_message_id");
                    Integer callContactMessageId = rs.wasNull() ? null : rawCallContactMessageId;
                    return new UserState(
                        rs.getLong("tg_id"),
                        rs.getInt("current_index"),
                        messageId,
                        callContactMessageId,
                        mode
                    );
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fetch user state", e);
        }

        UserState fallback = new UserState(tgId, 0, null, null, UserMode.NONE);
        saveUserState(fallback);
        return fallback;
    }

    public void saveUserState(UserState state) {
        String sql = """
            INSERT INTO user_state (tg_id, current_index, last_bot_message_id, last_call_contact_message_id, mode, updated_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(tg_id) DO UPDATE SET
                current_index = excluded.current_index,
                last_bot_message_id = excluded.last_bot_message_id,
                last_call_contact_message_id = excluded.last_call_contact_message_id,
                mode = excluded.mode,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, state.getUserId());
            ps.setInt(2, Math.max(0, state.getCurrentIndex()));
            if (state.getLastBotMessageId() == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, state.getLastBotMessageId());
            }
            if (state.getLastCallContactMessageId() == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, state.getLastCallContactMessageId());
            }
            ps.setString(5, state.getMode().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save user state", e);
        }
    }

    public ImportReport addContacts(long tgId, List<ParsedContact> contacts, int invalidFromParser) {
        if (contacts == null || contacts.isEmpty()) {
            return new ImportReport(0, 0, invalidFromParser);
        }

        int added = 0;
        int duplicates = 0;
        String sql = "INSERT OR IGNORE INTO contacts (tg_id, display_name, phone) VALUES (?, ?, ?)";

        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (ParsedContact contact : contacts) {
                if (contact.getPhone() == null || contact.getPhone().isBlank()) {
                    continue;
                }

                ps.setLong(1, tgId);
                ps.setString(2, normalizeName(contact.getDisplayName()));
                ps.setString(3, contact.getPhone());
                int changed = ps.executeUpdate();
                if (changed > 0) {
                    added++;
                } else {
                    duplicates++;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save contacts", e);
        }

        return new ImportReport(added, duplicates, invalidFromParser);
    }

    public int countContacts(long tgId) {
        String sql = "SELECT COUNT(*) FROM contacts WHERE tg_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count contacts", e);
        }
    }

    public Optional<ContactRecord> getContactByIndex(long tgId, int index) {
        if (index < 0) {
            return Optional.empty();
        }

        String sql = """
            SELECT id, tg_id, display_name, phone
            FROM contacts
            WHERE tg_id = ?
            ORDER BY id ASC
            LIMIT 1 OFFSET ?
            """;

        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            ps.setInt(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                return Optional.of(new ContactRecord(
                    rs.getLong("id"),
                    rs.getLong("tg_id"),
                    normalizeName(rs.getString("display_name")),
                    rs.getString("phone")
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load contact by index", e);
        }
    }

    public int removeDuplicates(long tgId) {
        String sql = """
            DELETE FROM contacts
            WHERE tg_id = ?
              AND id NOT IN (
                SELECT min_id
                FROM (
                    SELECT MIN(id) AS min_id
                    FROM contacts
                    WHERE tg_id = ?
                    GROUP BY phone
                )
              )
            """;

        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            ps.setLong(2, tgId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove duplicates", e);
        }
    }

    public int clearContacts(long tgId) {
        String sql = "DELETE FROM contacts WHERE tg_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear contacts", e);
        }
    }

    public boolean isAdmin(long tgId) {
        String sql = "SELECT is_admin FROM users WHERE tg_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check admin role", e);
        }
    }

    public void addAdmin(long tgId) {
        String upsertUser = """
            INSERT INTO users (tg_id, is_admin, created_at, updated_at)
            VALUES (?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT(tg_id) DO UPDATE SET
                is_admin = 1,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(upsertUser)) {
            ps.setLong(1, tgId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to grant admin role", e);
        }

        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO user_state (tg_id) VALUES (?)")) {
            ps.setLong(1, tgId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to ensure state for admin", e);
        }
    }

    public List<UserSummary> getUsersSummary() {
        String sql = """
            SELECT
                u.tg_id,
                u.username,
                u.first_name,
                u.last_name,
                u.is_admin,
                COUNT(c.id) AS contacts_count
            FROM users u
            LEFT JOIN contacts c ON c.tg_id = u.tg_id
            GROUP BY u.tg_id, u.username, u.first_name, u.last_name, u.is_admin
            ORDER BY u.created_at ASC
            """;

        List<UserSummary> users = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(new UserSummary(
                    rs.getLong("tg_id"),
                    rs.getString("username"),
                    rs.getString("first_name"),
                    rs.getString("last_name"),
                    rs.getInt("is_admin") == 1,
                    rs.getInt("contacts_count")
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load users summary", e);
        }

        return users;
    }

    public List<String> getAllUniquePhones() {
        String sql = "SELECT DISTINCT phone FROM contacts ORDER BY phone ASC";
        List<String> numbers = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                numbers.add(rs.getString("phone"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to build global phone list", e);
        }
        return numbers;
    }

    private String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "No Name";
        }
        String clean = raw.trim();
        return clean.isEmpty() ? "No Name" : clean;
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private void ensureColumnExists(Connection connection, String tableName, String columnDefinition) throws SQLException {
        String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + columnDefinition;
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!message.contains("duplicate column name")) {
                throw e;
            }
        }
    }
}
