package com.smartpetfeeder.model;

public class SummaryStats {

    public enum SystemStatus {
        NORMAL,
        WARNING,
        ANOMALY
    }

    private int todayFeedingCount;
    private String lastFeedingTime;
    private double totalFoodDispensedGrams;
    private SystemStatus systemStatus;
    private int completedFeedings;
    private int missedFeedings;
    private int anomalyFeedings;

    public SummaryStats() {
        this.todayFeedingCount = 0;
        this.lastFeedingTime = "--:--";
        this.totalFoodDispensedGrams = 0.0;
        this.systemStatus = SystemStatus.NORMAL;
        this.completedFeedings = 0;
        this.missedFeedings = 0;
        this.anomalyFeedings = 0;
    }

    public int getTodayFeedingCount() {
        return todayFeedingCount;
    }

    public void setTodayFeedingCount(int todayFeedingCount) {
        this.todayFeedingCount = todayFeedingCount;
    }

    public String getLastFeedingTime() {
        return lastFeedingTime;
    }

    public void setLastFeedingTime(String lastFeedingTime) {
        this.lastFeedingTime = lastFeedingTime;
    }

    public double getTotalFoodDispensedGrams() {
        return totalFoodDispensedGrams;
    }

    public void setTotalFoodDispensedGrams(double totalFoodDispensedGrams) {
        this.totalFoodDispensedGrams = totalFoodDispensedGrams;
    }

    public SystemStatus getSystemStatus() {
        return systemStatus;
    }

    public void setSystemStatus(SystemStatus systemStatus) {
        this.systemStatus = systemStatus;
    }

    public int getCompletedFeedings() {
        return completedFeedings;
    }

    public void setCompletedFeedings(int completedFeedings) {
        this.completedFeedings = completedFeedings;
    }

    public int getMissedFeedings() {
        return missedFeedings;
    }

    public void setMissedFeedings(int missedFeedings) {
        this.missedFeedings = missedFeedings;
    }

    public int getAnomalyFeedings() {
        return anomalyFeedings;
    }

    public void setAnomalyFeedings(int anomalyFeedings) {
        this.anomalyFeedings = anomalyFeedings;
    }

    public String getSystemStatusLabel() {
        switch (systemStatus) {
            case NORMAL:
                return "Normal";
            case WARNING:
                return "Warning";
            case ANOMALY:
                return "Anomaly";
            default:
                return "Unknown";
        }
    }

    public int getStatusColor() {
        switch (systemStatus) {
            case NORMAL:
                return 0xFF66BB6A;
            case WARNING:
                return 0xFFFFA726;
            case ANOMALY:
                return 0xFFEF5350;
            default:
                return 0xFF9E9E9E;
        }
    }

    public int getStatusBackgroundColor() {
        switch (systemStatus) {
            case NORMAL:
                return 0xFFE8F5E9;
            case WARNING:
                return 0xFFFFF3E0;
            case ANOMALY:
                return 0xFFFFEBEE;
            default:
                return 0xFFF5F5F5;
        }
    }
}
