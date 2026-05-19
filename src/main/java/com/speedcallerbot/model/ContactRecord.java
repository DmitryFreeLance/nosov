package com.speedcallerbot.model;

public class ContactRecord {
    private final long id;
    private final long userId;
    private final String displayName;
    private final String phone;

    public ContactRecord(long id, long userId, String displayName, String phone) {
        this.id = id;
        this.userId = userId;
        this.displayName = displayName;
        this.phone = phone;
    }

    public long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPhone() {
        return phone;
    }
}
