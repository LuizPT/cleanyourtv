package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class FamilyProfileStoreTest {
    private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
    private TimeZone originalZone;

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Before public void setUpLocalTestZone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(LISBON));
    }

    @After public void restoreLocalTestZone() {
        TimeZone.setDefault(originalZone);
    }

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

    @Test public void clockConfirmationCannotDiscardAnUnreconciledSession() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 12, 0);
        long startElapsed = 500_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                startWall, startElapsed, startElapsed, true));
        markAppForeground(state, day, startWall, startElapsed);
        assertFalse(state.observeClock(startWall + 3 * 60 * 60_000L,
                startElapsed + 60_000L, day));

        assertFalse("An Adult cannot clear clock uncertainty before the pending session is reconciled",
                state.confirmClock(startWall + 3 * 60 * 60_000L,
                        startElapsed + 60_000L, day));
        FamilyProfileStore.Snapshot snapshot = store().snapshot(day);
        assertTrue(snapshot.clockUncertain);
        assertTrue(snapshot.sessionPending);
        assertEquals(0L, snapshot.childUsedMs);
    }

    @Test public void unexpectedForwardClockJumpPausesFurtherUse() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long firstWall = wall(day, 12, 0);
        FamilyProfileStore state = store();
        assertTrue(state.observeClock(firstWall, 500_000L, day));
        assertFalse(state.observeClock(firstWall + 3 * 60 * 60_000L, 560_000L, day));
        assertFalse("A later stable sample must not erase an actual clock adjustment",
                state.reconcileForLauncher(firstWall + 3 * 60 * 60_000L + 60_000L,
                        620_000L, 620_000L, day, "Europe/Lisbon"));
        assertTrue(store().snapshot(day).clockUncertain);
    }

    @Test public void elapsedRealtimeResetRetainsSessionForAuthenticatedRecovery() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 12, 0);
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                startWall, 900_000L, 900_000L, true));
        markAppForeground(state, day, startWall, 900_000L);

        FamilyProfileStore.UsageUpdate update = state.recordSession(day,
                startWall + 60_000L, 1_000L, 1_000L);
        assertTrue(update.saved);
        FamilyProfileStore.Snapshot recovered = store().snapshot(day);
        assertTrue("An ambiguous reboot interval must remain available for review", recovered.sessionPending);
        assertTrue(recovered.clockUncertain);
        assertTrue(recovered.mustReturnHome);
        assertEquals(0L, recovered.childUsedMs);

        assertTrue("Adult recovery may end, but must not charge, the unknown interval",
                store().recoverInterruptedSession(startWall + 60_000L, 1_000L, 1_000L,
                        day, "Europe/Lisbon", true));
        FamilyProfileStore.Snapshot adultRecovered = store().snapshot(day);
        assertFalse(adultRecovered.sessionPending);
        assertTrue(adultRecovered.clockUncertain);
        assertEquals(0L, adultRecovered.childUsedMs);
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

    @Test public void safeInterruptedSessionReconcilesWithoutAdultAndPreservesPriorUsage() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 12, 0);
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.XBOX, "vendor.tv.input", day,
                startWall, 900_000L, 900_000L, true));
        markAppForeground(state, day, startWall, 900_000L);

        FamilyProfileStore reopened = store();
        assertTrue(reopened.recoverInterruptedSession(startWall + 20 * 60_000L,
                900_000L + 20 * 60_000L, 900_000L + 2 * 60_000L,
                day, "Europe/Lisbon", false));
        FamilyProfileStore.Snapshot snapshot = reopened.snapshot(day);
        assertFalse(snapshot.sessionPending);
        assertFalse(snapshot.clockUncertain);
        assertEquals("Only awake uptime may be charged", 2 * FamilyTimePolicy.MINUTE_MS, snapshot.xboxUsedMs);
        assertTrue(snapshot.mustReturnHome);
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

    @Test public void monitorTicksAndAdultExtensionsCannotOverwriteEachOther() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 24);
        long startWall = wall(day, 12, 0), startElapsed = 8_000_000L;
        FamilyProfileStore monitorStore = store();
        FamilyProfileStore adultStore = store();
        assertTrue(monitorStore.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app",
                day, startWall, startElapsed, startElapsed, true));
        markAppForeground(monitorStore, day, startWall, startElapsed);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread monitor = new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                for (int i = 1; i <= 25; i++) {
                    long delta = i * 1_000L;
                    if (!monitorStore.recordSession(day, startWall + delta,
                            startElapsed + delta, startElapsed + delta).saved) {
                        throw new AssertionError("monitor tick was not saved");
                    }
                }
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { finished.countDown(); }
        });
        Thread adult = new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                for (int i = 0; i < 25; i++) {
                    if (!adultStore.addExtension(FamilyProfileStore.Profile.CHILD_TV, day, 1)) {
                        throw new AssertionError("adult extension was not saved");
                    }
                }
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { finished.countDown(); }
        });
        monitor.start();
        adult.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue("concurrent storage updates completed", finished.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError("concurrent storage update failed", failure.get());

        FamilyProfileStore.Snapshot snapshot = store().snapshot(day);
        assertEquals(25_000L, snapshot.childUsedMs);
        assertEquals(25L * FamilyTimePolicy.MINUTE_MS, snapshot.childExtensionMs);
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

    @Test public void longScreenOffAcrossNextDayPreservesBalancesAndDoesNotChargeSuspension() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 22, 57);
        long elapsed = 5_000_000L;
        long uptime = elapsed;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                startWall, elapsed, uptime, true));
        markAppForeground(state, day, startWall, elapsed);

        long screenOffWall = startWall + 3 * FamilyTimePolicy.MINUTE_MS;
        long screenOffElapsed = elapsed + 3 * FamilyTimePolicy.MINUTE_MS;
        long screenOffUptime = uptime + 3 * FamilyTimePolicy.MINUTE_MS;
        assertTrue(state.setSessionScreenOn(false, day, screenOffWall,
                screenOffElapsed, screenOffUptime).saved);
        assertTrue(state.markReturnHomeOnWake());

        LocalDate resumedDay = day.plusDays(1);
        long resumedWall = wall(resumedDay, 14, 0);
        long suspendedElapsed = screenOffElapsed + resumedWall - screenOffWall;
        // uptimeMillis excludes deep sleep, so no uptime advances during the long suspended interval.
        long resumedUptime = screenOffUptime;
        assertTrue(state.setSessionScreenOn(true, resumedDay, resumedWall,
                suspendedElapsed, resumedUptime).saved);
        assertTrue(state.setSessionForeground(false, "com.gathin.tvhome", resumedDay,
                resumedWall, suspendedElapsed, resumedUptime).saved);
        assertTrue(state.finishSession(resumedDay, resumedWall, suspendedElapsed, resumedUptime).saved);

        FamilyProfileStore.Snapshot previousDay = store().snapshot(day);
        FamilyProfileStore.Snapshot currentDay = store().snapshot(resumedDay);
        assertEquals(3 * FamilyTimePolicy.MINUTE_MS, previousDay.childUsedMs);
        assertEquals(0L, currentDay.childUsedMs);
        assertEquals(0L, currentDay.xboxUsedMs);
        assertEquals(0L, currentDay.childExtensionMs);
        assertEquals(0L, currentDay.xboxExtensionMs);
        assertEquals(60L * FamilyTimePolicy.MINUTE_MS, previousDay.childSchedule.budgetMs);
        assertFalse(currentDay.clockUncertain);
        assertTrue(currentDay.mustReturnHome);
        assertFalse(currentDay.sessionPending);
    }

    @Test public void overnightResumeWithoutScreenEventsReconcilesOnlyAwakeTimeToNewDay() {
        LocalDate previousDay = LocalDate.of(2026, 10, 23);
        LocalDate resumedDay = previousDay.plusDays(1);
        long startWall = wall(previousDay, 22, 57);
        long startElapsed = 5_000_000L;
        long startUptime = 4_200_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", previousDay,
                startWall, startElapsed, startUptime, true));
        assertTrue(state.setSessionForeground(true, "video.app", previousDay,
                startWall, startElapsed, startUptime).saved);

        long lastTickWall = wall(previousDay, 23, 0);
        long lastTickElapsed = startElapsed + 3 * FamilyTimePolicy.MINUTE_MS;
        long lastTickUptime = startUptime + 3 * FamilyTimePolicy.MINUTE_MS;
        assertTrue(state.recordSession(previousDay, lastTickWall, lastTickElapsed, lastTickUptime).saved);

        long resumedWall = wall(resumedDay, 14, 0);
        long resumedElapsed = lastTickElapsed + resumedWall - lastTickWall;
        long resumedUptime = lastTickUptime + 2 * FamilyTimePolicy.MINUTE_MS;
        assertTrue("A missed SCREEN_OFF/SCREEN_ON pair is recoverable on the same boot",
                state.reconcileForLauncher(resumedWall, resumedElapsed, resumedUptime,
                        resumedDay, "Europe/Lisbon"));

        FamilyProfileStore.Snapshot previous = store().snapshot(previousDay);
        FamilyProfileStore.Snapshot current = store().snapshot(resumedDay);
        assertEquals("Earlier usage is preserved", 3 * FamilyTimePolicy.MINUTE_MS, previous.childUsedMs);
        assertEquals("Deep sleep is excluded and awake tail belongs to the resumed day",
                2 * FamilyTimePolicy.MINUTE_MS, current.childUsedMs);
        assertEquals(0L, current.xboxUsedMs);
        assertEquals(0L, current.childExtensionMs);
        assertFalse(current.clockUncertain);
        assertFalse(current.sessionPending);
        assertTrue(current.mustReturnHome);
    }

    @Test public void missingScreenOnEventDoesNotChargeSuspensionOrLeaveSessionPending() {
        LocalDate previousDay = LocalDate.of(2026, 10, 23);
        LocalDate resumedDay = previousDay.plusDays(1);
        long startWall = wall(previousDay, 22, 57);
        long startElapsed = 5_000_000L;
        long startUptime = 5_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.XBOX, "vendor.tv.input", previousDay,
                startWall, startElapsed, startUptime, true));
        assertTrue(state.setSessionForeground(true, "vendor.tv.input", previousDay,
                startWall, startElapsed, startUptime).saved);
        long offWall = wall(previousDay, 23, 0);
        long offElapsed = startElapsed + 3 * FamilyTimePolicy.MINUTE_MS;
        long offUptime = startUptime + 3 * FamilyTimePolicy.MINUTE_MS;
        assertTrue(state.setSessionScreenOn(false, previousDay, offWall, offElapsed, offUptime).saved);

        long resumedWall = wall(resumedDay, 14, 0);
        long resumedElapsed = offElapsed + resumedWall - offWall;
        assertTrue(state.reconcileForLauncher(resumedWall, resumedElapsed, offUptime,
                resumedDay, "Europe/Lisbon"));
        FamilyProfileStore.Snapshot current = store().snapshot(resumedDay);
        assertEquals(0L, current.xboxUsedMs);
        assertFalse(current.clockUncertain);
        assertFalse(current.sessionPending);
    }

    @Test public void ambiguousLegacyV22ClockFlagIsNotAutomaticallyCleared() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long wallNow = wall(day, 14, 0);
        long elapsedNow = 50_000_000L;
        File file = new File(folder.getRoot(), "family-profiles.properties");
        String oldV22State = "family_enabled=true\n"
                + "clock_uncertain=true\n"
                + "must_return_home=true\n"
                + "last_wall_ms=" + (wallNow - 60 * 60_000L) + "\n"
                + "last_elapsed_ms=" + (elapsedNow - 60 * 60_000L) + "\n"
                + "last_day=" + day + "\n";
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(oldV22State.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        FamilyProfileStore legacy = new FamilyProfileStore(file);
        assertFalse(legacy.hasStorageError());
        assertFalse("An old flag without a recorded cause is ambiguous and stays fail-closed",
                legacy.reconcileForLauncher(wallNow, elapsedNow, elapsedNow - 5_000_000L,
                        day, "Europe/Lisbon"));
        assertTrue(store().snapshot(day).clockUncertain);
        assertEquals(0L, store().snapshot(day).childUsedMs);
    }

    @Test public void timezoneChangeMayMoveLocalDateBackwardWithoutClockUncertainty() {
        LocalDate lisbonDay = LocalDate.of(2026, 10, 23);
        long firstWall = lisbonDay.atTime(0, 30).atZone(ZoneId.of("Europe/Lisbon"))
                .toInstant().toEpochMilli();
        long firstElapsed = 10_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.observeClock(firstWall, firstElapsed, lisbonDay, "Europe/Lisbon"));
        ZoneId newZone = ZoneId.of("America/New_York");
        long nextWall = firstWall + 5 * FamilyTimePolicy.MINUTE_MS;
        LocalDate localDayAfterZoneChange = java.time.Instant.ofEpochMilli(nextWall)
                .atZone(newZone).toLocalDate();
        assertTrue(state.observeClock(nextWall, firstElapsed + 5 * FamilyTimePolicy.MINUTE_MS,
                localDayAfterZoneChange, newZone.getId()));
        assertFalse(store().snapshot(localDayAfterZoneChange).clockUncertain);
    }

    @Test public void dailyUsageSplitsUsingCurrentSystemTimeZoneWithoutDuplicatingMinutes() {
        TimeZone originalZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"));
            LocalDate lisbonDay = LocalDate.of(2026, 1, 2);
            long startWall = lisbonDay.atTime(7, 50).atZone(LISBON).toInstant().toEpochMilli();
            long startElapsed = 6_000_000L;
            FamilyProfileStore state = new FamilyProfileStore(
                    new File(folder.getRoot(), "family-profiles.properties"));
            assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", lisbonDay,
                    startWall, startElapsed, startElapsed, true));
            markAppForeground(state, lisbonDay, startWall, startElapsed);

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            long endWall = startWall + 20 * FamilyTimePolicy.MINUTE_MS;
            LocalDate endDay = java.time.Instant.ofEpochMilli(endWall)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            assertEquals(LocalDate.of(2026, 1, 2), endDay);
            assertTrue(state.finishSession(endDay, endWall,
                    startElapsed + 20 * FamilyTimePolicy.MINUTE_MS,
                    startElapsed + 20 * FamilyTimePolicy.MINUTE_MS).saved);

            FamilyProfileStore persisted = new FamilyProfileStore(
                    new File(folder.getRoot(), "family-profiles.properties"));
            assertEquals(10 * FamilyTimePolicy.MINUTE_MS,
                    persisted.snapshot(LocalDate.of(2026, 1, 1)).childUsedMs);
            assertEquals(10 * FamilyTimePolicy.MINUTE_MS,
                    persisted.snapshot(LocalDate.of(2026, 1, 2)).childUsedMs);
            assertEquals(20 * FamilyTimePolicy.MINUTE_MS,
                    persisted.snapshot(LocalDate.of(2026, 1, 1)).childUsedMs
                            + persisted.snapshot(LocalDate.of(2026, 1, 2)).childUsedMs);
        } finally {
            TimeZone.setDefault(originalZone);
        }
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

    @Test public void diagnosticSessionPersistsItsOwnTimerWithoutChangingDailyBalances() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 12, 0);
        long startMono = 5_000_000L;
        FamilyProfileStore state = store();
        assertFalse(state.startDiagnosticSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", 3,
                day, startWall, startMono, startMono, true));
        assertFalse(state.startDiagnosticSession(FamilyProfileStore.Profile.ADULT, "video.app", 2,
                day, startWall, startMono, startMono, true));
        assertTrue(state.startDiagnosticSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", 2,
                day, startWall, startMono, startMono, true));
        markAppForeground(state, day, startWall, startMono);

        FamilyProfileStore.UsageUpdate halfway = store().recordSession(day,
                startWall + FamilyTimePolicy.MINUTE_MS, startMono + FamilyTimePolicy.MINUTE_MS,
                startMono + FamilyTimePolicy.MINUTE_MS);
        assertTrue(halfway.saved);
        assertTrue(halfway.diagnostic);
        assertEquals(FamilyTimePolicy.MINUTE_MS, halfway.remainingMs);
        assertEquals(Collections.singletonList(1), halfway.warningsMinutes);

        FamilyProfileStore.Snapshot reopened = store().snapshot(day);
        assertTrue(reopened.sessionDiagnostic);
        assertEquals(FamilyTimePolicy.MINUTE_MS, reopened.diagnosticUsedMs);
        assertEquals(FamilyTimePolicy.MINUTE_MS, reopened.diagnosticRemainingMs());
        assertEquals("diagnostics never consume the daily quota", 0L, reopened.childUsedMs);

        FamilyProfileStore.UsageUpdate expired = store().recordSession(day,
                startWall + 2 * FamilyTimePolicy.MINUTE_MS, startMono + 2 * FamilyTimePolicy.MINUTE_MS,
                startMono + 2 * FamilyTimePolicy.MINUTE_MS);
        assertTrue(expired.saved);
        assertEquals(0L, expired.remainingMs);
        assertEquals(0L, store().snapshot(day).childUsedMs);
        assertTrue(store().finishSession(day, startWall + 2 * FamilyTimePolicy.MINUTE_MS,
                startMono + 2 * FamilyTimePolicy.MINUTE_MS, startMono + 2 * FamilyTimePolicy.MINUTE_MS).saved);
        FamilyProfileStore.Snapshot finished = store().snapshot(day);
        assertFalse(finished.sessionPending);
        assertFalse(finished.sessionDiagnostic);
        assertEquals(0L, finished.childUsedMs);
    }

    @Test public void weekdaySchedulesPersistAndRejectInvalidWindows() {
        LocalDate saturday = LocalDate.of(2026, 10, 24);
        FamilyProfileStore state = store();
        assertEquals(60L * FamilyTimePolicy.MINUTE_MS,
                state.snapshot(saturday).remainingMs(FamilyProfileStore.Profile.CHILD_TV, saturday));
        assertFalse(state.setSchedule(FamilyProfileStore.Profile.CHILD_TV, DayOfWeek.SATURDAY,
                22 * 60, 7 * 60, 90));
        assertFalse(state.setSchedule(FamilyProfileStore.Profile.ADULT, DayOfWeek.SATURDAY,
                7 * 60, 22 * 60, 90));
        assertTrue(state.setSchedule(FamilyProfileStore.Profile.CHILD_TV, DayOfWeek.SATURDAY,
                8 * 60, 21 * 60, 90));

        FamilyProfileStore reopened = store();
        FamilyTimePolicy.Schedule schedule = reopened.snapshot(saturday).childSchedule;
        assertEquals(8 * 60, schedule.startMinute);
        assertEquals(21 * 60, schedule.endMinute);
        assertEquals(90L * FamilyTimePolicy.MINUTE_MS,
                reopened.snapshot(saturday).remainingMs(FamilyProfileStore.Profile.CHILD_TV, saturday));
        assertTrue(reopened.setSchedule(FamilyProfileStore.Profile.CHILD_TV, DayOfWeek.SATURDAY,
                7 * 60, 22 * 60, 60));
        assertEquals(7 * 60, store().snapshot(saturday).childSchedule.startMinute);
    }

    @Test public void interruptedDiagnosticIsClearedOnRecoveryWithoutChargingDailyBalance() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long wall = wall(day, 12, 0), elapsed = 7_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startDiagnosticSession(FamilyProfileStore.Profile.XBOX, "vendor.tv.input", 5,
                day, wall, elapsed, elapsed, true));
        markAppForeground(state, day, wall, elapsed);
        assertTrue(store().snapshot(day).sessionDiagnostic);

        FamilyProfileStore reopened = store();
        assertTrue(reopened.recoverInterruptedSession(wall + 3 * FamilyTimePolicy.MINUTE_MS,
                elapsed + 3 * FamilyTimePolicy.MINUTE_MS, elapsed + 3 * FamilyTimePolicy.MINUTE_MS,
                day, "Europe/Lisbon", false));
        FamilyProfileStore.Snapshot recovered = store().snapshot(day);
        assertFalse(recovered.sessionPending);
        assertFalse(recovered.sessionDiagnostic);
        assertFalse(recovered.clockUncertain);
        assertTrue(recovered.mustReturnHome);
        assertEquals(0L, recovered.xboxUsedMs);
        assertEquals(0L, recovered.xboxExtensionMs);
    }

    @Test public void dailyUsageHistoryIsLocalSeparatedAndIncludesEmptyDays() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        long startWall = wall(day, 12, 0), startMono = 6_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                startWall, startMono, startMono, true));
        markAppForeground(state, day, startWall, startMono);
        state.finishSession(day, startWall + 12 * FamilyTimePolicy.MINUTE_MS,
                startMono + 12 * FamilyTimePolicy.MINUTE_MS, startMono + 12 * FamilyTimePolicy.MINUTE_MS);
        assertEquals(Long.valueOf(12 * FamilyTimePolicy.MINUTE_MS),
                store().usageHistory(FamilyProfileStore.Profile.CHILD_TV, day, 7).get(day));
        assertEquals(Long.valueOf(0L), store().usageHistory(FamilyProfileStore.Profile.XBOX, day, 7).get(day));
        assertEquals(7, store().usageHistory(FamilyProfileStore.Profile.CHILD_TV, day, 7).size());
    }
}
