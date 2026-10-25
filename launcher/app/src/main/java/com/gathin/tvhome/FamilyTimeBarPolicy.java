package com.gathin.tvhome;

import java.util.List;

/** Small display policy shared by the family time overlay and its JVM tests. */
final class FamilyTimeBarPolicy {
    enum Colour { GREEN, YELLOW, ORANGE, RED }

    static final int GREEN_ARGB = 0xFF62D69A;
    static final int YELLOW_ARGB = 0xFFFFD166;
    static final int ORANGE_ARGB = 0xFFFF9F43;
    static final int RED_ARGB = 0xFFFF5D68;

    private FamilyTimeBarPolicy() { }

    static boolean shouldDisplay(boolean enabled, boolean permission, boolean screenOn,
                                 boolean foregroundApp, FamilyProfileStore.Profile profile) {
        return enabled && permission && screenOn && foregroundApp && profile != null
                && profile != FamilyProfileStore.Profile.ADULT;
    }

    static int displayMinutes(long milliseconds) {
        if (milliseconds <= 0L) return 0;
        long whole = milliseconds / FamilyTimePolicy.MINUTE_MS;
        return (int) Math.min(Integer.MAX_VALUE,
                whole + (milliseconds % FamilyTimePolicy.MINUTE_MS == 0L ? 0L : 1L));
    }

    static int warningMinutes(List<Integer> crossedThresholds, long remainingMs) {
        if (crossedThresholds == null || crossedThresholds.isEmpty()) return 0;
        return crossedThresholds.size() == 1
                ? crossedThresholds.get(0) : displayMinutes(remainingMs);
    }

    static int progressPercent(long usedMs, long totalMs) {
        if (totalMs <= 0L) return 0;
        double ratio = Math.max(0d, Math.min(1d, (double) usedMs / (double) totalMs));
        return (int) Math.round(ratio * 100d);
    }

    static Colour colourForRemaining(long remainingMs) {
        if (remainingMs <= FamilyTimePolicy.MINUTE_MS) return Colour.RED;
        if (remainingMs <= 5L * FamilyTimePolicy.MINUTE_MS) return Colour.ORANGE;
        if (remainingMs <= 10L * FamilyTimePolicy.MINUTE_MS) return Colour.YELLOW;
        return Colour.GREEN;
    }

    static int argbForRemaining(long remainingMs) {
        switch (colourForRemaining(remainingMs)) {
            case YELLOW: return YELLOW_ARGB;
            case ORANGE: return ORANGE_ARGB;
            case RED: return RED_ARGB;
            case GREEN:
            default: return GREEN_ARGB;
        }
    }
}
