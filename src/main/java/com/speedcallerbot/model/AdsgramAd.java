package com.speedcallerbot.model;

public class AdsgramAd {
    private final String textHtml;
    private final String clickUrl;
    private final String buttonName;
    private final String rewardUrl;
    private final String rewardButtonName;
    private final String imageUrl;
    private final String sourceBlockId;
    private final int priorityScore;

    public AdsgramAd(String textHtml,
                     String clickUrl,
                     String buttonName,
                     String rewardUrl,
                     String rewardButtonName,
                     String imageUrl,
                     String sourceBlockId,
                     int priorityScore) {
        this.textHtml = textHtml;
        this.clickUrl = clickUrl;
        this.buttonName = buttonName;
        this.rewardUrl = rewardUrl;
        this.rewardButtonName = rewardButtonName;
        this.imageUrl = imageUrl;
        this.sourceBlockId = sourceBlockId;
        this.priorityScore = priorityScore;
    }

    public String getTextHtml() {
        return textHtml;
    }

    public String getClickUrl() {
        return clickUrl;
    }

    public String getButtonName() {
        return buttonName;
    }

    public String getRewardUrl() {
        return rewardUrl;
    }

    public String getRewardButtonName() {
        return rewardButtonName;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getSourceBlockId() {
        return sourceBlockId;
    }

    public int getPriorityScore() {
        return priorityScore;
    }
}
