package com.speedcallerbot.service;

public final class PhoneUtils {
    private PhoneUtils() {
    }

    public static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String digitsOnly = raw.replaceAll("\\D", "");
        if (digitsOnly.length() < 10 || digitsOnly.length() > 15) {
            return null;
        }

        return "+" + digitsOnly;
    }
}
