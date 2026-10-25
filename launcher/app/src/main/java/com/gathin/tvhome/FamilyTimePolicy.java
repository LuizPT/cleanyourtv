package com.gathin.tvhome;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Pure, offline schedule/quota decisions; this class does not measure usage or control HDMI. */
final class FamilyTimePolicy {
    enum Profile { ADULT, CHILD_TV, XBOX }
    enum Reason { ALLOWED, OUTSIDE_HOURS, DAILY_LIMIT_REACHED }

    static final long MINUTE_MS = 60_000L;
    static final LocalTime START = LocalTime.of(7, 0);
    static final LocalTime END_EXCLUSIVE = LocalTime.of(22, 0);

    private FamilyTimePolicy() { }

    static long dailyBudgetMs(Profile profile, DayOfWeek day) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(day, "day");
        switch (profile) {
            case ADULT:
                return Long.MAX_VALUE;
            case CHILD_TV:
                return 60L * MINUTE_MS;
            case XBOX:
                return (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? 120L : 60L) * MINUTE_MS;
            default:
                throw new AssertionError(profile);
        }
    }

    static boolean isWithinChildHours(LocalTime time) {
        Objects.requireNonNull(time, "time");
        return !time.isBefore(START) && time.isBefore(END_EXCLUSIVE);
    }

    static Decision evaluate(Profile profile, ZonedDateTime when, long consumedMs) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(when, "when");
        if (profile == Profile.ADULT) {
            return new Decision(true, Reason.ALLOWED, Long.MAX_VALUE);
        }
        long budget = dailyBudgetMs(profile, when.getDayOfWeek());
        long remaining = consumedMs < 0L ? budget : Math.max(0L, budget - Math.min(budget, consumedMs));
        if (!isWithinChildHours(when.toLocalTime())) {
            return new Decision(false, Reason.OUTSIDE_HOURS, remaining);
        }
        if (remaining == 0L) {
            return new Decision(false, Reason.DAILY_LIMIT_REACHED, 0L);
        }
        return new Decision(true, Reason.ALLOWED, remaining);
    }

    static final class Decision {
        final boolean allowed;
        final Reason reason;
        final long remainingMs;

        private Decision(boolean allowed, Reason reason, long remainingMs) {
            this.allowed = allowed;
            this.reason = reason;
            this.remainingMs = remainingMs;
        }
    }
}
