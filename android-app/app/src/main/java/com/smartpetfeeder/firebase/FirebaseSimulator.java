package com.smartpetfeeder.firebase;

import static com.smartpetfeeder.model.FeedingRecord.Status.ANOMALY;
import static com.smartpetfeeder.model.FeedingRecord.Status.COMPLETED;
import static com.smartpetfeeder.model.FeedingRecord.Status.MISSED;

import android.os.Handler;
import android.os.Looper;

import com.smartpetfeeder.model.AnomalyAlert;
import com.smartpetfeeder.model.DashboardData;
import com.smartpetfeeder.model.FeedingRecord;
import com.smartpetfeeder.model.SummaryStats;
import com.smartpetfeeder.util.AnomalyDetector;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class FirebaseSimulator {

    public interface DataUpdateListener {
        void onDataUpdated(DashboardData dashboardData);
        void onError(String error);
    }

    private static final long UPDATE_INTERVAL_MS = 10000;
    private static final String[] TIME_SLOTS = {
            "07:00", "12:00", "18:00", "21:00"
    };

    private Handler handler;
    private Random random;
    private DataUpdateListener listener;
    private AnomalyDetector anomalyDetector;
    private boolean isRunning = false;
    private int updateCount = 0;

    public FirebaseSimulator() {
        this.handler = new Handler(Looper.getMainLooper());
        this.random = new Random();
        this.anomalyDetector = new AnomalyDetector();
    }

    public void setListener(DataUpdateListener listener) {
        this.listener = listener;
    }

    public void startSimulation() {
        if (isRunning) return;
        isRunning = true;

        pushDataUpdate();

        scheduleNextUpdate();
    }

    public void stopSimulation() {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void scheduleNextUpdate() {
        if (!isRunning) return;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    pushDataUpdate();
                    scheduleNextUpdate();
                }
            }
        }, UPDATE_INTERVAL_MS);
    }

    private void pushDataUpdate() {
        try {
            DashboardData data = generateMockData();

            if (random.nextDouble() < 0.05) {
                if (listener != null) {
                    listener.onError("Connection timeout. Retrying...");
                }
                return;
            }

            if (listener != null) {
                listener.onDataUpdated(data);
            }

            updateCount++;
        } catch (Exception e) {
            if (listener != null) {
                listener.onError("Data processing error: " + e.getMessage());
            }
        }
    }

    private DashboardData generateMockData() {
        DashboardData data = new DashboardData();
        data.setPetName("Buddy");
        data.setLastUpdated(System.currentTimeMillis());

        List<FeedingRecord> feedingRecords = generateFeedingHistory();
        data.setFeedingHistory(feedingRecords);

        List<AnomalyAlert> alerts = anomalyDetector.detectAnomalies(feedingRecords);
        data.setAnomalyAlerts(alerts);

        SummaryStats stats = calculateSummaryStats(feedingRecords, alerts);
        data.setSummaryStats(stats);

        data.setFoodTrendData(generateFoodTrendData(feedingRecords));
        data.setFrequencyData(generateFrequencyData(feedingRecords));

        return data;
    }

    private List<FeedingRecord> generateFeedingHistory() {
        List<FeedingRecord> records = new ArrayList<>();
        Calendar cal = Calendar.getInstance();

        int daysBack = (updateCount % 3 == 0) ? 7 : 1;

        for (int day = 0; day < daysBack; day++) {
            cal.setTimeInMillis(System.currentTimeMillis());
            cal.add(Calendar.DAY_OF_YEAR, -day);
            String dateStr = String.format(Locale.US, "%02d/%02d",
                    cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));

            for (int slot = 0; slot < TIME_SLOTS.length; slot++) {
                boolean isMissed = random.nextDouble() < 0.10;

                boolean hasDeviation = !isMissed && random.nextDouble() < 0.15;

                String scheduledTime = TIME_SLOTS[slot];
                String actualTime = isMissed ? "--:--" : addMinutes(scheduledTime, random.nextInt(20) - 5);

                double targetWeight = 50.0 + random.nextInt(100);
                double dispensedWeight;

                if (isMissed) {
                    dispensedWeight = 0.0;
                } else if (hasDeviation) {
                    if (random.nextBoolean()) {
                        dispensedWeight = targetWeight * (0.4 + random.nextDouble() * 0.3);
                    } else {
                        dispensedWeight = targetWeight * (1.3 + random.nextDouble() * 0.4);
                    }
                } else {
                    dispensedWeight = targetWeight * (0.92 + random.nextDouble() * 0.16);
                }

                FeedingRecord.Status status;
                if (isMissed) {
                    status = MISSED;
                } else if (hasDeviation) {
                    status = ANOMALY;
                } else {
                    status = COMPLETED;
                }

                long scheduledTimestamp = cal.getTimeInMillis() + (slot * 3600000L);
                long actualTimestamp = isMissed ? 0L
                        : scheduledTimestamp + (long)(random.nextInt(20) - 5) * 60_000L;

                FeedingRecord record = new FeedingRecord(
                        "REC_" + day + "_" + slot,
                        scheduledTimestamp,
                        actualTimestamp,
                        (float) targetWeight,
                        (float)(Math.round(dispensedWeight * 10.0) / 10.0),
                        (float)(Math.round((dispensedWeight * 0.85) * 10.0) / 10.0),
                        (float)(Math.max(0.0, targetWeight - dispensedWeight)),
                        status,
                        hasDeviation || isMissed,
                        dateStr
                );

                record.setScheduledTimeDisplay(scheduledTime);
                record.setActualTimeDisplay(actualTime);

                records.add(record);
            }
        }

        return records;
    }

    private String addMinutes(String timeStr, int minutes) {
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);

            minute += minutes;
            while (minute >= 60) {
                minute -= 60;
                hour++;
            }
            while (minute < 0) {
                minute += 60;
                hour--;
            }

            return String.format(Locale.US, "%02d:%02d", hour, minute);
        } catch (Exception e) {
            return timeStr;
        }
    }

    private SummaryStats calculateSummaryStats(List<FeedingRecord> records, List<AnomalyAlert> alerts) {
        SummaryStats stats = new SummaryStats();

        int completed = 0;
        int missed = 0;
        int anomaly = 0;
        double totalDispensed = 0.0;
        String lastFeedingTime = "--:--";

        Calendar today = Calendar.getInstance();

        for (FeedingRecord record : records) {
            Calendar recordCal = Calendar.getInstance();
            recordCal.setTimeInMillis(record.getScheduledTime());

            boolean isToday = recordCal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                    && recordCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);

            if (isToday) {
                switch (record.getStatusEnum()) {
                    case COMPLETED:
                        completed++;
                        totalDispensed += record.getDispensedWeight();
                        break;
                    case MISSED:
                        missed++;
                        break;
                    case ANOMALY:
                        anomaly++;
                        totalDispensed += record.getDispensedWeight();
                        break;
                }
            }

            if (record.getStatusEnum() != MISSED &&
                    !("--:--".equals(record.getActualTimeDisplay()))) {
                lastFeedingTime = record.getActualTimeDisplay();
            }
        }

        stats.setTodayFeedingCount(completed + missed + anomaly);
        stats.setCompletedFeedings(completed);
        stats.setMissedFeedings(missed);
        stats.setAnomalyFeedings(anomaly);
        stats.setTotalFoodDispensedGrams(Math.round(totalDispensed * 10.0) / 10.0);
        stats.setLastFeedingTime(lastFeedingTime);

        stats.setSystemStatus(anomalyDetector.determineSystemStatus(alerts));

        return stats;
    }

    private List<DashboardData.FoodTrendData> generateFoodTrendData(List<FeedingRecord> records) {
        List<DashboardData.FoodTrendData> trendData = new ArrayList<>();

        int count = Math.min(records.size(), 7);
        List<FeedingRecord> recentRecords = records.subList(0, count);

        for (int i = recentRecords.size() - 1; i >= 0; i--) {
            FeedingRecord record = recentRecords.get(i);
            String label = record.getScheduledTimeDisplay();

            trendData.add(new DashboardData.FoodTrendData(
                    label,
                    record.getScheduledPortion(),
                    record.getDispensedWeight()
            ));
        }

        return trendData;
    }

    private List<DashboardData.FeedingFrequencyData> generateFrequencyData(List<FeedingRecord> records) {
        List<DashboardData.FeedingFrequencyData> frequencyData = new ArrayList<>();

        java.util.Map<String, int[]> dailyCounts = new java.util.LinkedHashMap<>();

        for (FeedingRecord record : records) {
            String date = record.getDate();
            if (!dailyCounts.containsKey(date)) {
                dailyCounts.put(date, new int[]{0, 0});
            }
            dailyCounts.get(date)[0]++;
            if (record.getStatusEnum() == COMPLETED) {
                dailyCounts.get(date)[1]++;
            }
        }

        int count = 0;
        for (java.util.Map.Entry<String, int[]> entry : dailyCounts.entrySet()) {
            if (count >= 7) break;
            frequencyData.add(new DashboardData.FeedingFrequencyData(
                    entry.getKey(),
                    entry.getValue()[0],
                    entry.getValue()[1]
            ));
            count++;
        }

        return frequencyData;
    }
}
