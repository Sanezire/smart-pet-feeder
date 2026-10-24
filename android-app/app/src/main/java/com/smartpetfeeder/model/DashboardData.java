package com.smartpetfeeder.model;

import java.util.ArrayList;
import com.smartpetfeeder.model.FeedingSchedule;
import java.util.List;

public class DashboardData {

    private SummaryStats summaryStats;
    private List<FeedingRecord> feedingHistory;
    private List<AnomalyAlert> anomalyAlerts;
    private List<FoodTrendData> foodTrendData;
    private List<FeedingFrequencyData> frequencyData;
    private List<FeedingSchedule> schedules;
    private String petName;
    private long lastUpdated;
    private boolean weightKnown;
    private float currentWeight;
    private long weightUpdatedAt;

    public DashboardData() {
        this.summaryStats = new SummaryStats();
        this.feedingHistory = new ArrayList<>();
        this.anomalyAlerts = new ArrayList<>();
        this.foodTrendData = new ArrayList<>();
        this.frequencyData = new ArrayList<>();
        this.schedules = new ArrayList<>();
        this.petName = "Buddy";
        this.lastUpdated = System.currentTimeMillis();
    }

    public List<FeedingSchedule> getSchedules() { return schedules; }
    public void setSchedules(List<FeedingSchedule> schedules) {
        this.schedules = (schedules != null) ? schedules : new ArrayList<>();
    }

    public SummaryStats getSummaryStats() {
        return summaryStats;
    }

    public void setSummaryStats(SummaryStats summaryStats) {
        this.summaryStats = summaryStats;
    }

    public List<FeedingRecord> getFeedingHistory() {
        return feedingHistory;
    }

    public void setFeedingHistory(List<FeedingRecord> feedingHistory) {
        this.feedingHistory = feedingHistory;
    }

    public List<AnomalyAlert> getAnomalyAlerts() {
        return anomalyAlerts;
    }

    public void setAnomalyAlerts(List<AnomalyAlert> anomalyAlerts) {
        this.anomalyAlerts = anomalyAlerts;
    }

    public List<FoodTrendData> getFoodTrendData() {
        return foodTrendData;
    }

    public void setFoodTrendData(List<FoodTrendData> foodTrendData) {
        this.foodTrendData = foodTrendData;
    }

    public List<FeedingFrequencyData> getFrequencyData() {
        return frequencyData;
    }

    public void setFrequencyData(List<FeedingFrequencyData> frequencyData) {
        this.frequencyData = frequencyData;
    }

    public String getPetName() {
        return petName;
    }

    public void setPetName(String petName) {
        this.petName = petName;
    }

    public boolean isWeightKnown() { return weightKnown; }
    public void setWeightKnown(boolean weightKnown) { this.weightKnown = weightKnown; }
    public float getCurrentWeight() { return currentWeight; }
    public void setCurrentWeight(float currentWeight) { this.currentWeight = currentWeight; }
    public long getWeightUpdatedAt() { return weightUpdatedAt; }
    public void setWeightUpdatedAt(long weightUpdatedAt) { this.weightUpdatedAt = weightUpdatedAt; }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public static class FoodTrendData {
        private String label;
        private double targetWeight;
        private double actualWeight;

        public FoodTrendData(String label, double targetWeight, double actualWeight) {
            this.label = label;
            this.targetWeight = targetWeight;
            this.actualWeight = actualWeight;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public double getTargetWeight() {
            return targetWeight;
        }

        public void setTargetWeight(double targetWeight) {
            this.targetWeight = targetWeight;
        }

        public double getActualWeight() {
            return actualWeight;
        }

        public void setActualWeight(double actualWeight) {
            this.actualWeight = actualWeight;
        }
    }

    public static class FeedingFrequencyData {
        private String label;
        private int scheduledCount;
        private int completedCount;

        public FeedingFrequencyData(String label, int scheduledCount, int completedCount) {
            this.label = label;
            this.scheduledCount = scheduledCount;
            this.completedCount = completedCount;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public int getScheduledCount() {
            return scheduledCount;
        }

        public void setScheduledCount(int scheduledCount) {
            this.scheduledCount = scheduledCount;
        }

        public int getCompletedCount() {
            return completedCount;
        }

        public void setCompletedCount(int completedCount) {
            this.completedCount = completedCount;
        }
    }
}
