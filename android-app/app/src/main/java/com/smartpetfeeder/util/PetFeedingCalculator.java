package com.smartpetfeeder.util;

import com.smartpetfeeder.model.PetProfile;

public final class PetFeedingCalculator {

    public static final float MIN_WEIGHT_KG = 0.5f;
    public static final float MAX_WEIGHT_DOG_KG = 100f;
    public static final float MAX_WEIGHT_CAT_KG = 15f;
    public static final float MAX_AGE_YEARS = 30f;
    public static final int   MONTHS_PER_YEAR = 12;
    public static final int   MAX_AGE_MONTHS = (int) MAX_AGE_YEARS * MONTHS_PER_YEAR;
    public static final int   MAX_NAME_LENGTH = 30;

    public static final int DEFAULT_FEEDINGS_PER_DAY = 2;
    public static final int MIN_FEEDINGS_PER_DAY = 1;
    public static final int MAX_FEEDINGS_PER_DAY = 6;

    private static final float KCAL_PER_GRAM_DOG = 3.5f;
    private static final float KCAL_PER_GRAM_CAT = 3.8f;

    private PetFeedingCalculator() {}

    public static final class Result {
        public final float dailyGrams;
        public final float portionGrams;
        public final int   feedingsPerDay;

        Result(float dailyGrams, float portionGrams, int feedingsPerDay) {
            this.dailyGrams     = dailyGrams;
            this.portionGrams   = portionGrams;
            this.feedingsPerDay = feedingsPerDay;
        }
    }

    public static float maxWeightFor(String type) {
        return PetProfile.TYPE_CAT.equals(type) ? MAX_WEIGHT_CAT_KG : MAX_WEIGHT_DOG_KG;
    }

    public static Result calculate(PetProfile profile, int fallbackFeedings) {
        if (profile == null || !profile.isValid()) return null;
        int feedings = profile.getFeedingsPerDay();
        if (feedings < MIN_FEEDINGS_PER_DAY || feedings > MAX_FEEDINGS_PER_DAY) {
            feedings = fallbackFeedings >= MIN_FEEDINGS_PER_DAY
                    ? Math.min(fallbackFeedings, MAX_FEEDINGS_PER_DAY)
                    : DEFAULT_FEEDINGS_PER_DAY;
        }

        double rer = 70.0 * Math.pow(profile.getWeightKg(), 0.75);
        double mer = rer * energyFactor(profile);
        double density = profile.isCat() ? KCAL_PER_GRAM_CAT : KCAL_PER_GRAM_DOG;

        float daily   = Math.round(mer / density);
        float portion = Math.round(daily / feedings);
        return new Result(daily, portion, feedings);
    }

    private static double energyFactor(PetProfile p) {
        int ageMonths = p.getAgeMonths();
        String activity = p.getActivity() != null ? p.getActivity() : PetProfile.ACTIVITY_MODERATE;

        if (p.isCat()) {
            if (ageMonths < 4)  return 2.5;
            if (ageMonths < 12) return 2.0;
            boolean senior = ageMonths >= 10 * MONTHS_PER_YEAR;
            switch (activity) {
                case PetProfile.ACTIVITY_LOW:  return 1.0;
                case PetProfile.ACTIVITY_HIGH: return senior ? 1.2 : 1.4;
                default:                       return senior ? 1.1 : 1.2;
            }
        }

        if (ageMonths < 4)  return 3.0;
        if (ageMonths < 12) return 2.0;
        boolean senior = ageMonths >= 7 * MONTHS_PER_YEAR;
        switch (activity) {
            case PetProfile.ACTIVITY_LOW:  return 1.2;
            case PetProfile.ACTIVITY_HIGH: return senior ? 1.6 : 2.0;
            default:                       return senior ? 1.4 : 1.6;
        }
    }
}
