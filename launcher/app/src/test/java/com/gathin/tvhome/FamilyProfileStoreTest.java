package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

public class FamilyProfileStoreTest {
    private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private FamilyProfileStore store() {
        return new FamilyProfileStore(new File(folder.getRoot(), "family-profiles.properties"));
    }

    private static long wall(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(LISBON).toInstant().toEpochMilli();
    }

    private static void markAppForeground(FamilyProfileStore state, LocalDate day,
                                          long wall, long monotonic) {
        assertTrue(state.setSessionForeground(true, "test.tv.app", day, wall,
                monotonic, monotonic).saved);
    }

    @Test public void profilesStartDisabledAndSettingsPersist() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        FamilyProfileStore first = store();
        assertFalse(first.isEnabled());
        assertTrue(first.setEnabled(true));
        assertTrue(first.setXboxInputId("vendor/.TvInput/HW1"));
        assertTrue(first.snapshot(day).timeBarVisible);
        assertTrue(first.setTimeBarVisible(false));
        assertTrue(first.setTimeBarCorner(FamilyProfileStore.TimeBarCorner.RIGHT));
        assertTrue(first.setTimeBarContent(FamilyProfileStore.TimeBarContent.USED));

        FamilyProfileStore reopened = store();
        assertTrue(reopened.isEnabled());
        assertEquals("vendor/.TvInput/HW1", reopened.snapshot(day).xboxInputId);
        assertFalse(reopened.snapshot(day).timeBarVisible);
        assertEquals(FamilyProfileStore.TimeBarCorner.RIGHT, reopened.snapshot(day).timeBarCorner);
        assertEquals(FamilyProfileStore.TimeBarContent.USED, reopened.snapshot(day).timeBarContent);
        assertEquals(0L, reopened.snapshot(day).childUsedMs);
    }

    @Test public void timeBarPreferencesHaveNonIntrusiveDefaults() {
        FamilyProfileStore.Snapshot snapshot = store().snapshot(LocalDate.of(2026, 10, 23));
        assertTrue(snapshot.timeBarVisible);
        assertEquals(FamilyProfileStore.TimeBarCorner.LEFT, snapshot.timeBarCorner);
        assertEquals(FamilyProfileStore.TimeBarContent.BOTH, snapshot.timeBarContent);
    }

    @Test public void childAppVisibilityIsSeededOnceAndPersistsIndependently() {
        FamilyProfileStore first = store();
        assertTrue(first.initializeChildApps(Arrays.asList("video.one", "game.two", "bad,name")));
        assertTrue(first.childAppsInitialized());
        assertTrue(first.isChildAppAllowed("video.one"));
        assertTrue(first.isChildAppAllowed("game.two"));
        assertFalse(first.isChildAppAllowed("bad,name"));
        assertFalse(first.isChildAppAllowed("installed.later"));

        assertTrue(first.setChildAppAllowed("installed.later", true));
        assertTrue(first.initializeChildApps(Collections.singleton("replacement.baseline")));
        FamilyProfileStore reopened = store();
        Set<String> allowed = reopened.childAllowedApps();
        assertTrue(allowed.contains("video.one"));
        assertTrue(allowed.contains("installed.later"));
        assertFalse(allowed.contains("replacement.baseline"));
    }

    @Test public void uninstallRevokesChildApprovalButPackageUpdateDoesNot() {
        FamilyProfileStore state = store();
        assertTrue(state.initializeChildApps(Collections.singleton("family.game")));
        // A package replacement does not call removeChildAppApproval; approval survives the update.
        assertTrue(store().isChildAppAllowed("family.game"));
        assertTrue(state.removeChildAppApproval("family.game"));
        assertFalse(store().isChildAppAllowed("family.game"));
        // Reinstalling the same package is unapproved until an adult approves it again.
        assertFalse(store().isChildAppAllowed("family.game"));
        assertTrue(state.setChildAppAllowed("family.game", true));
        assertTrue(store().isChildAppAllowed("family.game"));
    }

    @Test public void reinstallIdentityDoesNotInheritAnOldApproval() {
        FamilyProfileStore state = store();
        assertTrue(state.initializeChildApps(Collections.singletonMap("family.game", 100L)));
        assertTrue(state.isChildAppAllowed("family.game", 100L));
        // Package updates preserve firstInstallTime; a full uninstall/reinstall gets a new identity.
        assertTrue(state.isChildAppAllowed("family.game", 100L));
        assertFalse(state.isChildAppAllowed("family.game", 200L));
        assertTrue(state.setChildAppAllowed("family.game", 200L, true));
        assertTrue(store().isChildAppAllowed("family.game", 200L));
        assertFalse(store().isChildAppAllowed("family.game", 100L));
    }

    @Test public void uninitializedOrCorruptChildVisibilityFailsClosed() throws Exception {
        assertFalse(store().isChildAppAllowed("video.one"));
        File file = new File(folder.getRoot(), "family-profiles.properties");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("child_apps_initialized=true\nchild_apps=../bad\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        FamilyProfileStore damaged = new FamilyProfileStore(file);
        assertFalse(damaged.isChildAppAllowed("video.one"));
        assertTrue(damaged.hasStorageError());
    }

    @Test public void childAndXboxUseSeparatePersistentDailyBalances() {
        LocalDate friday = LocalDate.of(2026, 10, 23);
        long startWall = wall(friday, 12, 0);
        long startClock = 100_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, friday,
                startWall, startClock, startClock, true));
        markAppForeground(state, friday, startWall, startClock);
        state.finishSession(friday, startWall + 30 * 60_000L, startClock + 30 * 60_000L,
                startClock + 30 * 60_000L);

        assertTrue(state.startSession(FamilyProfileStore.Profile.XBOX, friday,
                startWall + 30 * 60_000L, startClock + 30 * 60_000L,
                startClock + 30 * 60_000L, true));
        markAppForeground(state, friday, startWall + 30 * 60_000L, startClock + 30 * 60_000L);
        state.finishSession(friday, startWall + 60 * 60_000L, startClock + 60 * 60_000L,
                startClock + 60 * 60_000L);

        FamilyProfileStore reopened = store();
        FamilyProfileStore.Snapshot snapshot = reopened.snapshot(friday);
        assertEquals(30L * 60_000L, snapshot.childUsedMs);
        assertEquals(30L * 60_000L, snapshot.xboxUsedMs);
        assertEquals(30L * 60_000L, snapshot.remainingMs(FamilyProfileStore.Profile.CHILD_TV, friday));
        assertEquals(30L * 60_000L, snapshot.remainingMs(FamilyProfileStore.Profile.XBOX, friday));
        assertEquals(0L, reopened.snapshot(friday.plusDays(1)).childUsedMs);
    }

    @Test public void screenOffIntervalsDoNotConsumeTimeAndWarningsAreSavedOnce() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long w = wall(day, 12, 0), e = 200_000L, u = 200_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, day, w, e, u, true));
        markAppForeground(state, day, w, e);
        FamilyProfileStore.UsageUpdate ten = state.recordSession(day, w + 50 * 60_000L,
                e + 50 * 60_000L, u + 50 * 60_000L);
        assertTrue(ten.warningsMinutes.contains(10));
        FamilyProfileStore.UsageUpdate five = state.recordSession(day, w + 55 * 60_000L,
                e + 55 * 60_000L, u + 55 * 60_000L);
        assertTrue(five.warningsMinutes.contains(5));
        FamilyProfileStore.UsageUpdate one = state.recordSession(day, w + 59 * 60_000L,
                e + 59 * 60_000L, u + 59 * 60_000L);
        assertTrue(one.warningsMinutes.contains(1));
        state.setSessionScreenOn(false, day, w + 60 * 60_000L, e + 60 * 60_000L, u + 60 * 60_000L);
        state.setSessionScreenOn(true, day, w + 120 * 60_000L, e + 120 * 60_000L, u + 120 * 60_000L);
        state.finishSession(day, w + 130 * 60_000L, e + 130 * 60_000L, u + 130 * 60_000L);

        FamilyProfileStore.Snapshot snapshot = store().snapshot(day);
        assertEquals(70L * 60_000L, snapshot.childUsedMs);
    }

    @Test public void clockRollbackPausesChildUseUntilAdultConfirms() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        FamilyProfileStore state = store();
        long firstWall = wall(day, 12, 0);
        assertTrue(state.observeClock(firstWall, 500_000L, day));
        assertFalse(state.observeClock(firstWall - 60 * 60_000L, 560_000L, day));
        assertTrue(state.snapshot(day).clockUncertain);
        assertTrue(state.confirmClock(firstWall + 60_000L, 620_000L, day));
        assertFalse(store().snapshot(day).clockUncertain);
    }

    @Test public void unexpectedForwardClockJumpPausesFurtherUse() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long firstWall = wall(day, 12, 0);
        FamilyProfileStore state = store();
        assertTrue(state.observeClock(firstWall, 500_000L, day));
        assertFalse(state.observeClock(firstWall + 3 * 60 * 60_000L, 560_000L, day));
        assertTrue(store().snapshot(day).clockUncertain);
    }

    @Test public void elapsedRealtimeResetRecoversToLauncherAndRequiresClockReview() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 12, 0);
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                startWall, 900_000L, 900_000L, true));
        markAppForeground(state, day, startWall, 900_000L);

        FamilyProfileStore.UsageUpdate update = state.recordSession(day,
                startWall + 60_000L, 1_000L, 1_000L);
        assertFalse(update.saved);
        FamilyProfileStore.Snapshot recovered = store().snapshot(day);
        assertFalse(recovered.sessionPending);
        assertTrue(recovered.clockUncertain);
        assertTrue(recovered.mustReturnHome);
    }

    @Test public void adultRecoveryKeepsDamagedFamilyFileAndDisablesOnlyFamilyEdition() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 23);
        File file = new File(folder.getRoot(), "family-profiles.properties");
        byte[] damaged = ("family_enabled=true\nused.child." + day + "=not-a-number\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(damaged);
        }

        FamilyProfileStore state = new FamilyProfileStore(file);
        assertTrue(state.isEnabled());
        assertTrue(state.hasStorageError());
        long now = wall(day, 12, 0);
        assertTrue(state.recoverStorageAfterAdultAuthentication(now, 500_000L, day));

        FamilyProfileStore recovered = new FamilyProfileStore(file);
        assertFalse(recovered.isEnabled());
        assertFalse(recovered.hasStorageError());
        assertTrue(recovered.snapshot(day).clockUncertain);
        File backup = new File(file.getPath() + ".recovery-" + now);
        assertTrue(backup.isFile());
        assertEquals(new String(damaged, java.nio.charset.StandardCharsets.UTF_8),
                new String(Files.readAllBytes(backup.toPath()), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void interruptedActiveSessionRequiresAdultReviewWithoutChargingScreenOffTime() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 12, 0);
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.XBOX, day,
                startWall, 900_000L, 900_000L, true));
        markAppForeground(state, day, startWall, 900_000L);

        FamilyProfileStore reopened = store();
        assertTrue(reopened.recoverInterruptedSession(startWall + 20 * 60_000L, 1_000L, day));
        FamilyProfileStore.Snapshot snapshot = reopened.snapshot(day);
        assertFalse(snapshot.sessionPending);
        assertTrue(snapshot.clockUncertain);
        assertEquals(0L, snapshot.xboxUsedMs);
    }

    @Test public void xboxSessionCannotBeDuplicatedAndFailedLaunchCanBeRolledBack() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 12, 0);
        long elapsed = 900_000L;
        FamilyProfileStore state = store();

        assertTrue(state.startSession(FamilyProfileStore.Profile.XBOX, day,
                startWall, elapsed, elapsed, true));
        assertFalse("A second launch must not create another active session",
                state.startSession(FamilyProfileStore.Profile.XBOX, day,
                        startWall, elapsed, elapsed, true));
        assertTrue(state.hasPendingSession());

        FamilyProfileStore.UsageUpdate rolledBack = state.finishSession(day,
                startWall, elapsed, elapsed);
        assertTrue(rolledBack.saved);
        assertFalse(state.hasPendingSession());
        assertEquals(0L, store().snapshot(day).xboxUsedMs);
    }

    @Test public void authenticatedExtensionsArePersistentAndBounded() {
        LocalDate day = LocalDate.of(2026, 10, 24);
        FamilyProfileStore state = store();
        assertFalse(state.addExtension(FamilyProfileStore.Profile.CHILD_TV, day, 121));
        assertTrue(state.addExtension(FamilyProfileStore.Profile.CHILD_TV, day, 15));
        assertEquals(75L * 60_000L, store().snapshot(day).remainingMs(FamilyProfileStore.Profile.CHILD_TV, day));
    }

    @Test public void monitorResumeAfterProcessDeathChargesElapsedForegroundTime() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 12, 0), startElapsed = 1_000_000L;
        FamilyProfileStore firstProcess = store();
        assertTrue(firstProcess.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app",
                day, startWall, startElapsed, startElapsed, true));
        markAppForeground(firstProcess, day, startWall, startElapsed);

        FamilyProfileStore restartedProcess = store();
        FamilyProfileStore.UsageUpdate update = restartedProcess.recordSession(day,
                startWall + 12 * 60_000L, startElapsed + 12 * 60_000L, startElapsed + 12 * 60_000L);
        assertTrue(update.saved);
        assertEquals(12L * 60_000L, store().snapshot(day).childUsedMs);
        assertTrue(store().snapshot(day).sessionPending);
    }

    @Test public void foregroundChangesPauseUsageWithoutEndingTheSession() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long wall = wall(day, 12, 0), mono = 2_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                wall, mono, mono, true));
        markAppForeground(state, day, wall, mono);
        state.setSessionForeground(false, "com.android.systemui", day,
                wall + 10 * 60_000L, mono + 10 * 60_000L, mono + 10 * 60_000L);
        state.setSessionForeground(true, "video.app", day,
                wall + 20 * 60_000L, mono + 20 * 60_000L, mono + 20 * 60_000L);
        state.finishSession(day, wall + 30 * 60_000L, mono + 30 * 60_000L, mono + 30 * 60_000L);
        assertEquals(20L * 60_000L, store().snapshot(day).childUsedMs);
    }

    @Test public void requestedTargetIsNotTreatedAsObservedUntilUsageEventsReportIt() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long wall = wall(day, 12, 0), mono = 2_500_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                wall, mono, mono, true));
        assertFalse(store().snapshot(day).sessionPackageObserved);

        markAppForeground(state, day, wall, mono);
        FamilyProfileStore.Snapshot resumed = store().snapshot(day);
        assertTrue(resumed.sessionPackageObserved);
        assertEquals("test.tv.app", resumed.sessionPackage);
    }

    @Test public void activeUseIsSplitAtLisbonCivilMidnight() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = day.atTime(23, 59, 30).atZone(LISBON).toInstant().toEpochMilli();
        long startMono = 3_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                startWall, startMono, startMono, true));
        markAppForeground(state, day, startWall, startMono);
        state.finishSession(day.plusDays(1), startWall + 60_000L,
                startMono + 60_000L, startMono + 60_000L);
        assertEquals(30_000L, store().snapshot(day).childUsedMs);
        assertEquals(30_000L, store().snapshot(day.plusDays(1)).childUsedMs);
    }

    @Test public void daylightSavingJumpDoesNotLookLikeManualClockChange() {
        LocalDate day = LocalDate.of(2026, 3, 29);
        long startWall = day.atTime(0, 59, 30).atZone(LISBON).toInstant().toEpochMilli();
        long startMono = 4_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                startWall, startMono, startMono, true));
        markAppForeground(state, day, startWall, startMono);
        FamilyProfileStore.UsageUpdate update = state.recordSession(day,
                startWall + 60_000L, startMono + 60_000L, startMono + 60_000L);
        assertTrue(update.saved);
        assertFalse(store().snapshot(day).clockUncertain);
        assertEquals(60_000L, store().snapshot(day).childUsedMs);
    }
}
