package com.speedcallerbot.model;

public class UserState {
    private final long userId;
    private int currentIndex;
    private Integer pendingAdContactIndex;
    private Integer lastBotMessageId;
    private Integer lastCallContactMessageId;
    private UserMode mode;

    public UserState(long userId,
                     int currentIndex,
                     Integer pendingAdContactIndex,
                     Integer lastBotMessageId,
                     Integer lastCallContactMessageId,
                     UserMode mode) {
        this.userId = userId;
        this.currentIndex = currentIndex;
        this.pendingAdContactIndex = pendingAdContactIndex;
        this.lastBotMessageId = lastBotMessageId;
        this.lastCallContactMessageId = lastCallContactMessageId;
        this.mode = mode;
    }

    public long getUserId() {
        return userId;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    public Integer getPendingAdContactIndex() {
        return pendingAdContactIndex;
    }

    public void setPendingAdContactIndex(Integer pendingAdContactIndex) {
        this.pendingAdContactIndex = pendingAdContactIndex;
    }

    public Integer getLastBotMessageId() {
        return lastBotMessageId;
    }

    public void setLastBotMessageId(Integer lastBotMessageId) {
        this.lastBotMessageId = lastBotMessageId;
    }

    public Integer getLastCallContactMessageId() {
        return lastCallContactMessageId;
    }

    public void setLastCallContactMessageId(Integer lastCallContactMessageId) {
        this.lastCallContactMessageId = lastCallContactMessageId;
    }

    public UserMode getMode() {
        return mode;
    }

    public void setMode(UserMode mode) {
        this.mode = mode;
    }
}
