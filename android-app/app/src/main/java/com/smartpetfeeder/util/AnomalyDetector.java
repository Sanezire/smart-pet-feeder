package com.smartpetfeeder.util;

import android.util.Log;

import com.google.firebase.database.DatabaseReference;
import com.smartpetfeeder.model.AnomalyAlert;
import com.smartpetfeeder.model.FeedingRecord;
import com.smartpetfeeder.model.FeedingSchedule;
import com.smartpetfeeder.model.SummaryStats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnomalyDetector {

    private static final String TAG = "AnomalyDetector";

    private static final long   MISSED_WINDOW_MS      = 30L * 60 * 1000;
    private static final double PORTION_LOW_THRESHOLD = 0.80;
    private static final double PORTION_HIGH_THRESHOLD= 1.20;
    private static final double PORTION_SEVERE_LOW    = 0.50;
    private static final double PORTION_SEVERE_HIGH   = 1.50;

    private int anomalyCounter = 0;

    public List<AnomalyAlert> detectAnomalies(List<FeedingRecord> records,
                                               List<FeedingSchedule> schedules,
                                               DatabaseReference recordsRef) {
        List<AnomalyAlert> alerts = new ArrayList<>();
        if (records == null || records.isEmpty()) return alerts;

        List<FeedingRecord> sorted = new ArrayList<>(records);
        Collections.sort(sorted, (a, b) ->
                Long.compare(b.getScheduledTime(), a.getScheduledTime()));

        for (FeedingRecord record : sorted) {
            boolean wasAnomaly = false;

            if ("Missed".equals(record.getStatus())) {
                alerts.add(createMissedAlert(record));
                wasAnomaly = true;
            } else if (record.hasPortionDeviation()) {
                alerts.add(createPortionAlert(record));
                wasAnomaly = true;
            }

            if (wasAnomaly && !record.isAnomalyFlag() && recordsRef != null
                    && record.getFeedingId() != null) {
                Map<String, Object> update = new HashMap<>();
                update.put("anomalyFlag", true);
                update.put("status", "Anomaly");
                recordsRef.child(record.getFeedingId())
                          .updateChildren(update)
                          .addOnFailureListener(e ->
                                  Log.e(TAG, "write-back failed for " + record.getFeedingId(), e));
            }
        }

        AnomalyAlert freqAlert = checkFrequency(sorted);
        if (freqAlert != null) alerts.add(freqAlert);

        if (schedules != null && !schedules.isEmpty()) {
            List<AnomalyAlert> scheduleAlerts = checkScheduleCompliance(sorted, schedules);
            alerts.addAll(scheduleAlerts);
        }

        return alerts;
    }

    public List<AnomalyAlert> detectAnomalies(List<FeedingRecord> records) {
        return detectAnomalies(records, null, null);
    }

    public SummaryStats.SystemStatus determineSystemStatus(List<AnomalyAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) return SummaryStats.SystemStatus.NORMAL;

        int high = 0, medium = 0;
        for (AnomalyAlert a : alerts) {
            if (a.isResolved()) continue;
            switch (a.getSeverity()) {
                case HIGH:   high++;   break;
                case MEDIUM: medium++; break;
            }
        }

        if (high > 0)   return SummaryStats.SystemStatus.ANOMALY;
        if (medium > 0) return SummaryStats.SystemStatus.WARNING;
        return SummaryStats.SystemStatus.NORMAL;
    }

    private AnomalyAlert createMissedAlert(FeedingRecord record) {
        anomalyCounter++;
        String timeDisplay = record.getScheduledTimeDisplay() != null
                ? record.getScheduledTimeDisplay() : "--:--";
        return new AnomalyAlert(
                "ANOMALY_" + anomalyCounter,
                AnomalyAlert.AnomalyType.MISSED_FEEDING,
                AnomalyAlert.Severity.HIGH,
                "Missed Feeding Detected",
                String.format("Scheduled feeding at %s was missed. No record found within ±30 minutes.", timeDisplay),
                timeDisplay
        );
    }

    private AnomalyAlert createPortionAlert(FeedingRecord record) {
        anomalyCounter++;
        double ratio = record.getComparisonTarget() > 0
                ? record.getComparisonWeight() / (double) record.getComparisonTarget()
                : 1.0;
        boolean isLow = ratio < PORTION_LOW_THRESHOLD;

        AnomalyAlert.Severity severity = (ratio < PORTION_SEVERE_LOW || ratio > PORTION_SEVERE_HIGH)
                ? AnomalyAlert.Severity.HIGH
                : AnomalyAlert.Severity.MEDIUM;

        double pct = Math.abs(record.getDeviationPercentage());
        String dir = isLow ? "below" : "above";
        String time = record.getActualTimeDisplay() != null ? record.getActualTimeDisplay() : "--:--";

        String detail = record.isWeightRecorded()
                ? String.format("Feeding at %s: Recommended %.1fg, Measured %.1fg (%.1f%% %s target).",
                        time, record.getComparisonTarget(), record.getComparisonWeight(), pct, dir)
                : String.format("Feeding at %s: Target %.0fg, Actual %.0fg (%.1f%% %s target).",
                        time, record.getScheduledPortion(), record.getDispensedWeight(), pct, dir);

        return new AnomalyAlert(
                "ANOMALY_" + anomalyCounter,
                isLow ? AnomalyAlert.AnomalyType.PORTION_DEVIATION_LOW
                       : AnomalyAlert.AnomalyType.PORTION_DEVIATION_HIGH,
                severity,
                isLow ? "Low Portion Warning" : "High Portion Warning",
                detail,
                time
        );
    }

    private AnomalyAlert checkFrequency(List<FeedingRecord> sortedNewestFirst) {
        if (sortedNewestFirst.size() < 2) return null;

        int consecutive = 0, maxConsecutive = 0;
        for (FeedingRecord r : sortedNewestFirst) {
            if ("Missed".equals(r.getStatus())) {
                if (++consecutive > maxConsecutive) maxConsecutive = consecutive;
            } else {
                consecutive = 0;
            }
        }
        if (maxConsecutive >= 2) {
            anomalyCounter++;
            return new AnomalyAlert(
                    "ANOMALY_" + anomalyCounter,
                    AnomalyAlert.AnomalyType.FREQUENCY_IRREGULARITY,
                    AnomalyAlert.Severity.HIGH,
                    "Irregular Feeding Frequency",
                    String.format("%d consecutive missed feedings detected. Check schedule and device connectivity.", maxConsecutive),
                    sortedNewestFirst.get(0).getActualTimeDisplay() != null
                            ? sortedNewestFirst.get(0).getActualTimeDisplay() : "--:--"
            );
        }

        int recentAnomalies = 0;
        int check = Math.min(5, sortedNewestFirst.size());
        for (int i = 0; i < check; i++) {
            if (sortedNewestFirst.get(i).hasPortionDeviation()) recentAnomalies++;
        }
        if (recentAnomalies >= 3) {
            anomalyCounter++;
            return new AnomalyAlert(
                    "ANOMALY_" + anomalyCounter,
                    AnomalyAlert.AnomalyType.FREQUENCY_IRREGULARITY,
                    AnomalyAlert.Severity.MEDIUM,
                    "Recurring Portion Deviations",
                    String.format("%d portion deviations in last %d feedings. Please calibrate the dispenser.", recentAnomalies, check),
                    sortedNewestFirst.get(0).getActualTimeDisplay() != null
                            ? sortedNewestFirst.get(0).getActualTimeDisplay() : "--:--"
            );
        }

        return null;
    }

    private List<AnomalyAlert> checkScheduleCompliance(List<FeedingRecord> records,
                                                        List<FeedingSchedule> schedules) {
        List<AnomalyAlert> alerts = new ArrayList<>();

        for (FeedingRecord record : records) {
            if ("Missed".equals(record.getStatus())) continue;
            if (record.getActualTime() <= 0) continue;

            long minDiff = Long.MAX_VALUE;
            for (FeedingSchedule schedule : schedules) {
                if (!schedule.isEnabled()) continue;
                long slotMs = schedule.getScheduledEpochForToday();
                long diff = Math.abs(record.getActualTime() - slotMs);
                if (diff < minDiff) minDiff = diff;
            }

            if (minDiff > MISSED_WINDOW_MS) {
                anomalyCounter++;
                String time = record.getActualTimeDisplay() != null
                        ? record.getActualTimeDisplay() : "--:--";
                long minutesOff = minDiff / 60_000;
                alerts.add(new AnomalyAlert(
                        "ANOMALY_" + anomalyCounter,
                        AnomalyAlert.AnomalyType.FREQUENCY_IRREGULARITY,
                        AnomalyAlert.Severity.LOW,
                        "Off-Schedule Feeding",
                        String.format("Feeding at %s occurred ~%d min outside scheduled window.", time, minutesOff),
                        time
                ));
            }
        }

        return alerts;
    }
}
