package com.speedcallerbot.util;

public final class TextFormatter {
    private TextFormatter() {
    }

    public static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    public static String displayName(String firstName, String username) {
        if (firstName != null && !firstName.isBlank()) {
            return firstName.trim();
        }
        if (username != null && !username.isBlank()) {
            return "@" + username.trim();
        }
        return "there";
    }

    public static String fullName(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? "Client" : full;
    }
}
