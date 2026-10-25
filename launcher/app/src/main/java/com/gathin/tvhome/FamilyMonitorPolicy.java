package com.gathin.tvhome;

import java.time.ZonedDateTime;

/** Platform-free decisions used by the temporary foreground session monitor. */
final class FamilyMonitorPolicy {
    enum Foreground { LAUNCHER, APP, SETTINGS, UNKNOWN }
    enum Action { STOP, PAUSE, WAIT_FOR_LAUNCH, FINISH_SESSION, KEEP_RUNNING, RETURN_HOME }
    static final long LAUNCH_OBSERVATION_GRACE_MS = 30_000L;

    private FamilyMonitorPolicy() { }

    static Action decide(boolean familyEnabled, boolean usageAccess, boolean sessionPending,
                         boolean screenOn, FamilyProfileStore.Profile profile, Foreground foreground,
                         ZonedDateTime now, long consumedMs, long extensionMs, boolean clockUncertain,
                         boolean foregroundObserved, long sessionAgeMs) {
        if (!familyEnabled || !sessionPending) return Action.STOP;
        if (!usageAccess) return Action.RETURN_HOME;
        if (!screenOn) return Action.PAUSE;
        if (profile == null || now == null) return Action.RETURN_HOME;
        if (foreground == Foreground.LAUNCHER) {
            // The service can start before Android switches away from Home. Do not end an
            // accepted launch until an external foreground package has actually been observed.
            return foregroundObserved ? Action.FINISH_SESSION : Action.WAIT_FOR_LAUNCH;
        }
        if (foreground == Foreground.UNKNOWN) {
            return foregroundObserved || sessionAgeMs >= LAUNCH_OBSERVATION_GRACE_MS
                    ? Action.RETURN_HOME : Action.WAIT_FOR_LAUNCH;
        }
        if (foreground == Foreground.SETTINGS && profile != FamilyProfileStore.Profile.ADULT) {
            return Action.RETURN_HOME;
        }
        if (profile == FamilyProfileStore.Profile.ADULT) return Action.KEEP_RUNNING;

        FamilyTimePolicy.Profile policyProfile = profile == FamilyProfileStore.Profile.XBOX
                ? FamilyTimePolicy.Profile.XBOX : FamilyTimePolicy.Profile.CHILD_TV;
        FamilyTimePolicy.Decision time = FamilyTimePolicy.evaluate(
                policyProfile, now, consumedMs, extensionMs, clockUncertain);
        return time.allowed ? Action.KEEP_RUNNING : Action.RETURN_HOME;
    }
}
