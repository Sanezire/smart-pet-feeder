package com.smartpetfeeder.model;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.ArrayList;
import java.util.List;

@IgnoreExtraProperties
public class FeedingSchedule {

    private String scheduleId;
    private int timeHour;
    private int timeMinute;
    private float portionGrams;
    private boolean enabled;
    private String label;
    private List<String> repeatDays;

    public FeedingSchedule() {}

    public FeedingSchedule(String scheduleId, int timeHour, int timeMinute,
                           float portionGrams, boolean enabled, String label) {
        this.scheduleId   = scheduleId;
        this.timeHour     = timeHour;
        this.timeMinute   = timeMinute;
        this.portionGrams = portionGrams;
        this.enabled      = enabled;
        this.label        = normalizeLabel(label);
        this.repeatDays   = new ArrayList<>();
    }

    public FeedingSchedule(String scheduleId, int timeHour, int timeMinute,
                           float portionGrams, boolean enabled, String label,
                           List<String> repeatDays) {
        this(scheduleId, timeHour, timeMinute, portionGrams, enabled, label);
        this.repeatDays = (repeatDays != null) ? repeatDays : new ArrayList<>();
    }

    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }

    public int getTimeHour() { return timeHour; }
    public void setTimeHour(int timeHour) { this.timeHour = timeHour; }

    public int getTimeMinute() { return timeMinute; }
    public void setTimeMinute(int timeMinute) { this.timeMinute = timeMinute; }

    public float getPortionGrams() { return portionGrams; }
    public void setPortionGrams(float portionGrams) { this.portionGrams = portionGrams; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = normalizeLabel(label); }

    public List<String> getRepeatDays() { return repeatDays; }
    public void setRepeatDays(List<String> repeatDays) {
        this.repeatDays = (repeatDays != null) ? repeatDays : new ArrayList<>();
    }

    public String getRepeatDisplay() {
        if (repeatDays == null || repeatDays.isEmpty()) return "Once";
        if (repeatDays.size() == 7) return "Every day";
        if (repeatDays.size() >= 5) {
            List<String> weekdays = java.util.Arrays.asList("Mon","Tue","Wed","Thu","Fri");
            if (new java.util.HashSet<>(repeatDays).containsAll(weekdays) && repeatDays.size() == 5)
                return "Weekdays";
        }
        return android.text.TextUtils.join(", ", repeatDays);
    }

    public String getTimeDisplay() {
        return formatTime12(timeHour, timeMinute);
    }

    public static String formatTime12(int hour24, int minute) {
        int h = ((hour24 % 24) + 24) % 24;
        String suffix = h >= 12 ? "PM" : "AM";
        int h12 = h % 12;
        if (h12 == 0) h12 = 12;
        return String.format(java.util.Locale.US, "%02d:%02d %s", h12, minute, suffix);
    }

    private static final java.util.regex.Pattern TIME_12 = java.util.regex.Pattern.compile(
            "^\\s*(\\d{1,2})\\s*:\\s*(\\d{2})\\s*([AaPp])\\.?\\s*[Mm]\\.?\\s*$");
    private static final java.util.regex.Pattern TIME_24 = java.util.regex.Pattern.compile(
            "^\\s*(\\d{1,2})\\s*:\\s*(\\d{2})\\s*$");

    public static int[] parseTime(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m12 = TIME_12.matcher(text);
        if (m12.matches()) {
            int h = Integer.parseInt(m12.group(1));
            int min = Integer.parseInt(m12.group(2));
            if (h < 1 || h > 12 || min > 59) return null;
            boolean pm = Character.toUpperCase(m12.group(3).charAt(0)) == 'P';
            return new int[]{ (h % 12) + (pm ? 12 : 0), min };
        }
        java.util.regex.Matcher m24 = TIME_24.matcher(text);
        if (m24.matches()) {
            int h = Integer.parseInt(m24.group(1));
            int min = Integer.parseInt(m24.group(2));
            if (h > 23 || min > 59) return null;
            return new int[]{ h, min };
        }
        return null;
    }

    private static String normalizeLabel(String label) {
        if (label == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,2}):(\\d{2})(?!\\s*[AaPp]\\.?\\s*[Mm])(.*)$", java.util.regex.Pattern.DOTALL)
                .matcher(label);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h <= 23 && min <= 59) return formatTime12(h, min) + m.group(3);
        }
        return label;
    }

    public long getScheduledEpochForToday() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, timeHour);
        cal.set(java.util.Calendar.MINUTE,      timeMinute);
        cal.set(java.util.Calendar.SECOND,      0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
