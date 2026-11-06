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
import java.io.FileInputStream;
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
import java.util.Properties;
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

    private File storeFile() {
        return new File(folder.getRoot(), "family-profiles.properties");
    }

    private void writeProperties(Properties properties) throws Exception {
        try (FileOutputStream output = new FileOutputStream(storeFile())) {
            properties.store(output, "test state");
        }
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

        assertTrue("Adult recovery may close an ambiguous session only after reserving its possible use",
                store().recoverInterruptedSession(startWall + 60_000L, 1_000L, 1_000L,
                        day, "Europe/Lisbon", true));
        FamilyProfileStore.Snapshot adultRecovered = store().snapshot(day);
        assertFalse(adultRecovered.sessionPending);
        assertTrue(adultRecovered.clockUncertain);
        assertEquals("The possible use cannot create free minutes after a reboot",
                adultRecovered.childSchedule.budgetMs, adultRecovered.childUsedMs);
        assertEquals(0L, adultRecovered.remainingMs(FamilyProfileStore.Profile.CHILD_TV, day));
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

    @Test public void legacyV22ClockFlagStaysClosedWithoutTrustedAutomaticTime() throws Exception {
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
        assertEquals(FamilyProfileStore.ClockIssue.LEGACY_V22, store().snapshot(day).clockIssue);
        assertEquals(0L, store().snapshot(day).childUsedMs);
    }

    @Test public void v22StateMigratesAcrossV24AndRecoversAfterVerifiedDayChange() throws Exception {
        LocalDate legacyDay = LocalDate.of(2026, 11, 2);
        LocalDate v24Day = legacyDay.plusDays(2);
        long v22Wall = wall(legacyDay, 23, 30);
        long v22Elapsed = 80_000_000L;
        Properties v22 = new Properties();
        v22.setProperty("family_enabled", "true");
        v22.setProperty("clock_uncertain", "true");
        v22.setProperty("must_return_home", "true");
        v22.setProperty("last_wall_ms", Long.toString(v22Wall));
        v22.setProperty("last_elapsed_ms", Long.toString(v22Elapsed));
        v22.setProperty("last_day", legacyDay.toString());
        v22.setProperty("used.child_tv." + legacyDay, Long.toString(27 * FamilyTimePolicy.MINUTE_MS));
        v22.setProperty("used.xbox." + legacyDay, Long.toString(8 * FamilyTimePolicy.MINUTE_MS));
        v22.setProperty("child_apps_initialized", "true");
        v22.setProperty("child_apps", "video.app@123");
        writeProperties(v22);

        FamilyProfileStore afterV24 = store();
        long v24Wall = wall(v24Day, 12, 0);
        long v24Elapsed = v22Elapsed + v24Wall - v22Wall;
        assertFalse("A v24 read migrates the legacy marker but stays fail-closed until trusted recovery",
                afterV24.reconcileForLauncher(v24Wall, v24Elapsed, v24Elapsed - 2_000_000L,
                        v24Day, "Europe/Lisbon"));
        assertEquals(FamilyProfileStore.ClockIssue.LEGACY_V22, store().snapshot(v24Day).clockIssue);

        FamilyProfileStore updated = store();
        assertTrue("A stable automatic clock and the already verified later day clear the old marker",
                updated.observeClockAutomatically(v24Wall + 20_000L, v24Elapsed + 20_000L,
                        v24Day, "Europe/Lisbon", true));
        FamilyProfileStore.Snapshot migrated = store().snapshot(v24Day);
        assertFalse(migrated.clockUncertain);
        assertEquals(27 * FamilyTimePolicy.MINUTE_MS,
                store().usageHistory(FamilyProfileStore.Profile.CHILD_TV, legacyDay, 1).get(legacyDay).longValue());
        assertEquals(8 * FamilyTimePolicy.MINUTE_MS,
                store().usageHistory(FamilyProfileStore.Profile.XBOX, legacyDay, 1).get(legacyDay).longValue());
        assertEquals(Collections.singleton("video.app"), store().childAllowedApps());
        assertEquals(0L, migrated.childUsedMs);
        assertTrue(migrated.enabled);
    }

    @Test public void legacyV22MarkerWaitsUntilDayChangesWhenItWasFirstSeenToday() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 4);
        long wallNow = wall(day, 14, 0);
        long elapsedNow = 90_000_000L;
        Properties old = new Properties();
        old.setProperty("family_enabled", "true");
        old.setProperty("clock_uncertain", "true");
        old.setProperty("last_wall_ms", Long.toString(wallNow - 60_000L));
        old.setProperty("last_elapsed_ms", Long.toString(elapsedNow - 60_000L));
        old.setProperty("last_day", day.toString());
        old.setProperty("used.child_tv." + day, Long.toString(19 * FamilyTimePolicy.MINUTE_MS));
        writeProperties(old);

        FamilyProfileStore state = store();
        assertFalse(state.observeClockAutomatically(wallNow, elapsedNow, day, "Europe/Lisbon", false));
        assertEquals(FamilyProfileStore.ClockIssue.LEGACY_V22, store().snapshot(day).clockIssue);
        assertFalse(state.observeClockAutomatically(wallNow + 20_000L, elapsedNow + 20_000L,
                day, "Europe/Lisbon", false));
        long nextDayWall = wall(day.plusDays(1), 0, 10);
        long nextDayElapsed = elapsedNow + 20_000L + nextDayWall - (wallNow + 20_000L);
        assertTrue("A verified new local day ends the one-day legacy quarantine",
                state.observeClockAutomatically(nextDayWall, nextDayElapsed,
                        day.plusDays(1), "Europe/Lisbon", false));
        assertEquals(19 * FamilyTimePolicy.MINUTE_MS,
                store().usageHistory(FamilyProfileStore.Profile.CHILD_TV, day, 1).get(day).longValue());
        assertEquals(0L, store().snapshot(day.plusDays(1)).childUsedMs);
    }

    @Test public void manualDateJumpDuringLegacyMigrationReservesBothProfilesBeforeRecovery() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 4);
        long firstWall = wall(day, 12, 0);
        long firstElapsed = 110_000_000L;
        Properties old = new Properties();
        old.setProperty("family_enabled", "true");
        old.setProperty("clock_uncertain", "true");
        old.setProperty("last_wall_ms", Long.toString(firstWall - 60_000L));
        old.setProperty("last_elapsed_ms", Long.toString(firstElapsed - 60_000L));
        old.setProperty("last_day", day.toString());
        writeProperties(old);

        FamilyProfileStore state = store();
        assertFalse(state.observeClockAutomatically(firstWall, firstElapsed, day, "Europe/Lisbon", false));
        assertFalse(state.observeClockAutomatically(firstWall + 20_000L,
                firstElapsed + 20_000L, day, "Europe/Lisbon", false));
        LocalDate jumpedDay = day.plusDays(1L);
        long jumpWall = wall(jumpedDay, 12, 0);
        long jumpElapsed = firstElapsed + 40_000L;
        assertFalse("A manual date jump cannot clear the legacy marker immediately",
                state.observeClockAutomatically(jumpWall, jumpElapsed, jumpedDay, "Europe/Lisbon", false));
        assertTrue("If the parent cannot distinguish the old date, recovery spends the current-day quota",
                state.observeClockAutomatically(jumpWall + 20_000L, jumpElapsed + 20_000L,
                        jumpedDay, "Europe/Lisbon", false));
        FamilyProfileStore.Snapshot recovered = store().snapshot(jumpedDay);
        assertFalse(recovered.clockUncertain);
        assertEquals(recovered.childSchedule.budgetMs, recovered.childUsedMs);
        assertEquals(recovered.xboxSchedule.budgetMs, recovered.xboxUsedMs);
    }

    @Test public void stableAutomaticTimeClearsKnownClockAdjustmentWithoutChangingUsage() {
        LocalDate day = LocalDate.of(2026, 11, 4);
        long firstWall = wall(day, 12, 0);
        long firstElapsed = 100_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.setEnabled(true));
        assertTrue(state.observeClockAutomatically(firstWall, firstElapsed, day, "Europe/Lisbon", true));
        assertFalse(state.observeClockAutomatically(firstWall + 60 * 60_000L,
                firstElapsed + 60_000L, day, "Europe/Lisbon", true));
        assertEquals(FamilyProfileStore.ClockIssue.TIME_ADJUSTED, store().snapshot(day).clockIssue);
        assertTrue(state.observeClockAutomatically(firstWall + 60 * 60_000L + 16_000L,
                firstElapsed + 76_000L, day, "Europe/Lisbon", true));
        assertFalse(store().snapshot(day).clockUncertain);
        assertEquals(0L, store().snapshot(day).childUsedMs);
    }

    @Test public void automaticTimeSyncAcrossMidnightClearsWithoutAdultAndPreservesUsage() throws Exception {
        LocalDate previousDay = LocalDate.of(2026, 11, 5);
        LocalDate synchronizedDay = previousDay.plusDays(1);
        long firstWall = wall(previousDay, 23, 50);
        long firstElapsed = 150_000_000L;
        Properties persisted = new Properties();
        persisted.setProperty("family_enabled", "true");
        persisted.setProperty("used.child_tv." + previousDay,
                Long.toString(10 * FamilyTimePolicy.MINUTE_MS));
        writeProperties(persisted);

        FamilyProfileStore state = store();
        assertTrue(state.observeClockAutomatically(firstWall, firstElapsed, previousDay,
                "Europe/Lisbon", true));

        long synchronizedWall = wall(synchronizedDay, 0, 15);
        long synchronizedElapsed = firstElapsed + FamilyTimePolicy.MINUTE_MS;
        assertFalse("A wall-clock correction crossing midnight is held for stable samples, not Adult authentication",
                state.observeClockAutomatically(synchronizedWall, synchronizedElapsed, synchronizedDay,
                        "Europe/Lisbon", true));
        assertEquals(FamilyProfileStore.ClockIssue.TIME_ADJUSTED,
                store().snapshot(synchronizedDay).clockIssue);

        assertTrue("Trusted automatic time clears the transient correction after 15 stable seconds",
                state.observeClockAutomatically(synchronizedWall + 16_000L,
                        synchronizedElapsed + 16_000L, synchronizedDay, "Europe/Lisbon", true));
        assertFalse(store().snapshot(synchronizedDay).clockUncertain);
        assertEquals("Previously recorded usage remains attached to its original civil day",
                10 * FamilyTimePolicy.MINUTE_MS, store().snapshot(previousDay).childUsedMs);
    }

    @Test public void deliberateClockChangeStaysPausedWhenAutomaticTimeIsOff() {
        LocalDate day = LocalDate.of(2026, 11, 4);
        long wallNow = wall(day, 12, 0);
        long elapsed = 200_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.observeClockAutomatically(wallNow, elapsed, day, "Europe/Lisbon", false));
        assertFalse(state.observeClockAutomatically(wallNow + 60 * 60_000L,
                elapsed + 60_000L, day, "Europe/Lisbon", false));
        assertFalse(state.observeClockAutomatically(wallNow + 60 * 60_000L + 60_000L,
                elapsed + 120_000L, day, "Europe/Lisbon", false));
        assertTrue(store().snapshot(day).clockUncertain);
        assertEquals(FamilyProfileStore.ClockIssue.TIME_ADJUSTED, store().snapshot(day).clockIssue);
    }

    @Test public void clockRecoveryDoesNotResetAnAlreadyReachedDailyLimit() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 4);
        long wallNow = wall(day, 19, 0);
        long elapsed = 260_000_000L;
        FamilyProfileStore defaults = store();
        long limit = defaults.snapshot(day).childSchedule.budgetMs;
        Properties persisted = new Properties();
        persisted.setProperty("family_enabled", "true");
        persisted.setProperty("clock_uncertain", "true");
        persisted.setProperty("clock_uncertain_reason", "TIME_ADJUSTED");
        persisted.setProperty("clock_issue_day", day.toString());
        persisted.setProperty("clock_issue_elapsed_ms", Long.toString(elapsed - 30_000L));
        persisted.setProperty("last_wall_ms", Long.toString(wallNow));
        persisted.setProperty("last_elapsed_ms", Long.toString(elapsed));
        persisted.setProperty("last_day", day.toString());
        persisted.setProperty("used.child_tv." + day, Long.toString(limit));
        writeProperties(persisted);

        assertTrue(store().observeClockAutomatically(wallNow + 20_000L, elapsed + 20_000L,
                day, "Europe/Lisbon", true));
        FamilyProfileStore.Snapshot recovered = store().snapshot(day);
        assertFalse(recovered.clockUncertain);
        assertEquals(limit, recovered.childUsedMs);
        assertEquals(0L, recovered.remainingMs(FamilyProfileStore.Profile.CHILD_TV, day));
    }

    @Test public void restartWithoutPendingSessionRecoversAfterStableTrustedClock() {
        LocalDate day = LocalDate.of(2026, 11, 4);
        long wallNow = wall(day, 12, 0);
        FamilyProfileStore state = store();
        assertTrue(state.observeClockAutomatically(wallNow, 500_000L, day, "Europe/Lisbon", true));
        assertFalse(state.observeClockAutomatically(wallNow + 60_000L, 2_000L,
                day, "Europe/Lisbon", true));
        assertEquals(FamilyProfileStore.ClockIssue.DEVICE_RESTARTED, store().snapshot(day).clockIssue);
        assertTrue(state.observeClockAutomatically(wallNow + 76_000L, 18_000L,
                day, "Europe/Lisbon", true));
        assertFalse(store().snapshot(day).clockUncertain);
    }

    @Test public void seventyTwoHourResumeReconcilesPendingSessionAndClearsStickyMarker() throws Exception {
        LocalDate startDay = LocalDate.of(2026, 11, 1);
        long startWall = wall(startDay, 20, 0);
        long startElapsed = 4_000_000L;
        long startUptime = 3_500_000L;
        FamilyProfileStore state = store();
        assertTrue(state.setEnabled(true));
        assertTrue(state.initializeChildApps(Collections.singleton("video.app")));
        assertTrue(state.setChildAppAllowed("video.app", true));
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", startDay,
                startWall, startElapsed, startUptime, true));
        assertTrue(state.setSessionForeground(true, "video.app", startDay,
                startWall, startElapsed, startUptime).saved);
        assertTrue(state.recordSession(startDay, startWall + 10 * FamilyTimePolicy.MINUTE_MS,
                startElapsed + 10 * FamilyTimePolicy.MINUTE_MS,
                startUptime + 10 * FamilyTimePolicy.MINUTE_MS).saved);

        Properties persisted = new Properties();
        try (FileInputStream input = new FileInputStream(storeFile())) { persisted.load(input); }
        persisted.setProperty("clock_uncertain", "true");
        persisted.setProperty("clock_uncertain_reason", "TIME_ADJUSTED");
        persisted.setProperty("clock_issue_day", startDay.toString());
        persisted.setProperty("clock_issue_elapsed_ms", Long.toString(startElapsed + 10 * FamilyTimePolicy.MINUTE_MS));
        writeProperties(persisted);

        long suspendMs = 72L * 60L * 60_000L;
        long awakeAfterWakeMs = 6L * FamilyTimePolicy.MINUTE_MS;
        LocalDate returnDay = startDay.plusDays(3);
        long returnWall = startWall + 10 * FamilyTimePolicy.MINUTE_MS + suspendMs;
        long returnElapsed = startElapsed + 10 * FamilyTimePolicy.MINUTE_MS + suspendMs;
        long returnUptime = startUptime + 10 * FamilyTimePolicy.MINUTE_MS + awakeAfterWakeMs;
        assertTrue("Same-boot wall and elapsed time prove the 72-hour session interval",
                store().reconcileForLauncher(returnWall, returnElapsed, returnUptime,
                        returnDay, "Europe/Lisbon"));
        FamilyProfileStore.Snapshot returned = store().snapshot(returnDay);
        assertFalse(returned.clockUncertain);
        assertFalse(returned.sessionPending);
        assertEquals("The prior recorded ten minutes remain on their original day",
                10 * FamilyTimePolicy.MINUTE_MS,
                store().usageHistory(FamilyProfileStore.Profile.CHILD_TV, startDay, 1)
                        .get(startDay).longValue());
        assertEquals("Only awake uptime since the last tick is added after the long suspension",
                awakeAfterWakeMs, returned.childUsedMs);
        assertTrue("Profiles return according to today's independent schedule and remaining quota",
                returned.remainingMs(FamilyProfileStore.Profile.CHILD_TV, returnDay) > 0L);
        assertEquals(Collections.singleton("video.app"), store().childAllowedApps());
    }

    @Test public void seventyTwoHourXboxResumePreservesIndependentUsageAndQuota() throws Exception {
        LocalDate startDay = LocalDate.of(2026, 11, 1);
        long startWall = wall(startDay, 20, 0);
        long startElapsed = 8_000_000L;
        long startUptime = 7_500_000L;
        FamilyProfileStore state = store();
        assertTrue(state.setEnabled(true));
        assertTrue(state.startSession(FamilyProfileStore.Profile.XBOX, "hdmi.synthetic", startDay,
                startWall, startElapsed, startUptime, true));
        assertTrue(state.setSessionForeground(true, "hdmi.synthetic", startDay,
                startWall, startElapsed, startUptime).saved);
        assertTrue(state.recordSession(startDay, startWall + 10 * FamilyTimePolicy.MINUTE_MS,
                startElapsed + 10 * FamilyTimePolicy.MINUTE_MS,
                startUptime + 10 * FamilyTimePolicy.MINUTE_MS).saved);

        Properties persisted = new Properties();
        try (FileInputStream input = new FileInputStream(storeFile())) { persisted.load(input); }
        persisted.setProperty("clock_uncertain", "true");
        persisted.setProperty("clock_uncertain_reason", "TIME_ADJUSTED");
        persisted.setProperty("clock_issue_day", startDay.toString());
        persisted.setProperty("clock_issue_elapsed_ms", Long.toString(startElapsed + 10 * FamilyTimePolicy.MINUTE_MS));
        writeProperties(persisted);

        long suspendMs = 72L * 60L * 60_000L;
        long awakeAfterWakeMs = 4L * FamilyTimePolicy.MINUTE_MS;
        LocalDate returnDay = startDay.plusDays(3);
        long returnWall = startWall + 10 * FamilyTimePolicy.MINUTE_MS + suspendMs;
        long returnElapsed = startElapsed + 10 * FamilyTimePolicy.MINUTE_MS + suspendMs;
        long returnUptime = startUptime + 10 * FamilyTimePolicy.MINUTE_MS + awakeAfterWakeMs;
        assertTrue("Same-boot wall and elapsed time prove a normal 72-hour Xbox interval",
                store().reconcileForLauncher(returnWall, returnElapsed, returnUptime,
                        returnDay, "Europe/Lisbon"));

        FamilyProfileStore.Snapshot returned = store().snapshot(returnDay);
        assertFalse(returned.clockUncertain);
        assertFalse(returned.sessionPending);
        assertEquals(10 * FamilyTimePolicy.MINUTE_MS,
                store().usageHistory(FamilyProfileStore.Profile.XBOX, startDay, 1)
                        .get(startDay).longValue());
        assertEquals(awakeAfterWakeMs, returned.xboxUsedMs);
        assertEquals("Child's independent balance must not change during an Xbox session",
                0L, returned.childUsedMs);
        assertTrue(returned.remainingMs(FamilyProfileStore.Profile.XBOX, returnDay) > 0L);
    }

    @Test public void rebootWithPendingSessionRemainsAmbiguousAndPreservesRecordedUse() {
        LocalDate day = LocalDate.of(2026, 11, 4);
        long wallNow = wall(day, 12, 0);
        FamilyProfileStore state = store();
        assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", day,
                wallNow, 500_000L, 450_000L, true));
        assertTrue(state.setSessionForeground(true, "video.app", day,
                wallNow, 500_000L, 450_000L).saved);
        assertTrue(state.recordSession(day, wallNow + 5 * FamilyTimePolicy.MINUTE_MS,
                500_000L + 5 * FamilyTimePolicy.MINUTE_MS,
                450_000L + 5 * FamilyTimePolicy.MINUTE_MS).saved);
        long usedBefore = store().snapshot(day).childUsedMs;
        FamilyProfileStore.UsageUpdate afterReboot = state.recordSession(day,
                wallNow + 2 * FamilyTimePolicy.MINUTE_MS, 1_000L, 800L);
        assertTrue(afterReboot.saved);
        FamilyProfileStore.Snapshot snapshot = store().snapshot(day);
        assertTrue(snapshot.clockUncertain);
        assertTrue(snapshot.sessionPending);
        assertEquals(usedBefore, snapshot.childUsedMs);
        assertEquals(FamilyProfileStore.ClockIssue.SESSION_UNRECONCILED, snapshot.clockIssue);
    }

    @Test public void timezoneChangeWithinSameLocalDateDoesNotCreateClockUncertainty() {
        LocalDate lisbonDay = LocalDate.of(2026, 10, 23);
        long firstWall = lisbonDay.atTime(12, 30).atZone(ZoneId.of("Europe/Lisbon"))
                .toInstant().toEpochMilli();
        long firstElapsed = 10_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.observeClock(firstWall, firstElapsed, lisbonDay, "Europe/Lisbon"));
        ZoneId newZone = ZoneId.of("America/New_York");
        long nextWall = firstWall + 5 * FamilyTimePolicy.MINUTE_MS;
        LocalDate localDayAfterZoneChange = java.time.Instant.ofEpochMilli(nextWall)
                .atZone(newZone).toLocalDate();
        assertEquals("A zone change within the same local date keeps the existing daily bucket",
                lisbonDay, localDayAfterZoneChange);
        assertTrue(state.observeClock(nextWall, firstElapsed + 5 * FamilyTimePolicy.MINUTE_MS,
                localDayAfterZoneChange, newZone.getId()));
        assertFalse(store().snapshot(localDayAfterZoneChange).clockUncertain);
    }

    @Test public void automaticTimezoneSyncAcrossLocalDateDoesNotBlockPendingSession() {
        TimeZone originalZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(LISBON));
            LocalDate lisbonDay = LocalDate.of(2026, 10, 23);
            long firstWall = lisbonDay.atTime(0, 30).atZone(LISBON).toInstant().toEpochMilli();
            long firstElapsed = 10_000_000L;
            long firstUptime = 8_000_000L;
            FamilyProfileStore state = store();
            assertTrue(state.setEnabled(true));
            assertTrue(state.observeClock(firstWall, firstElapsed, lisbonDay, LISBON.getId()));
            assertTrue(state.startSession(FamilyProfileStore.Profile.CHILD_TV, "video.app", lisbonDay,
                    firstWall, firstElapsed, firstUptime, true));
            assertTrue(state.setSessionForeground(true, "video.app", lisbonDay, firstWall,
                    firstElapsed, firstUptime).saved);

            ZoneId newZone = ZoneId.of("America/New_York");
            TimeZone.setDefault(TimeZone.getTimeZone(newZone));
            long nextWall = firstWall + 5 * FamilyTimePolicy.MINUTE_MS;
            long nextElapsed = firstElapsed + 5 * FamilyTimePolicy.MINUTE_MS;
            LocalDate localDayAfterZoneChange = java.time.Instant.ofEpochMilli(nextWall)
                    .atZone(newZone).toLocalDate();

            assertTrue("Automatic Android time and zone synchronization should not mark a coherent session uncertain",
                    state.observeClockAutomatically(nextWall, nextElapsed, localDayAfterZoneChange,
                            newZone.getId(), true, true));
            FamilyProfileStore.UsageUpdate update = state.setSessionForeground(true, "video.app",
                    localDayAfterZoneChange, nextWall, nextElapsed,
                    firstUptime + 5 * FamilyTimePolicy.MINUTE_MS);
            FamilyProfileStore.Snapshot resumed = store().snapshot(localDayAfterZoneChange);
            assertTrue(update.saved);
            assertTrue(resumed.sessionPending);
            assertFalse(resumed.clockUncertain);
            assertEquals("The coherent elapsed interval is counted once across the zone transition",
                    5 * FamilyTimePolicy.MINUTE_MS,
                    store().snapshot(lisbonDay).childUsedMs + resumed.childUsedMs);
        } finally {
            TimeZone.setDefault(originalZone);
        }
    }

    @Test public void timezoneMarkerClearsAfterTrustedAutomaticZoneSyncStabilizes() {
        LocalDate lisbonDay = LocalDate.of(2026, 10, 23);
        long firstWall = lisbonDay.atTime(0, 30).atZone(LISBON).toInstant().toEpochMilli();
        long firstElapsed = 20_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.setEnabled(true));
        assertTrue(state.observeClock(firstWall, firstElapsed, lisbonDay, LISBON.getId()));

        ZoneId newZone = ZoneId.of("America/New_York");
        long changedWall = firstWall + 5 * FamilyTimePolicy.MINUTE_MS;
        long changedElapsed = firstElapsed + 5 * FamilyTimePolicy.MINUTE_MS;
        LocalDate changedDay = java.time.Instant.ofEpochMilli(changedWall).atZone(newZone).toLocalDate();
        assertFalse(state.observeClockAutomatically(changedWall, changedElapsed, changedDay,
                newZone.getId(), true, false));
        assertEquals(FamilyProfileStore.ClockIssue.TIMEZONE_CHANGED, store().snapshot(changedDay).clockIssue);

        assertTrue("Once Android reports automatic time and zone and samples remain stable, do not require Adult review",
                state.observeClockAutomatically(changedWall + 16_000L, changedElapsed + 16_000L,
                        changedDay, newZone.getId(), true, true));
        assertFalse(store().snapshot(changedDay).clockUncertain);
    }

    @Test public void timezoneChangeAcrossLocalDateRequiresAdultClockReview() {
        LocalDate lisbonDay = LocalDate.of(2026, 10, 23);
        long firstWall = lisbonDay.atTime(0, 30).atZone(LISBON).toInstant().toEpochMilli();
        long firstElapsed = 10_000_000L;
        FamilyProfileStore state = store();
        assertTrue(state.observeClock(firstWall, firstElapsed, lisbonDay, LISBON.getId()));

        ZoneId newZone = ZoneId.of("America/New_York");
        long nextWall = firstWall + 5 * FamilyTimePolicy.MINUTE_MS;
        LocalDate localDayAfterZoneChange = java.time.Instant.ofEpochMilli(nextWall)
                .atZone(newZone).toLocalDate();
        assertFalse("Automatic time alone must not trust a manual time-zone change to another quota day",
                state.observeClockAutomatically(nextWall, firstElapsed + 5 * FamilyTimePolicy.MINUTE_MS,
                        localDayAfterZoneChange, newZone.getId(), true, false));

        FamilyProfileStore.Snapshot blocked = store().snapshot(localDayAfterZoneChange);
        assertTrue(blocked.clockUncertain);
        assertEquals(FamilyProfileStore.ClockIssue.TIMEZONE_CHANGED, blocked.clockIssue);
        assertFalse(FamilyTimePolicy.evaluate(FamilyTimePolicy.Profile.CHILD_TV,
                java.time.ZonedDateTime.of(2026, 10, 22, 20, 35, 0, 0, newZone),
                blocked.childUsedMs, blocked.childExtensionMs, blocked.clockUncertain,
                blocked.childSchedule).allowed);

        assertTrue("Only an authenticated Adult review clears the date-changing zone marker",
                state.confirmClock(nextWall, firstElapsed + 5 * FamilyTimePolicy.MINUTE_MS,
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
