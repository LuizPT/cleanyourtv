package com.gathin.tvhome;

import java.time.ZonedDateTime;

/** Pure launch policy for the optional family profiles. It never attempts to control Android itself. */
final class FamilyAccessPolicy {
    enum Target { APP, TUNER_INPUT, PASSTHROUGH_INPUT, SYSTEM_SETTINGS, UNKNOWN }
    enum Reason { ALLOWED, PROFILE_REQUIRED, TARGET_NOT_ALLOWED, WRONG_XBOX_INPUT,
        OUTSIDE_HOURS, DAILY_LIMIT_REACHED, CLOCK_UNCERTAIN, MONITOR_REQUIRED, SESSION_PENDING }

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

    private FamilyAccessPolicy() { }

    static Decision evaluate(FamilyTimePolicy.Profile profile, Target target,
                             String targetInputId, String xboxInputId, ZonedDateTime when,
                             long consumedMs, long extensionMs, boolean clockUncertain) {
        FamilyTimePolicy.Schedule schedule = profile == null || when == null
                ? null : FamilyTimePolicy.defaultSchedule(profile, when.getDayOfWeek());
        return evaluate(profile, target, targetInputId, xboxInputId, when, consumedMs,
                extensionMs, clockUncertain, schedule, false);
    }

    static Decision evaluate(FamilyTimePolicy.Profile profile, Target target,
                             String targetInputId, String xboxInputId, ZonedDateTime when,
                             long consumedMs, long extensionMs, boolean clockUncertain,
                             FamilyTimePolicy.Schedule schedule, boolean diagnosticSession) {
        if (profile == null) return denied(Reason.PROFILE_REQUIRED);
        if (profile == FamilyTimePolicy.Profile.ADULT) {
            return new Decision(true, Reason.ALLOWED, Long.MAX_VALUE);
        }
        if (target == null) return denied(Reason.TARGET_NOT_ALLOWED);

        if (profile == FamilyTimePolicy.Profile.CHILD_TV) {
            if (target != Target.APP && target != Target.TUNER_INPUT) {
                return denied(Reason.TARGET_NOT_ALLOWED);
            }
        } else {
            if (target != Target.PASSTHROUGH_INPUT) return denied(Reason.TARGET_NOT_ALLOWED);
            if (xboxInputId == null || xboxInputId.isEmpty() || !xboxInputId.equals(targetInputId)) {
                return denied(Reason.WRONG_XBOX_INPUT);
            }
        }

        if (diagnosticSession) return new Decision(true, Reason.ALLOWED, 0L);
        FamilyTimePolicy.Decision time = FamilyTimePolicy.evaluate(
                profile, when, consumedMs, extensionMs, clockUncertain, schedule);
        if (time.allowed) return new Decision(true, Reason.ALLOWED, time.remainingMs);
        switch (time.reason) {
            case OUTSIDE_HOURS:
                return new Decision(false, Reason.OUTSIDE_HOURS, time.remainingMs);
            case DAILY_LIMIT_REACHED:
                return new Decision(false, Reason.DAILY_LIMIT_REACHED, time.remainingMs);
            case CLOCK_UNCERTAIN:
                return new Decision(false, Reason.CLOCK_UNCERTAIN, time.remainingMs);
            default:
                return denied(Reason.TARGET_NOT_ALLOWED);
        }
    }

    private static Decision denied(Reason reason) {
        return new Decision(false, reason, 0L);
    }

    static Decision blocked(Reason reason) {
        return denied(reason);
    }
}
