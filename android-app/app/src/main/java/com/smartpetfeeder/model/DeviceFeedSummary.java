package com.smartpetfeeder.model;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class DeviceFeedSummary {

    private final String feedingId;
    private final float before;
    private final float afterDispense;
    private final float dispensed;
    private final boolean bowlFull;
    private final boolean finished;
    private final float consumed;
    private final float remaining;
    private final boolean byTray;

    private DeviceFeedSummary(String feedingId, float before, float afterDispense, float dispensed,
                              boolean bowlFull, boolean finished, float consumed, float remaining,
                              boolean byTray) {
        this.feedingId = feedingId;
        this.before = before;
        this.afterDispense = afterDispense;
        this.dispensed = dispensed;
        this.bowlFull = bowlFull;
        this.finished = finished;
        this.consumed = consumed;
        this.remaining = remaining;
        this.byTray = byTray;
    }

    public static DeviceFeedSummary fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("feedingId", "");
        if (id.isEmpty()) return null;
        return new DeviceFeedSummary(id,
                (float) o.optDouble("before", 0.0),
                (float) o.optDouble("afterDispense", 0.0),
                (float) o.optDouble("dispensed", 0.0),
                o.optBoolean("bowlFull", false),
                o.optBoolean("finished", false),
                (float) o.optDouble("consumed", 0.0),
                (float) o.optDouble("remaining", 0.0),
                o.optBoolean("byTray", false));
    }

    public String getFeedingId() { return feedingId; }

    public boolean isFinished() { return finished; }

    /** Fills the record's missing figures from the feeder; returns the fields it filled, or null. */
    public Map<String, Object> applyTo(FeedingRecord r) {
        if (r == null || !feedingId.equals(r.getFeedingId())) return null;
        Map<String, Object> filled = null;

        if (!r.isDispensedKnown() && (afterDispense > 0f || bowlFull)) {
            r.setWeightBeforeFeed(before);
            r.setWeightAfterDispense(afterDispense);
            r.setDispensedWeight(dispensed);
            r.setBowlAlreadyFull(bowlFull);
            filled = new HashMap<>();
            filled.put("weightBeforeFeed", (double) before);
            filled.put("weightAfterDispense", (double) afterDispense);
            filled.put("dispensedWeight", (double) dispensed);
            filled.put("bowlAlreadyFull", bowlFull);
        }

        if (finished && r.isDispensedKnown() && !r.isConsumptionRecorded()) {
            long now = System.currentTimeMillis();
            r.setConsumedWeight(consumed);
            r.setRemainingWeight(remaining);
            r.setWeightAfterEating(remaining);
            r.setConsumptionRecorded(true);
            r.setMeasuredAfterTrayClose(byTray);
            if (byTray) r.setWeightAfterTrayClose(remaining);
            if (r.getConsumptionTime() <= 0L) r.setConsumptionTime(now);
            if (filled == null) filled = new HashMap<>();
            filled.put("consumedWeight", (double) consumed);
            filled.put("remainingWeight", (double) remaining);
            filled.put("weightAfterEating", (double) remaining);
            filled.put("consumptionRecorded", true);
            filled.put("measuredAfterTrayClose", byTray);
            if (byTray) filled.put("weightAfterTrayClose", (double) remaining);
            filled.put("consumptionTime", r.getConsumptionTime());
        }
        return filled;
    }
}
