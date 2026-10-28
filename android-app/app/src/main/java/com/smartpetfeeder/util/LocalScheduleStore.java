package com.smartpetfeeder.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.smartpetfeeder.model.FeedingSchedule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps feeding schedules on the phone (SharedPreferences) so they can be viewed and edited
 * without Firebase or a router. They are pushed to the feeder over its own Wi-Fi.
 */
public class LocalScheduleStore {

    public static final int MAX_SCHEDULES = 16; // must match MAX_SCHEDULES in pet-feeder.ino

    private static final String PREFS            = "petfeeder_direct";
    private static final String KEY_SCHEDULES    = "schedules";
    private static final String KEY_DIRECT_ENTRY = "direct_entry";

    private final SharedPreferences prefs;

    public LocalScheduleStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<FeedingSchedule> load() {
        List<FeedingSchedule> out = new ArrayList<>();
        String raw = prefs.getString(KEY_SCHEDULES, null);
        if (raw == null) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                List<String> days = new ArrayList<>();
                JSONArray d = o.optJSONArray("days");
                if (d != null) for (int j = 0; j < d.length(); j++) days.add(d.getString(j));
                out.add(new FeedingSchedule(
                        o.getString("id"),
                        o.getInt("hour"),
                        o.getInt("minute"),
                        (float) o.optDouble("portion", 0),
                        o.optBoolean("enabled", true),
                        o.optString("label", null),
                        days));
            }
        } catch (Exception ignored) {
            // corrupted data: start clean
            out.clear();
        }
        return out;
    }

    public void save(List<FeedingSchedule> schedules) {
        JSONArray array = new JSONArray();
        try {
            for (FeedingSchedule s : schedules) {
                JSONObject o = new JSONObject();
                o.put("id", s.getScheduleId());
                o.put("hour", s.getTimeHour());
                o.put("minute", s.getTimeMinute());
                o.put("portion", s.getPortionGrams());
                o.put("enabled", s.isEnabled());
                o.put("label", s.getLabel());
                JSONArray days = new JSONArray();
                if (s.getRepeatDays() != null) for (String day : s.getRepeatDays()) days.put(day);
                o.put("days", days);
                array.put(o);
            }
        } catch (Exception ignored) {
            return;
        }
        prefs.edit().putString(KEY_SCHEDULES, array.toString()).apply();
    }

    /** True when the person chose "use the feeder without an account" on the login screen. */
    public static boolean isDirectEntry(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DIRECT_ENTRY, false);
    }

    public static void setDirectEntry(Context context, boolean value) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DIRECT_ENTRY, value).apply();
    }
}
