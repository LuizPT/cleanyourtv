package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Opt-in, emulator-only in-place update fixture; does not run during the regular test suite. */
@RunWith(AndroidJUnit4.class)
public final class FamilyUpgradePreservationTest {
    private static final String MARKER_FILE = "family-upgrade-test.properties";
    private static final String CHILD_PACKAGE = "com.synthetic.child";
    private static final String RESTRICTED_PACKAGE = "com.synthetic.restricted";
    private static final String XBOX_INPUT_ID = "synthetic.tv/.TvInput/HDMI1";

    @Test public void seedSyntheticStateForUpdateChain() throws Exception {
        assumeExplicitUpgradeRun();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File pinFile = new File(target.getFilesDir(), "launcher-state.properties");
        File familyFile = new File(target.getFilesDir(), "family-profiles.properties");
        LocalDate day = LocalDate.now(ZoneId.systemDefault());
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        long wall = now.toInstant().toEpochMilli();
        long elapsed = android.os.SystemClock.elapsedRealtime();
        long uptime = android.os.SystemClock.uptimeMillis();
        char[] pin = new char[6];
        char[] sequence = new char[10];
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < pin.length; i++) pin[i] = (char) ('0' + random.nextInt(10));
        char[] directions = {'U', 'D', 'L', 'R'};
        for (int i = 0; i < sequence.length; i++) sequence[i] = directions[random.nextInt(directions.length)];
        try {
            LauncherState launcherState = new LauncherState(pinFile);
            assertTrue("Synthetic legacy PIN setup failed", launcherState.setPin(pin));
            assertTrue("Synthetic Adult sequence setup failed", launcherState.setUnlockSequence(sequence));
            assertTrue("Synthetic restricted-app setting failed",
                    launcherState.saveProtectedApps(Collections.singleton(RESTRICTED_PACKAGE)));
        } finally {
            Arrays.fill(pin, '\0');
            Arrays.fill(sequence, '\0');
        }

        FamilyProfileStore store = new FamilyProfileStore(familyFile);
        assertTrue("Synthetic family settings setup failed", store.setEnabled(true));
        Map<String, Long> approved = new HashMap<>();
        approved.put(CHILD_PACKAGE, 123L);
        assertTrue("Synthetic child-app approvals setup failed", store.initializeChildApps(approved));
        assertTrue("Synthetic Xbox input setup failed", store.setXboxInputId(XBOX_INPUT_ID));
        assertTrue("Synthetic clock baseline setup failed", store.observeClock(wall, elapsed, day,
                ZoneId.systemDefault().getId(), true));
        assertTrue("Synthetic child schedule setup failed",
                store.setSchedule(FamilyProfileStore.Profile.CHILD_TV, day.getDayOfWeek(), 450, 1320, 180));
        assertTrue("Synthetic Xbox schedule setup failed",
                store.setSchedule(FamilyProfileStore.Profile.XBOX, day.getDayOfWeek(), 600, 1380, 120));
        assertTrue("Synthetic child extension setup failed",
                store.addExtension(FamilyProfileStore.Profile.CHILD_TV, day, 15));
        assertTrue("Synthetic Xbox extension setup failed",
                store.addExtension(FamilyProfileStore.Profile.XBOX, day, 30));
        assertTrue("Synthetic persisted session setup failed", store.startSession(
                FamilyProfileStore.Profile.CHILD_TV, CHILD_PACKAGE, day, wall, elapsed, uptime, true));
        Properties family = read(familyFile);
        family.setProperty("used.child_tv." + day, "123456");
        family.setProperty("used.xbox." + day, "234567");
        write(familyFile, family);

        Properties marker = new Properties();
        marker.setProperty("launcher_state_sha256", propertiesFingerprint(read(pinFile)));
        marker.setProperty("family_state_sha256", propertiesFingerprint(read(familyFile)));
        marker.setProperty("test_day", day.toString());
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        write(new File(test.getFilesDir(), MARKER_FILE), marker);
    }

    @Test public void verifySyntheticStateAfterInPlaceUpdate() throws Exception {
        assumeExplicitUpgradeRun();
        Bundle arguments = InstrumentationRegistry.getArguments();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int expectedCode = arguments.getInt("expected.version", -1);
        assertTrue("Pass expected.version for this update step", expectedCode > 0);
        PackageInfo info = target.getPackageManager().getPackageInfo(target.getPackageName(), 0);
        assertEquals(expectedCode, info.versionCode);

        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        Properties marker = read(new File(test.getFilesDir(), MARKER_FILE));
        assertNotNull("Seed the synthetic state before verifying", marker.getProperty("launcher_state_sha256"));
        File pinFile = new File(target.getFilesDir(), "launcher-state.properties");
        Properties launcher = read(pinFile);
        assertEquals("PIN, Adult sequence, and launcher preferences must be preserved",
                marker.getProperty("launcher_state_sha256"), propertiesFingerprint(launcher));
        LauncherState launcherState = new LauncherState(pinFile);
        assertTrue("Legacy PIN compatibility data is missing", launcherState.isPinConfigured());
        assertTrue("Adult sequence migration state is missing", launcherState.isUnlockSequenceConfigured());
        assertTrue("Restricted-app setting is missing", launcherState.protectedApps().contains(RESTRICTED_PACKAGE));

        File familyFile = new File(target.getFilesDir(), "family-profiles.properties");
        Properties family = read(familyFile);
        assertEquals("Family settings, schedules, approvals, and persisted session must be preserved",
                marker.getProperty("family_state_sha256"), propertiesFingerprint(family));
        LocalDate day = LocalDate.parse(marker.getProperty("test_day"));
        FamilyProfileStore store = new FamilyProfileStore(familyFile);
        assertTrue("Family configuration is disabled", store.isEnabled());
        assertTrue("Authorized child app was lost", store.isChildAppAllowed(CHILD_PACKAGE, 123L));
        assertTrue("Xbox input selection was lost", XBOX_INPUT_ID.equals(store.snapshot(day).xboxInputId));
        FamilyProfileStore.Snapshot snapshot = store.snapshot(day);
        assertEquals(FamilyProfileStore.Profile.CHILD_TV, snapshot.sessionProfile);
        assertTrue("Persisted family session was lost", snapshot.sessionPending);
        assertEquals(123456L, snapshot.childUsedMs);
        assertEquals(234567L, snapshot.xboxUsedMs);
        assertEquals(15L * FamilyTimePolicy.MINUTE_MS, snapshot.childExtensionMs);
        assertEquals(30L * FamilyTimePolicy.MINUTE_MS, snapshot.xboxExtensionMs);
        assertEquals(450, snapshot.childSchedule.startMinute);
        assertEquals(600, snapshot.xboxSchedule.startMinute);
    }

    private static void assumeExplicitUpgradeRun() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Update preservation fixtures are restricted to Android emulators", isEmulator());
        Assume.assumeTrue("Only run explicitly on the disposable emulator",
                arguments.getBoolean("family.upgrade.fixture", false));
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

    private static String propertiesFingerprint(Properties properties) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder canonical = new StringBuilder();
        properties.stringPropertyNames().stream().sorted().forEach(key -> canonical.append(key)
                .append('=').append(properties.getProperty(key)).append('\n'));
        byte[] result = digest.digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(result.length * 2);
        for (byte value : result) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        Arrays.fill(result, (byte) 0);
        return hex.toString();
    }

    private static Properties read(File file) throws Exception {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { properties.load(input); }
        return properties;
    }

    private static void write(File file, Properties properties) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) assertTrue(parent.mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, "synthetic in-place update fixture");
        }
    }
}
