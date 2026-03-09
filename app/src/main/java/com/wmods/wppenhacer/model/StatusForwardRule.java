package com.wmods.wppenhacer.model;

public class StatusForwardRule {
    public static final String TYPE_CONTAINS = "contains";
    public static final String TYPE_EQUALS = "equals";

    public String type;
    public String text;
    public boolean applyText = true;
    public boolean applyMedia = true;
    public boolean applyVoice = false;

    public StatusForwardRule(String type, String text) {
        this.type = type;
        this.text = text;
    }

    public StatusForwardRule(String type, String text, boolean applyText, boolean applyMedia, boolean applyVoice) {
        this.type = type;
        this.text = text;
        this.applyText = applyText;
        this.applyMedia = applyMedia;
        this.applyVoice = applyVoice;
    }
}
