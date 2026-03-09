package com.wmods.wppenhacer.model;

public class StatusForwardRule {
    public static final String TYPE_CONTAINS = "contains";
    public static final String TYPE_EQUALS = "equals";

    public String type;
    public String text;

    public StatusForwardRule(String type, String text) {
        this.type = type;
        this.text = text;
    }
}
