package com.speedcallerbot.model;

public class ImportReport {
    private final int added;
    private final int duplicates;
    private final int invalid;

    public ImportReport(int added, int duplicates, int invalid) {
        this.added = added;
        this.duplicates = duplicates;
        this.invalid = invalid;
    }

    public int getAdded() {
        return added;
    }

    public int getDuplicates() {
        return duplicates;
    }

    public int getInvalid() {
        return invalid;
    }
}
