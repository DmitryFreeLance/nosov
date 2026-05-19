package com.speedcallerbot.model;

public class ParsedContact {
    private final String displayName;
    private final String phone;

    public ParsedContact(String displayName, String phone) {
        this.displayName = displayName;
        this.phone = phone;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPhone() {
        return phone;
    }
}
