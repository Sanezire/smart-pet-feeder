package com.smartpetfeeder.util;

import com.smartpetfeeder.model.DashboardData;
import com.smartpetfeeder.model.FeedingRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Turns feeding records into the Analytics chart data for the selected range, in device local time.
 * <ul>
 *   <li>DAY: today. Trend has one point per feed; frequency is split into 6-hour blocks.</li>
 *   <li>WEEK: the last 7 days including today, one point per day.</li>
 *   <li>MONTH: the last 30 days including today, in 5 blocks of 6 days, labelled by start date.</li>
 * </ul>
 * Every chart runs oldest to newest. A range with no feeds returns empty lists.
 */
public final class AnalyticsBuilder {

    public enum Range { DAY, WEEK, MONTH }

    private static final String[] DAY_BLOCK_LABELS = {"Night", "Morning", "Afternoon", "Evening"};
    private static final int HOURS_PER_DAY_BLOCK = 6;
    private static final int MONTH_BLOCKS = 5;
    private static final int DAYS_PER_MONTH_BLOCK = 6;

    public static final class Result {
        private final List<DashboardData.FoodTrendData> trend;
        private final List<DashboardData.FeedingFrequencyData> frequency;

        Result(List<DashboardData.FoodTrendData> trend,
               List<DashboardData.FeedingFrequencyData> frequency) {
            this.trend = trend;
            this.frequency = frequency;
        }

        public List<DashboardData.FoodTrendData> getTrend() { return trend; }
        public List<DashboardData.FeedingFrequencyData> getFrequency() { return frequency; }
    }

    /** One chart slot: feeds with startMs <= scheduledTime < endMs. */
    private static final class Bucket {
        final String label;
        final long startMs;
        final long endMs;
        double dispensed;
        double consumed;
        int scheduled;
        int completed;

        Bucket(String label, long startMs, long endMs) {
            this.label = label;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private AnalyticsBuilder() {}

    public static Result build(List<FeedingRecord> records, Range range, long nowMs) {
        List<Bucket> buckets = createBuckets(range, nowMs);
        List<FeedingRecord> inRange = recordsBetween(records,
                buckets.get(0).startMs, buckets.get(buckets.size() - 1).endMs);

        List<DashboardData.FoodTrendData> trend = new ArrayList<>();
        List<DashboardData.FeedingFrequencyData> frequency = new ArrayList<>();
        if (inRange.isEmpty()) return new Result(trend, frequency);

        for (FeedingRecord r : inRange) {
            Bucket bucket = bucketFor(buckets, r.getScheduledTime());
            bucket.dispensed += dispensedOf(r);
            bucket.consumed += r.getDisplayConsumedWeight();
            bucket.scheduled++;
            if (r.getStatusEnum() == FeedingRecord.Status.COMPLETED && !r.isInProgress(nowMs)) {
                bucket.completed++;
            }
        }

        for (Bucket b : buckets) {
            frequency.add(new DashboardData.FeedingFrequencyData(b.label, b.scheduled, b.completed));
        }
        if (range == Range.DAY) {
            for (FeedingRecord r : inRange) {
                trend.add(new DashboardData.FoodTrendData(timeLabel(r),
                        dispensedOf(r), r.getDisplayConsumedWeight()));
            }
        } else {
            for (Bucket b : buckets) {
                trend.add(new DashboardData.FoodTrendData(b.label, b.dispensed, b.consumed));
            }
        }
        return new Result(trend, frequency);
    }

    private static List<Bucket> createBuckets(Range range, long nowMs) {
        List<Bucket> buckets = new ArrayList<>();
        switch (range) {
            case WEEK:
                for (int i = 6; i >= 0; i--) {
                    long start = startOfDay(nowMs, -i);
                    buckets.add(new Bucket(dateLabel(start), start, startOfDay(nowMs, 1 - i)));
                }
                break;
            case MONTH:
                for (int block = MONTH_BLOCKS - 1; block >= 0; block--) {
                    int newestDay = -block * DAYS_PER_MONTH_BLOCK;
                    long start = startOfDay(nowMs, newestDay - DAYS_PER_MONTH_BLOCK + 1);
                    buckets.add(new Bucket(dateLabel(start), start, startOfDay(nowMs, newestDay + 1)));
                }
                break;
            default:
                for (int i = 0; i < DAY_BLOCK_LABELS.length; i++) {
                    buckets.add(new Bucket(DAY_BLOCK_LABELS[i],
                            hourOfToday(nowMs, i * HOURS_PER_DAY_BLOCK),
                            hourOfToday(nowMs, (i + 1) * HOURS_PER_DAY_BLOCK)));
                }
                break;
        }
        return buckets;
    }

    /** Records in the window, oldest first. Records with no feed time cannot be placed and are skipped. */
    private static List<FeedingRecord> recordsBetween(List<FeedingRecord> records,
                                                      long startMs, long endMs) {
        List<FeedingRecord> result = new ArrayList<>();
        if (records == null) return result;
        for (FeedingRecord r : records) {
            long t = r.getScheduledTime();
            if (t > 0L && t >= startMs && t < endMs) result.add(r);
        }
        Collections.sort(result, (a, b) -> Long.compare(a.getScheduledTime(), b.getScheduledTime()));
        return result;
    }

    private static Bucket bucketFor(List<Bucket> buckets, long timeMs) {
        for (Bucket b : buckets) {
            if (timeMs < b.endMs) return b;
        }
        return buckets.get(buckets.size() - 1);
    }

    /** What the feeder reported dispensing; 0 until it has (a new manual record holds the app's placeholder). */
    private static double dispensedOf(FeedingRecord r) {
        return r.isDispensedKnown() ? Math.max(0f, r.getDispensedWeight()) : 0.0;
    }

    private static long startOfDay(long nowMs, int dayOffset) {
        return hourOfDay(nowMs, dayOffset, 0);
    }

    private static long hourOfToday(long nowMs, int hour) {
        return hourOfDay(nowMs, 0, hour);
    }

    private static long hourOfDay(long nowMs, int dayOffset, int hour) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        cal.add(Calendar.DAY_OF_YEAR, dayOffset);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.HOUR_OF_DAY, hour);
        return cal.getTimeInMillis();
    }

    private static String dateLabel(long timeMs) {
        return new SimpleDateFormat("MM/dd", Locale.US).format(new Date(timeMs));
    }

    private static String timeLabel(FeedingRecord r) {
        return r.getScheduledTimeDisplay() != null ? r.getScheduledTimeDisplay() : "--:--";
    }
}
