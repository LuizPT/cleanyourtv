package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class FamilyMonitorPolicyTest {
    private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
    private static final ZonedDateTime OPEN = LocalDate.of(2026, 10, 23).atTime(21, 0).atZone(LISBON);

    private FamilyMonitorPolicy.Action decide(FamilyProfileStore.Profile profile,
            FamilyMonitorPolicy.Foreground foreground, boolean access, boolean screenOn,
            long usedMs, boolean clockUncertain) {
        return FamilyMonitorPolicy.decide(true, access, true, screenOn, profile, foreground,
                OPEN, usedMs, 0L, clockUncertain, true, 60_000L);
    }

    @Test public void childSessionContinuesUntilTheQuotaAndReturnsHomeAtTheLimit() {
        assertEquals(FamilyMonitorPolicy.Action.KEEP_RUNNING,
                decide(FamilyProfileStore.Profile.CHILD_TV, FamilyMonitorPolicy.Foreground.APP,
                        true, true, 59L * FamilyTimePolicy.MINUTE_MS, false));
        assertEquals(FamilyMonitorPolicy.Action.RETURN_HOME,
                decide(FamilyProfileStore.Profile.CHILD_TV, FamilyMonitorPolicy.Foreground.APP,
                        true, true, 60L * FamilyTimePolicy.MINUTE_MS, false));
    }

    @Test public void childSessionReturnsHomeOutsideItsTimeWindow() {
        ZonedDateTime afterHours = OPEN.withHour(22);
        assertEquals(FamilyMonitorPolicy.Action.RETURN_HOME,
                FamilyMonitorPolicy.decide(true, true, true, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.APP, afterHours, 0L, 0L, false, true, 60_000L));
    }

    @Test public void missingUsageAccessAndUnknownChildForegroundFailClosed() {
        assertEquals(FamilyMonitorPolicy.Action.RETURN_HOME,
                decide(FamilyProfileStore.Profile.CHILD_TV, FamilyMonitorPolicy.Foreground.APP,
                        false, true, 0L, false));
        assertEquals(FamilyMonitorPolicy.Action.RETURN_HOME,
                decide(FamilyProfileStore.Profile.XBOX, FamilyMonitorPolicy.Foreground.UNKNOWN,
                        true, true, 0L, false));
    }

    @Test public void settingsAreBlockedForChildrenButRemainAvailableToAdult() {
        assertEquals(FamilyMonitorPolicy.Action.RETURN_HOME,
                decide(FamilyProfileStore.Profile.CHILD_TV, FamilyMonitorPolicy.Foreground.SETTINGS,
                        true, true, 0L, false));
        assertEquals(FamilyMonitorPolicy.Action.KEEP_RUNNING,
                decide(FamilyProfileStore.Profile.ADULT, FamilyMonitorPolicy.Foreground.SETTINGS,
                        true, true, Long.MAX_VALUE, true));
    }

    @Test public void screenOffPausesTheSessionAndLauncherEndsIt() {
        assertEquals(FamilyMonitorPolicy.Action.PAUSE,
                decide(FamilyProfileStore.Profile.CHILD_TV, FamilyMonitorPolicy.Foreground.APP,
                        true, false, 60L * FamilyTimePolicy.MINUTE_MS, false));
        assertEquals(FamilyMonitorPolicy.Action.FINISH_SESSION,
                decide(FamilyProfileStore.Profile.CHILD_TV, FamilyMonitorPolicy.Foreground.LAUNCHER,
                        true, true, 0L, false));
    }

    @Test public void launcherDoesNotEndARequestedLaunchBeforeAnAppWasObserved() {
        assertEquals(FamilyMonitorPolicy.Action.WAIT_FOR_LAUNCH,
                FamilyMonitorPolicy.decide(true, true, true, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.LAUNCHER, OPEN, 0L, 0L, false,
                        false, 60_000L));
        assertEquals(FamilyMonitorPolicy.Action.FINISH_SESSION,
                FamilyMonitorPolicy.decide(true, true, true, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.LAUNCHER, OPEN, 0L, 0L, false,
                        true, 60_000L));
    }

    @Test public void unknownForegroundWaitsBrieflyThenReturnsHome() {
        assertEquals(FamilyMonitorPolicy.Action.WAIT_FOR_LAUNCH,
                FamilyMonitorPolicy.decide(true, true, true, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.UNKNOWN, OPEN, 0L, 0L, false,
                        false, FamilyMonitorPolicy.LAUNCH_OBSERVATION_GRACE_MS - 1L));
        assertEquals(FamilyMonitorPolicy.Action.RETURN_HOME,
                FamilyMonitorPolicy.decide(true, true, true, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.UNKNOWN, OPEN, 0L, 0L, false,
                        false, FamilyMonitorPolicy.LAUNCH_OBSERVATION_GRACE_MS));
    }

    @Test public void clockUncertaintyReturnsToAdultRecovery() {
        assertEquals(FamilyMonitorPolicy.Action.RETURN_HOME,
                decide(FamilyProfileStore.Profile.CHILD_TV, FamilyMonitorPolicy.Foreground.APP,
                        true, true, 0L, true));
    }

    @Test public void adultHasNoDailyQuota() {
        assertEquals(FamilyMonitorPolicy.Action.KEEP_RUNNING,
                decide(FamilyProfileStore.Profile.ADULT, FamilyMonitorPolicy.Foreground.APP,
                        true, true, Long.MAX_VALUE, false));
    }

    @Test public void disabledFeatureOrFinishedSessionStopsTheMonitor() {
        assertEquals(FamilyMonitorPolicy.Action.STOP,
                FamilyMonitorPolicy.decide(false, true, true, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.APP, OPEN, 0L, 0L, false, true, 60_000L));
        assertEquals(FamilyMonitorPolicy.Action.STOP,
                FamilyMonitorPolicy.decide(true, true, false, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.APP, OPEN, 0L, 0L, false, true, 60_000L));
    }

    @Test public void diagnosticSessionUsesItsIsolatedTimerAndStillFailsClosedOnClockUncertainty() {
        FamilyTimePolicy.Schedule schedule = new FamilyTimePolicy.Schedule(7 * 60, 22 * 60,
                60L * FamilyTimePolicy.MINUTE_MS);
        assertEquals(FamilyMonitorPolicy.Action.KEEP_RUNNING,
                FamilyMonitorPolicy.decide(true, true, true, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.APP, OPEN.withHour(23), 60L * FamilyTimePolicy.MINUTE_MS,
                        0L, false, true, 60_000L, schedule, true, FamilyTimePolicy.MINUTE_MS));
        assertEquals(FamilyMonitorPolicy.Action.RETURN_HOME,
                FamilyMonitorPolicy.decide(true, true, true, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.APP, OPEN, 0L, 0L, false, true, 60_000L,
                        schedule, true, 0L));
        assertEquals(FamilyMonitorPolicy.Action.RETURN_HOME,
                FamilyMonitorPolicy.decide(true, true, true, true, FamilyProfileStore.Profile.CHILD_TV,
                        FamilyMonitorPolicy.Foreground.APP, OPEN, 0L, 0L, true, true, 60_000L,
                        schedule, true, FamilyTimePolicy.MINUTE_MS));
    }

    @Test public void monitorHealthBecomesLimitedWhenSessionPollingIsStale() {
        assertEquals(false, FamilyMonitorPolicy.isSessionStale(false, 0L, 100_000L));
        assertEquals(false, FamilyMonitorPolicy.isSessionStale(true, 100_000L, 130_000L));
        assertEquals(true, FamilyMonitorPolicy.isSessionStale(true, 0L, 100_000L));
        assertEquals(true, FamilyMonitorPolicy.isSessionStale(true, 100_000L, 130_001L));
        assertEquals(true, FamilyMonitorPolicy.isSessionStale(true, 130_000L, 100_000L));
    }
}
