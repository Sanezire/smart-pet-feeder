package com.smartpetfeeder.model;

import com.google.firebase.database.Exclude;
import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class FeedingRecord {

    public enum Status {
        COMPLETED,
        MISSED,
        ANOMALY
    }

    private String feedingId;
    private long scheduledTime;
    private long actualTime;
    private float scheduledPortion;
    private float dispensedWeight;
    private float consumedWeight;
    private float remainingWeight;
    private float measuredWeight;
    private float recommendedWeight;
    private boolean weightRecorded;
    private float weightBeforeFeed;
    private float weightAfterDispense;
    private float weightAfterEating;
    private boolean consumptionRecorded;
    private long consumptionTime;
    private long eatWindowMs;
    private boolean bowlAlreadyFull;
    private String feedType;                 // "manual" or "scheduled"
    private float targetWeight;              // target bowl weight for this feed
    private boolean measuredAfterTrayClose;  // consumed/remaining were read after the tray closed
    private float weightAfterTrayClose;
    private String status;
    private boolean anomalyFlag;
    private String date;

    @Exclude private String scheduledTimeDisplay;
    @Exclude private String actualTimeDisplay;

    public FeedingRecord() {}

    public FeedingRecord(String feedingId,
                         long scheduledTime,
                         long actualTime,
                         float scheduledPortion,
                         float dispensedWeight,
                         float consumedWeight,
                         float remainingWeight,
                         Status status,
                         boolean anomalyFlag,
                         String date) {
        this.feedingId = feedingId;
        this.scheduledTime = scheduledTime;
        this.actualTime = actualTime;
        this.scheduledPortion = scheduledPortion;
        this.dispensedWeight = dispensedWeight;
        this.consumedWeight = consumedWeight;
        this.remainingWeight = remainingWeight;
        this.status = statusToString(status);
        this.anomalyFlag = anomalyFlag;
        this.date = date;
    }

    public String getFeedingId() { return feedingId; }
    public void setFeedingId(String feedingId) { this.feedingId = feedingId; }

    public long getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(long scheduledTime) { this.scheduledTime = scheduledTime; }

    public long getActualTime() { return actualTime; }
    public void setActualTime(long actualTime) { this.actualTime = actualTime; }

    public float getScheduledPortion() { return scheduledPortion; }
    public void setScheduledPortion(float scheduledPortion) { this.scheduledPortion = scheduledPortion; }

    public float getDispensedWeight() { return dispensedWeight; }
    public void setDispensedWeight(float dispensedWeight) { this.dispensedWeight = dispensedWeight; }
    public float getConsumedWeight() { return consumedWeight; }
    public void setConsumedWeight(float consumedWeight) { this.consumedWeight = consumedWeight; }
    public float getRemainingWeight() { return remainingWeight; }
    public void setRemainingWeight(float remainingWeight) { this.remainingWeight = remainingWeight; }

    public float getMeasuredWeight() { return measuredWeight; }
    public void setMeasuredWeight(float measuredWeight) { this.measuredWeight = measuredWeight; }
    public float getRecommendedWeight() { return recommendedWeight; }
    public void setRecommendedWeight(float recommendedWeight) { this.recommendedWeight = recommendedWeight; }
    public boolean isWeightRecorded() { return weightRecorded; }
    public void setWeightRecorded(boolean weightRecorded) { this.weightRecorded = weightRecorded; }

    public float getWeightBeforeFeed() { return weightBeforeFeed; }
    public void setWeightBeforeFeed(float v) { this.weightBeforeFeed = v; }
    public float getWeightAfterDispense() { return weightAfterDispense; }
    public void setWeightAfterDispense(float v) { this.weightAfterDispense = v; }
    public float getWeightAfterEating() { return weightAfterEating; }
    public void setWeightAfterEating(float v) { this.weightAfterEating = v; }
    public boolean isConsumptionRecorded() { return consumptionRecorded; }
    public void setConsumptionRecorded(boolean v) { this.consumptionRecorded = v; }
    public long getConsumptionTime() { return consumptionTime; }
    public void setConsumptionTime(long v) { this.consumptionTime = v; }
    public String getFeedType() { return feedType; }
    public void setFeedType(String v) { this.feedType = v; }
    public float getTargetWeight() { return targetWeight; }
    public void setTargetWeight(float v) { this.targetWeight = v; }
    public boolean isMeasuredAfterTrayClose() { return measuredAfterTrayClose; }
    public void setMeasuredAfterTrayClose(boolean v) { this.measuredAfterTrayClose = v; }
    public float getWeightAfterTrayClose() { return weightAfterTrayClose; }
    public void setWeightAfterTrayClose(float v) { this.weightAfterTrayClose = v; }

    @Exclude
    public boolean isManualFeed() {
        if (feedType != null) return "manual".equals(feedType);
        return feedingId != null && feedingId.startsWith("manual_");
    }

    /** Target weight for this feed, for both manual and scheduled records. */
    @Exclude
    public float getDisplayTarget() {
        return targetWeight > 0f ? targetWeight : scheduledPortion;
    }

    /** True once the feeder has reported what it actually dispensed (not the app's placeholder). */
    @Exclude
    public boolean isDispensedKnown() {
        return weightAfterDispense > 0f || bowlAlreadyFull || weightRecorded;
    }

    public boolean isBowlAlreadyFull() { return bowlAlreadyFull; }
    public void setBowlAlreadyFull(boolean v) { this.bowlAlreadyFull = v; }
    public long getEatWindowMs() { return eatWindowMs; }
    public void setEatWindowMs(long v) { this.eatWindowMs = v; }

    @Exclude
    public float getDisplayConsumedWeight() {
        boolean untracked = !consumptionRecorded && weightAfterDispense <= 0f;
        if (untracked && feedingId != null && feedingId.startsWith("manual_")) return 0f;
        float c = consumedWeight < 0f ? 0f : consumedWeight;
        if (weightAfterDispense > 0f && c > weightAfterDispense) c = weightAfterDispense;
        return c;
    }

    @Exclude
    public boolean isConsumptionPending() {
        return weightAfterDispense > 0f && !consumptionRecorded
                && getStatusEnum() != Status.MISSED;
    }

    @Exclude
    public boolean isInProgress(long nowMs) {
        if (getStatusEnum() != Status.COMPLETED || !isConsumptionPending()) return false;
        if (actualTime <= 0L) return true;
        long windowMs = eatWindowMs > 0 ? eatWindowMs : 30L * 60_000L;
        return nowMs - actualTime <= windowMs + 5L * 60_000L;
    }

    @Exclude
    public boolean hasConsumptionTracking() {
        return consumptionRecorded || weightAfterDispense > 0f;
    }

    @Exclude
    public int getEatWindowMinutes() {
        return eatWindowMs > 0 ? (int) Math.round(eatWindowMs / 60000.0) : 30;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isAnomalyFlag() { return anomalyFlag; }
    public void setAnomalyFlag(boolean anomalyFlag) { this.anomalyFlag = anomalyFlag; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    @Exclude
    public String getScheduledTimeDisplay() { return scheduledTimeDisplay; }
    @Exclude
    public void setScheduledTimeDisplay(String s) { this.scheduledTimeDisplay = s; }

    @Exclude
    public String getActualTimeDisplay() { return actualTimeDisplay; }
    @Exclude
    public void setActualTimeDisplay(String s) { this.actualTimeDisplay = s; }

    @Exclude
    public Status getStatusEnum() {
        if (status == null) return Status.COMPLETED;
        switch (status) {
            case "Missed": return Status.MISSED;
            case "Anomaly": return Status.ANOMALY;
            default: return Status.COMPLETED;
        }
    }

    @Exclude
    public void setStatusEnum(Status s) {
        this.status = statusToString(s);
    }

    private static String statusToString(Status s) {
        switch (s) {
            case MISSED: return "Missed";
            case ANOMALY: return "Anomaly";
            default: return "Completed";
        }
    }

    @Exclude
    public float getComparisonWeight() {
        if (weightRecorded) return measuredWeight;
        // Manual feeds: the bowl weight right after dispensing is what the target is compared with.
        if (weightAfterDispense > 0f) return weightAfterDispense;
        return dispensedWeight;
    }

    @Exclude
    public float getComparisonTarget() {
        if (weightRecorded && recommendedWeight > 0) return recommendedWeight;
        return targetWeight > 0f ? targetWeight : scheduledPortion;
    }

    @Exclude
    public double getDeviationGrams() {
        return getComparisonWeight() - getComparisonTarget();
    }

    @Exclude
    public double getDeviationPercentage() {
        float target = getComparisonTarget();
        if (target == 0) return 0;
        return ((getComparisonWeight() - target) / target) * 100.0;
    }

    @Exclude
    public boolean hasPortionDeviation() {
        if (bowlAlreadyFull) return false;
        float target = getComparisonTarget();
        if (target == 0) return false;
        double ratio = getComparisonWeight() / (double) target;
        return ratio < 0.8 || ratio > 1.2;
    }

    @Override
    public String toString() {
        return "FeedingRecord{id=" + feedingId
                + ", status=" + status
                + ", scheduled=" + scheduledPortion + "g"
                + ", dispensed=" + dispensedWeight + "g"
                + "}";
    }
}
