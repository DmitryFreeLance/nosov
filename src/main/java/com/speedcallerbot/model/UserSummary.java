package com.speedcallerbot.model;

public class UserSummary {
    private final long tgId;
    private final String username;
    private final String firstName;
    private final String lastName;
    private final boolean admin;
    private final int contactsCount;

    public UserSummary(long tgId, String username, String firstName, String lastName, boolean admin, int contactsCount) {
        this.tgId = tgId;
        this.username = username;
        this.firstName = firstName;
        this.lastName = lastName;
        this.admin = admin;
        this.contactsCount = contactsCount;
    }

    public long getTgId() {
        return tgId;
    }

    public String getUsername() {
        return username;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public boolean isAdmin() {
        return admin;
    }

    public int getContactsCount() {
        return contactsCount;
    }
}
