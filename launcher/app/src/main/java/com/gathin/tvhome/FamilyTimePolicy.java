package com.gathin.tvhome;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Pure, offline schedule/quota decisions; this class does not measure usage or control HDMI. */
final class FamilyTimePolicy {
    enum Profile { ADULT, CHILD_TV, XBOX }
    enum Reason { ALLOWED, OUTSIDE_HOURS, DAILY_LIMIT_REACHED, CLOCK_UNCERTAIN }

    static final long MINUTE_MS = 60_000L;
    static final LocalTime START = LocalTime.of(7, 0);
    static final LocalTime END_EXCLUSIVE = LocalTime.of(22, 0);

    static final class Schedule {
        final int startMinute;
        final int endMinute;
        final long budgetMs;

        Schedule(int startMinute, int endMinute, long budgetMs) {
            if (startMinute < 0 || startMinute >= 24 * 60 || endMinute <= startMinute
                    || endMinute > 24 * 60 || budgetMs < 0L) {
                throw new IllegalArgumentException("Invalid family schedule");
            }
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.budgetMs = budgetMs;
        }

        boolean contains(LocalTime time) {
            int minute = time.getHour() * 60 + time.getMinute();
            return minute >= startMinute && minute < endMinute;
        }
    }

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

    static Schedule defaultSchedule(Profile profile, DayOfWeek day) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(day, "day");
        if (profile == Profile.ADULT) return new Schedule(0, 24 * 60, Long.MAX_VALUE);
        return new Schedule(7 * 60, 22 * 60, dailyBudgetMs(profile, day));
    }

    static Decision evaluate(Profile profile, ZonedDateTime when, long consumedMs) {
        return evaluate(profile, when, consumedMs, 0L, false);
    }

    static Decision evaluate(Profile profile, ZonedDateTime when, long consumedMs,
                             long extensionMs, boolean clockUncertain) {
        return evaluate(profile, when, consumedMs, extensionMs, clockUncertain,
                defaultSchedule(profile, when.getDayOfWeek()));
    }

    static Decision evaluate(Profile profile, ZonedDateTime when, long consumedMs,
                             long extensionMs, boolean clockUncertain, Schedule schedule) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(schedule, "schedule");
        if (profile == Profile.ADULT) {
            return new Decision(true, Reason.ALLOWED, Long.MAX_VALUE);
        }
        if (clockUncertain) return new Decision(false, Reason.CLOCK_UNCERTAIN, 0L);
        long budget = schedule.budgetMs;
        long extra = Math.max(0L, extensionMs);
        budget = budget > Long.MAX_VALUE - extra ? Long.MAX_VALUE : budget + extra;
        long remaining = consumedMs < 0L ? budget : Math.max(0L, budget - Math.min(budget, consumedMs));
        if (!schedule.contains(when.toLocalTime())) {
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
