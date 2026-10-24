package com.smartpetfeeder.model;

public class LiveFeedingStats {
    private final boolean active;
    private final String feedingId;
    private final float target;
    private final float consumed;
    private final float remaining;
    private final boolean bowlRemoved;

    public LiveFeedingStats(boolean active, String feedingId, float target,
                            float consumed, float remaining, boolean bowlRemoved) {
        this.active = active;
        this.feedingId = feedingId;
        this.target = target;
        this.consumed = consumed;
        this.remaining = remaining;
        this.bowlRemoved = bowlRemoved;
    }

    public boolean isActive() { return active; }
    public String getFeedingId() { return feedingId; }
    public float getTarget() { return target; }
    public float getConsumed() { return consumed; }
    public float getRemaining() { return remaining; }
    public boolean isBowlRemoved() { return bowlRemoved; }
}
