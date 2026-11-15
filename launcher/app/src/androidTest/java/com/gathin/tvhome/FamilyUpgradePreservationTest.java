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
import java.util.Properties;

/** Opt-in, emulator-only in-place update fixture; does not run during the regular test suite. */
@RunWith(AndroidJUnit4.class)
public final class FamilyUpgradePreservationTest {
    private static final String MARKER_FILE = "family-upgrade-test.properties";
    private static final String TEST_DAY = "2026-11-15";

    @Test public void seedSyntheticStateForUpdateChain() throws Exception {
        assumeExplicitUpgradeRun();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File pinFile = new File(target.getFilesDir(), "launcher-state.properties");
        File familyFile = new File(target.getFilesDir(), "family-profiles.properties");
        char[] pin = new char[6];
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < pin.length; i++) pin[i] = (char) ('0' + random.nextInt(10));
        try {
            assertTrue("Synthetic legacy PIN setup failed", new LauncherState(pinFile).setPin(pin));
        } finally {
            Arrays.fill(pin, '\0');
        }

        Properties family = new Properties();
        family.setProperty("family_enabled", "true");
        family.setProperty("clock_uncertain", "false");
        family.setProperty("xbox_input_id", "synthetic.tv/.TvInput/HDMI1");
        family.setProperty("used.child_tv." + TEST_DAY, "123456");
        family.setProperty("extra.child_tv." + TEST_DAY, "900000");
        family.setProperty("used.xbox." + TEST_DAY, "234567");
        family.setProperty("schedule.child_tv.SATURDAY.start", "450");
        family.setProperty("schedule.child_tv.SATURDAY.end", "1320");
        family.setProperty("schedule.child_tv.SATURDAY.limit", "180");
        family.setProperty("schedule.xbox.SATURDAY.start", "600");
        family.setProperty("schedule.xbox.SATURDAY.end", "1380");
        family.setProperty("schedule.xbox.SATURDAY.limit", "120");
        write(familyFile, family);

        Properties marker = new Properties();
        marker.setProperty("legacy_pin_state_sha256", credentialFingerprint(pinFile));
        for (String key : family.stringPropertyNames()) marker.setProperty("expect." + key, family.getProperty(key));
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
        assertNotNull("Seed the synthetic state before verifying", marker.getProperty("legacy_pin_state_sha256"));
        File pinFile = new File(target.getFilesDir(), "launcher-state.properties");
        assertEquals("Legacy PIN salt/hash state must be preserved",
                marker.getProperty("legacy_pin_state_sha256"), credentialFingerprint(pinFile));
        Properties family = read(new File(target.getFilesDir(), "family-profiles.properties"));
        for (String key : marker.stringPropertyNames()) {
            if (!key.startsWith("expect.")) continue;
            String stateKey = key.substring("expect.".length());
            assertEquals("Family state changed: " + stateKey,
                    marker.getProperty(key), family.getProperty(stateKey));
        }
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

    private static String credentialFingerprint(File pinFile) throws Exception {
        Properties pin = read(pinFile);
        String salt = pin.getProperty("pin_salt");
        String hash = pin.getProperty("pin_hash");
        assertNotNull("No synthetic PIN salt", salt);
        assertNotNull("No synthetic PIN hash", hash);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] result = digest.digest((salt + ":" + hash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
