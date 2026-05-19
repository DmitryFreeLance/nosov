package com.speedcallerbot.model;

import java.util.List;

public class ParsedBatch {
    private final List<ParsedContact> contacts;
    private final int invalidCount;

    public ParsedBatch(List<ParsedContact> contacts, int invalidCount) {
        this.contacts = contacts;
        this.invalidCount = invalidCount;
    }

    public List<ParsedContact> getContacts() {
        return contacts;
    }

    public int getInvalidCount() {
        return invalidCount;
    }
}
