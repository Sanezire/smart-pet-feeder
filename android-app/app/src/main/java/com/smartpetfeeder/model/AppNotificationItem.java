package com.smartpetfeeder.model;

public class AppNotificationItem {
    public enum Type {
        ANOMALY,
        FEEDING,
        SYSTEM
    }

    private final String id;
    private final Type type;
    private final String title;
    private final String message;
    private final String timeText;

    public AppNotificationItem(String id, Type type, String title, String message, String timeText) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.message = message;
        this.timeText = timeText;
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getTimeText() {
        return timeText;
    }
}
