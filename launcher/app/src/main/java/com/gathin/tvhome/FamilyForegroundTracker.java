package com.gathin.tvhome;

import java.util.List;

/** Small, platform-free reducer for the foreground/background events returned by UsageStatsManager. */
final class FamilyForegroundTracker {
    enum Type { FOREGROUND, BACKGROUND }

    static final class Event {
        final Type type;
        final String packageName;

        Event(Type type, String packageName) {
            this.type = type;
            this.packageName = packageName;
        }
    }

    private String foregroundPackage;

    FamilyForegroundTracker(String initialPackage) {
        foregroundPackage = clean(initialPackage);
    }

    String foregroundPackage() {
        return foregroundPackage;
    }

    void apply(List<Event> events) {
        if (events == null) return;
        for (Event event : events) {
            if (event == null || event.type == null) continue;
            String pkg = clean(event.packageName);
            if (pkg == null) continue;
            if (event.type == Type.FOREGROUND) {
                foregroundPackage = pkg;
            } else if (pkg.equals(foregroundPackage)) {
                foregroundPackage = null;
            }
        }
    }

    private static String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
