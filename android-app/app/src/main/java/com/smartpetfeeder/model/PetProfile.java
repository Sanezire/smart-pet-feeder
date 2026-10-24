package com.smartpetfeeder.model;

import com.google.firebase.database.Exclude;
import com.google.firebase.database.IgnoreExtraProperties;

import java.util.Locale;

@IgnoreExtraProperties
public class PetProfile {

    public static final String TYPE_DOG = "DOG";
    public static final String TYPE_CAT = "CAT";

    public static final String ACTIVITY_LOW      = "LOW";
    public static final String ACTIVITY_MODERATE = "MODERATE";
    public static final String ACTIVITY_HIGH     = "HIGH";

    private String name;
    private String type     = TYPE_DOG;
    private float  ageYears;
    private int    ageMonths;
    private float  weightKg;
    private String activity = ACTIVITY_MODERATE;
    private int    feedingsPerDay;
    private long   updatedAt;
    private String ownerUid;

    public PetProfile() {}

    public PetProfile(String name, String type, float ageYears,
                      float weightKg, String activity) {
        this.name      = name;
        this.type      = type;
        this.ageYears  = ageYears;
        this.ageMonths = Math.max(1, Math.round(ageYears * 12f));
        this.weightKg  = weightKg;
        this.activity  = activity;
        this.updatedAt = System.currentTimeMillis();
    }

    public PetProfile(String name, String type, float ageYears,
                      float weightKg, String activity, int feedingsPerDay) {
        this(name, type, ageYears, weightKg, activity);
        this.feedingsPerDay = feedingsPerDay;
    }

    public int getFeedingsPerDay() { return feedingsPerDay; }
    public void setFeedingsPerDay(int feedingsPerDay) { this.feedingsPerDay = feedingsPerDay; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public float getAgeYears() { return ageYears; }
    public void setAgeYears(float ageYears) {
        this.ageYears  = ageYears;
        this.ageMonths = Math.max(1, Math.round(ageYears * 12f));
    }

    public int getAgeMonths() {
        return ageMonths > 0 ? ageMonths : Math.max(1, Math.round(ageYears * 12f));
    }
    public void setAgeMonths(int ageMonths) {
        this.ageMonths = ageMonths;
        this.ageYears  = ageMonths / 12f;
    }

    public float getWeightKg() { return weightKg; }
    public void setWeightKg(float weightKg) { this.weightKg = weightKg; }

    public String getActivity() { return activity; }
    public void setActivity(String activity) { this.activity = activity; }

    public String getOwnerUid() { return ownerUid; }
    public void setOwnerUid(String ownerUid) { this.ownerUid = ownerUid; }

    @Exclude
    public boolean isOwnedBy(String uid) {
        return uid != null && (ownerUid == null || ownerUid.equals(uid));
    }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Exclude
    public boolean isCat() { return TYPE_CAT.equals(type); }

    @Exclude
    public String getAgeLabel() {
        int months = getAgeMonths();
        if (months < 12) return months + (months == 1 ? " month" : " months");
        float years = months / 12f;
        if (months % 12 == 0) {
            int y = months / 12;
            return y + (y == 1 ? " year" : " years");
        }
        return String.format(Locale.US, "%.1f years", years);
    }

    @Exclude
    public String getWeightLabel() {
        if (Math.abs(weightKg - Math.round(weightKg)) < 0.05f) {
            return Math.round(weightKg) + " kg";
        }
        return String.format(Locale.US, "%.1f kg", weightKg);
    }

    @Exclude
    public boolean isValid() {
        return name != null && !name.trim().isEmpty() && weightKg > 0f && ageYears >= 0f
                && feedingsPerDay >= 0 && feedingsPerDay <= 6
                && (TYPE_DOG.equals(type) || TYPE_CAT.equals(type));
    }
}
