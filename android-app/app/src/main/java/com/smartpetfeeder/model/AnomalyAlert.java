package com.smartpetfeeder.model;

public class AnomalyAlert {

    public enum AnomalyType {
        MISSED_FEEDING,
        PORTION_DEVIATION_LOW,
        PORTION_DEVIATION_HIGH,
        FREQUENCY_IRREGULARITY
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }

    private String id;
    private AnomalyType type;
    private Severity severity;
    private String message;
    private String detail;
    private String timestamp;
    private boolean isResolved;

    public AnomalyAlert() {}

    public AnomalyAlert(String id, AnomalyType type, Severity severity,
                        String message, String detail, String timestamp) {
        this.id = id;
        this.type = type;
        this.severity = severity;
        this.message = message;
        this.detail = detail;
        this.timestamp = timestamp;
        this.isResolved = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public AnomalyType getType() { return type; }
    public void setType(AnomalyType type) { this.type = type; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public boolean isResolved() { return isResolved; }
    public void setResolved(boolean resolved) { isResolved = resolved; }

    public String getTypeLabel() {
        switch (type) {
            case MISSED_FEEDING:        return "Missed Feeding";
            case PORTION_DEVIATION_LOW: return "Low Portion";
            case PORTION_DEVIATION_HIGH:return "High Portion";
            case FREQUENCY_IRREGULARITY:return "Irregular Frequency";
            default:                    return "Unknown";
        }
    }

    public int getSeverityColor() {
        switch (severity) {
            case LOW:    return 0xFFF59E0B;
            case MEDIUM: return 0xFFF97316;
            case HIGH:   return 0xFFEF4444;
            default:     return 0xFF9CA3AF;
        }
    }

    public int getSeverityBackgroundColor() {
        switch (severity) {
            case LOW:    return 0xFFFEF3C7;
            case MEDIUM: return 0xFFFFF0E8;
            case HIGH:   return 0xFFFEE2E2;
            default:     return 0xFFF3F4F6;
        }
    }
}
