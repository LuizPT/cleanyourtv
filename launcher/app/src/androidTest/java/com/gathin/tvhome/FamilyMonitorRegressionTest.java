package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Properties;

/** Integration regressions for the Adult session, screen receivers, and automatic clock recovery. */
@RunWith(AndroidJUnit4.class)
public final class FamilyMonitorRegressionTest {
    private Context target;
    private File familyFile;
    private File pinFile;
    private SavedFile originalFamily;
    private SavedFile originalPin;
    private Activity activity;
    private char[] testPin;
    private int originalAutoTime = -1;
    private int originalAutoTimeZone = -1;
    private int originalUsageMode = AppOpsManager.MODE_DEFAULT;
    private boolean usageModeChanged;
    private boolean settingsOpened;
    private Intent monitorIntent;

    @Before public void setUp() throws Exception {
        Assume.assumeTrue("Family monitor integration tests require an emulator", isEmulator());
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PowerManager initialPower = (PowerManager) target.getSystemService(Context.POWER_SERVICE);
        if (initialPower != null && !initialPower.isInteractive()) {
            runShell("input keyevent " + KeyEvent.KEYCODE_WAKEUP);
            waitForInteractive(initialPower, true, 5_000L);
        }
        runShell("wm dismiss-keyguard");
        AppOpsManager appOps = (AppOpsManager) target.getSystemService(Context.APP_OPS_SERVICE);
        originalUsageMode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), target.getPackageName());
        originalAutoTime = Settings.Global.getInt(target.getContentResolver(), Settings.Global.AUTO_TIME, 0);
        originalAutoTimeZone = Settings.Global.getInt(target.getContentResolver(),
                Settings.Global.AUTO_TIME_ZONE, 0);

        familyFile = new File(target.getFilesDir(), "family-profiles.properties");
        pinFile = new File(target.getFilesDir(), "launcher-state.properties");
        originalFamily = save(familyFile);
        originalPin = save(pinFile);
        clearForTest(familyFile);
        clearForTest(pinFile);

        SecureRandom random = new SecureRandom();
        testPin = new char[6];
        for (int i = 0; i < testPin.length; i++) testPin[i] = (char) ('0' + random.nextInt(10));
        LauncherState state = new LauncherState(pinFile);
        assertTrue("Synthetic test credential setup failed", state.setPin(testPin.clone()));
        assertTrue("Synthetic test sequence setup failed",
                state.setUnlockSequence("UDLRUDLRUD".toCharArray()));
        FamilyProfileStore store = new FamilyProfileStore(familyFile);
        assertTrue(store.initializeChildApps(java.util.Collections.emptySet()));
        assertTrue(store.setEnabled(true));
        monitorIntent = new Intent(target, FamilySessionMonitorService.class);
    }

    @After public void tearDown() throws Exception {
        try {
            if (target != null && monitorIntent != null) target.stopService(monitorIntent);
            if (settingsOpened) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
            if (activity != null) {
                Activity toFinish = activity;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(toFinish::finish);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
        } finally {
            if (target != null) {
                if (usageModeChanged) setUsageAccessMode(modeName(originalUsageMode));
                if (originalAutoTime >= 0) setGlobalSetting("auto_time", originalAutoTime);
                if (originalAutoTimeZone >= 0) setGlobalSetting("auto_time_zone", originalAutoTimeZone);
            }
            restore(familyFile, originalFamily);
            restore(pinFile, originalPin);
            if (testPin != null) Arrays.fill(testPin, '\0');
        }
    }

    @Test public void duplicateScreenOnReceiverDoesNotClearAdultOrInvalidateReentry() throws Exception {
        authenticateAdult();
        AdultProfileAccessWindow window = field(activity, "adultProfileAccessWindow",
                AdultProfileAccessWindow.class);
        BroadcastReceiver receiver = field(activity, "familyScreenReceiver", BroadcastReceiver.class);
        assertTrue(window.permitsReentry(SystemClock.elapsedRealtime()));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                receiver.onReceive(activity, new Intent(Intent.ACTION_SCREEN_ON)));

        assertEquals("A duplicate SCREEN_ON while interactive must not evict Adult",
                FamilyTimePolicy.Profile.ADULT,
                field(activity, "activeFamilyProfile", FamilyTimePolicy.Profile.class));
        assertTrue("A duplicate SCREEN_ON must not revoke the 15-minute reentry window",
                window.permitsReentry(SystemClock.elapsedRealtime()));
    }

    @Test public void outOfOrderScreenOffWhileInteractiveDoesNotMarkWakeOrClearAdult() throws Exception {
        authenticateAdult();
        PowerManager power = (PowerManager) target.getSystemService(Context.POWER_SERVICE);
        assertTrue("The test must begin with the display interactive", power.isInteractive());
        BroadcastReceiver receiver = field(activity, "familyScreenReceiver", BroadcastReceiver.class);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                receiver.onReceive(activity, new Intent(Intent.ACTION_SCREEN_OFF)));

        FamilyProfileStore.Snapshot state = new FamilyProfileStore(familyFile)
                .snapshot(LocalDate.now(ZoneId.systemDefault()));
        assertEquals("An out-of-order SCREEN_OFF cannot clear Adult",
                FamilyTimePolicy.Profile.ADULT,
                field(activity, "activeFamilyProfile", FamilyTimePolicy.Profile.class));
        assertFalse("An out-of-order SCREEN_OFF cannot persist a return-to-Home request",
                state.mustReturnHome);
    }

    @Test public void actualSleepAndWakeRequiresFreshAdultAuthentication() throws Exception {
        authenticateAdult();
        PowerManager power = (PowerManager) target.getSystemService(Context.POWER_SERVICE);
        assertTrue(power.isInteractive());

        runShell("input keyevent " + KeyEvent.KEYCODE_SLEEP);
        waitForInteractive(power, false, 10_000L);
        FamilyScreenTransitionPolicy transitionPolicy = field(activity, "familyScreenTransitions",
                FamilyScreenTransitionPolicy.class);
        waitForPolicyBoolean(transitionPolicy, "screenOffObserved", true, 5_000L);
        assertTrue("The real SCREEN_OFF must be accepted after Android becomes noninteractive",
                field(transitionPolicy, "screenOffObserved", Boolean.class));
        runShell("input keyevent " + KeyEvent.KEYCODE_WAKEUP);
        waitForInteractive(power, true, 10_000L);

        waitForPolicyBoolean(transitionPolicy, "wakeHandled", true, 5_000L);
        waitForProfile(null, 5_000L);
        assertEquals("A true wake leaves the Adult profile", null,
                field(activity, "activeFamilyProfile", FamilyTimePolicy.Profile.class));
        AdultProfileAccessWindow window = field(activity, "adultProfileAccessWindow",
                AdultProfileAccessWindow.class);
        assertFalse("A genuine sleep/wake must invalidate the fifteen-minute reentry grant",
                window.permitsReentry(SystemClock.elapsedRealtime()));
    }

    @Test public void voluntaryReturnHomeKeepsTheExistingFifteenMinuteReentryWindow() throws Exception {
        authenticateAdult();
        AdultProfileAccessWindow window = field(activity, "adultProfileAccessWindow",
                AdultProfileAccessWindow.class);
        setField(activity, "familyLaunchInFlight", true);
        target.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        settingsOpened = true;
        waitForBooleanField("familyActivityResumed", false, 5_000L);

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        settingsOpened = false;
        waitForBooleanField("familyActivityResumed", true, 5_000L);
        awaitVisibleText(target.getString(R.string.family_select_profile));
        assertEquals("Returning voluntarily to Home ends the selected session",
                null, field(activity, "activeFamilyProfile", FamilyTimePolicy.Profile.class));
        assertTrue("Voluntary Home keeps reentry authorized inside fifteen minutes",
                window.permitsReentry(SystemClock.elapsedRealtime()));

        clickView(R.id.familyProfileAdult);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNull("Voluntary Home must not ask for a second sequence inside the window",
                awaitViewById(R.id.unlock_sequence_progress));
        assertEquals(FamilyTimePolicy.Profile.ADULT,
                field(activity, "activeFamilyProfile", FamilyTimePolicy.Profile.class));
    }

    @Test public void activityAndMonitorServiceKeepAdultSessionAfterThirtyMinutesWithoutUsageStats() throws Exception {
        authenticateAdult();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        long wall = now.toInstant().toEpochMilli();
        long elapsed = SystemClock.elapsedRealtime();
        long uptime = SystemClock.uptimeMillis();
        FamilyProfileStore store = new FamilyProfileStore(familyFile);
        // Replay an Adult viewing session whose age is already over thirty minutes. This avoids
        // a 30-minute wall-clock test while exercising the same persisted state at service entry.
        long sessionAgeMs = 31L * 60L * 1000L;
        assertTrue(store.startSession(FamilyProfileStore.Profile.ADULT, "synthetic.tv.app",
                now.toLocalDate(), wall, elapsed, uptime, true));
        assertTrue(store.setSessionForeground(true, "synthetic.tv.app", now.toLocalDate(),
                wall, elapsed, uptime).saved);
        backdateSessionStart(wall - sessionAgeMs);

        setField(activity, "familyLaunchInFlight", true);
        target.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        settingsOpened = true;
        waitForBooleanField("familyActivityResumed", false, 5_000L);
        setUsageAccessMode("ignore");
        assertFalse(FamilyUsageAccess.isGranted(target));

        if (Build.VERSION.SDK_INT >= 26) target.startForegroundService(monitorIntent);
        else target.startService(monitorIntent);
        SystemClock.sleep(1_500L);

        FamilyProfileStore.Snapshot state = store.snapshot(now.toLocalDate());
        assertEquals("A transient UsageStats outage cannot evict the authenticated Adult",
                FamilyTimePolicy.Profile.ADULT,
                field(activity, "activeFamilyProfile", FamilyTimePolicy.Profile.class));
        assertTrue("The monitor must keep the long Adult session intact", state.sessionPending);
        assertEquals(FamilyProfileStore.Profile.ADULT, state.sessionProfile);
        assertTrue("The service must not force the launcher over the open app", Boolean.FALSE.equals(
                field(activity, "familyActivityResumed", Boolean.class)));

        PowerManager power = (PowerManager) target.getSystemService(Context.POWER_SERVICE);
        runShell("input keyevent " + KeyEvent.KEYCODE_SLEEP);
        waitForInteractive(power, false, 10_000L);
        waitForSessionScreenState(store, now.toLocalDate(), false, 5_000L);
        runShell("input keyevent " + KeyEvent.KEYCODE_WAKEUP);
        waitForInteractive(power, true, 10_000L);
        activity = waitForResumedMainActivity(5_000L);
        assertEquals("The service returns to Home after the genuine wake, requiring Adult re-entry",
                null, field(activity, "activeFamilyProfile", FamilyTimePolicy.Profile.class));
    }

    @Test public void newAdultEntryDoesNotReuseAStaleReturnHomeFlag() throws Exception {
        FamilyProfileStore store = new FamilyProfileStore(familyFile);
        assertTrue(store.markReturnHomeOnWake());
        assertTrue(store.snapshot(LocalDate.now(ZoneId.systemDefault())).mustReturnHome);
        setUsageAccessMode("allow");
        authenticateAdult();
        target.stopService(monitorIntent);
        FamilyProfileStore.Snapshot entered = store.snapshot(LocalDate.now(ZoneId.systemDefault()));
        assertFalse("A prior session's Home request must be reset when the new session starts",
                entered.mustReturnHome);
        assertEquals("Selecting Adult does not start a time-limited app session", null, entered.sessionProfile);
    }

    @Test public void automaticClockRecoveryUpdatesTheSelectorWithoutRefreshClicks() throws Exception {
        setGlobalSetting("auto_time", 1);
        setGlobalSetting("auto_time_zone", 1);
        launchMainActivity();
        awaitVisibleText(target.getString(R.string.family_select_profile));
        FamilyProfileStore store = new FamilyProfileStore(familyFile);
        LocalDate day = LocalDate.now(ZoneId.systemDefault());
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        assertFalse("The synthetic rollback should create a clock-review marker",
                store.observeClock(wall - 60L * 60L * 1000L, elapsed, day));
        assertTrue(store.snapshot(day).clockUncertain);

        BroadcastReceiver receiver = field(activity, "familyScreenReceiver", BroadcastReceiver.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                receiver.onReceive(activity, new Intent(Intent.ACTION_TIME_CHANGED)));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        TextViewField warning = clockWarning();
        assertEquals("Clock review is initially visible", android.view.View.VISIBLE, warning.visibility());
        SystemClock.sleep(16_000L);

        assertFalse("A trusted, stable clock must recover automatically after the 15-second window",
                store.snapshot(day).clockUncertain);
        assertEquals("The existing selector must update without button presses",
                android.view.View.GONE, warning.visibility());
    }

    private void authenticateAdult() throws Exception {
        launchMainActivity();
        awaitVisibleText(target.getString(R.string.family_select_profile));
        clickView(R.id.familyProfileAdult);
        awaitVisibleText(target.getString(R.string.family_adult_auth_subtitle));
        enterSequence("UDLRUDLRUD");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitForProfile(FamilyTimePolicy.Profile.ADULT, 5_000L);
        assertEquals(FamilyTimePolicy.Profile.ADULT,
                field(activity, "activeFamilyProfile", FamilyTimePolicy.Profile.class));
    }

    private void launchMainActivity() {
        Intent intent = new Intent(target, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void clickView(int id) throws Exception {
        AccessibilityNodeInfo node = awaitViewById(id);
        assertNotNull("Expected interactive view " + id, node);
        try {
            assertTrue("View must accept OK/click: " + id,
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        } finally {
            node.recycle();
        }
    }

    private AccessibilityNodeInfo awaitViewById(int id) throws Exception {
        String name = target.getResources().getResourceName(id);
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(name);
                root.recycle();
                if (nodes != null && !nodes.isEmpty()) {
                    AccessibilityNodeInfo first = nodes.get(0);
                    for (int i = 1; i < nodes.size(); i++) nodes.get(i).recycle();
                    return first;
                }
            }
            Thread.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    private String awaitVisibleText(String expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15_000L;
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            StringBuilder found = new StringBuilder();
            appendText(root, found);
            if (root != null) root.recycle();
            if (found.toString().contains(expected)) return found.toString();
            Thread.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Expected visible text in the active window: " + expected);
    }

    private static void appendText(AccessibilityNodeInfo node, StringBuilder text) {
        if (node == null) return;
        if (node.getText() != null) text.append(node.getText()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) appendText(node.getChild(i), text);
    }

    private void enterSequence(String sequence) {
        for (char direction : sequence.toCharArray()) {
            int key = direction == 'U' ? KeyEvent.KEYCODE_DPAD_UP
                    : direction == 'D' ? KeyEvent.KEYCODE_DPAD_DOWN
                    : direction == 'L' ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);
        }
    }

    private void waitForInteractive(PowerManager power, boolean expected, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (power.isInteractive() != expected && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        assertEquals("Expected the power manager interactive state to change", expected, power.isInteractive());
    }

    private void waitForProfile(FamilyTimePolicy.Profile expected, long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (field(activity, "activeFamilyProfile", FamilyTimePolicy.Profile.class) != expected
                && SystemClock.uptimeMillis() < deadline) Thread.sleep(50L);
    }

    private void waitForSessionScreenState(FamilyProfileStore store, LocalDate day,
                                           boolean expected, long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (store.snapshot(day).sessionPending && store.snapshot(day).sessionScreenOn != expected
                && SystemClock.uptimeMillis() < deadline) Thread.sleep(50L);
        FamilyProfileStore.Snapshot state = store.snapshot(day);
        assertTrue("The service should still persist the active Adult session", state.sessionPending);
        assertEquals(expected, state.sessionScreenOn);
    }

    private void waitForBooleanField(String name, boolean expected, long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while ((Boolean) field(activity, name, Boolean.class) != expected
                && SystemClock.uptimeMillis() < deadline) Thread.sleep(50L);
        assertEquals(expected, field(activity, name, Boolean.class));
    }

    private void waitForPolicyBoolean(Object policy, String name, boolean expected, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while ((Boolean) field(policy, name, Boolean.class) != expected
                && SystemClock.uptimeMillis() < deadline) Thread.sleep(50L);
        assertEquals(expected, field(policy, name, Boolean.class));
    }

    private Activity waitForResumedMainActivity(long timeoutMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            java.util.concurrent.atomic.AtomicReference<Activity> resumed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (candidate instanceof MainActivity) resumed.set(candidate);
                }
            });
            if (resumed.get() != null) return resumed.get();
            Thread.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The service did not bring the launcher Activity back to foreground");
    }

    private TextViewField clockWarning() throws Exception {
        Field field = MainActivity.class.getDeclaredField("familySelectorClockWarning");
        field.setAccessible(true);
        android.widget.TextView warning = (android.widget.TextView) field.get(activity);
        assertNotNull("The selector's clock warning must be accessible for the integration assertion", warning);
        return new TextViewField(warning);
    }

    private void setUsageAccessMode(String mode) throws IOException {
        String command = "appops set " + target.getPackageName() + " GET_USAGE_STATS " + mode;
        runShell(command);
        usageModeChanged = true;
    }

    private void backdateSessionStart(long wallMillis) throws IOException {
        Properties values = new Properties();
        try (FileInputStream input = new FileInputStream(familyFile)) { values.load(input); }
        values.setProperty("session_started_wall_ms", Long.toString(wallMillis));
        try (FileOutputStream output = new FileOutputStream(familyFile)) {
            values.store(output, "synthetic Adult viewing duration regression");
            output.getFD().sync();
        }
    }

    private static String modeName(int mode) {
        if (mode == AppOpsManager.MODE_ALLOWED) return "allow";
        if (mode == AppOpsManager.MODE_IGNORED) return "ignore";
        if (mode == AppOpsManager.MODE_ERRORED) return "deny";
        if (mode == AppOpsManager.MODE_FOREGROUND) return "foreground";
        return "default";
    }

    private static void setGlobalSetting(String name, int value) throws IOException {
        if (!"auto_time".equals(name) && !"auto_time_zone".equals(name)) {
            throw new IllegalArgumentException("Unsupported test setting");
        }
        runShell("settings put global " + name + " " + value);
    }

    private static String runShell(String command) throws IOException {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String result = output.toString("UTF-8");
            if (result.contains("Permission Denial") || result.contains("Error:")) {
                throw new IOException("Shell operation failed: " + result);
            }
            return result;
        }
    }

    private static boolean isEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase(java.util.Locale.ROOT);
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase(java.util.Locale.ROOT);
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE.toLowerCase(java.util.Locale.ROOT);
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase(java.util.Locale.ROOT);
        String device = Build.DEVICE == null ? "" : Build.DEVICE.toLowerCase(java.util.Locale.ROOT);
        return fingerprint.startsWith("generic") || fingerprint.startsWith("unknown")
                || fingerprint.contains("/emu") || model.contains("emulator")
                || model.contains("sdk_gphone") || model.contains("android sdk built for")
                || "goldfish".equals(hardware) || "ranchu".equals(hardware)
                || product.contains("sdk_gphone") || device.startsWith("emu");
    }

    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(object));
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static SavedFile save(File file) throws IOException {
        if (!file.exists()) return new SavedFile(false, null);
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return new SavedFile(true, bytes);
    }

    private static void clearForTest(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Could not isolate test fixture file");
    }

    private static void restore(File file, SavedFile saved) throws IOException {
        if (file == null || saved == null) return;
        if (!saved.exists) {
            clearForTest(file);
            return;
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(saved.bytes);
            output.getFD().sync();
        }
    }

    private static final class SavedFile {
        final boolean exists;
        final byte[] bytes;
        SavedFile(boolean exists, byte[] bytes) { this.exists = exists; this.bytes = bytes; }
    }

    private static final class TextViewField {
        final android.widget.TextView view;
        TextViewField(android.widget.TextView view) { this.view = view; }
        int visibility() { return view.getVisibility(); }
    }
}
