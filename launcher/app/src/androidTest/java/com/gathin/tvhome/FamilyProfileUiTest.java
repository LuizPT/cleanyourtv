package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;

/** Device-side smoke test for family profile navigation and PIN-gated management. */
@RunWith(AndroidJUnit4.class)
public final class FamilyProfileUiTest {
    private File familyFile;
    private File pinFile;
    private File overlayTestFile;
    private SavedFile originalFamily;
    private SavedFile originalPin;
    private SavedFile originalOverlayTestFile;
    private Activity activity;
    private char[] testPin;

    @Before public void setUp() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        familyFile = new File(target.getFilesDir(), "family-profiles.properties");
        pinFile = new File(target.getFilesDir(), "launcher-state.properties");
        overlayTestFile = new File(target.getFilesDir(), "family-time-bar-ui-test.properties");
        originalFamily = save(familyFile);
        originalPin = save(pinFile);
        originalOverlayTestFile = save(overlayTestFile);

        SecureRandom random = new SecureRandom();
        testPin = new char[6];
        for (int i = 0; i < testPin.length; i++) testPin[i] = (char) ('0' + random.nextInt(10));
        LauncherState launcherState = new LauncherState(pinFile);
        assertTrue("Test PIN setup failed", launcherState.setPin(testPin.clone()));
        FamilyProfileStore familyStore = new FamilyProfileStore(familyFile);
        assertTrue("Family test state setup failed", familyStore.setEnabled(true));
    }

    @Test public void profileSelectorAndManagementUseDpadAndRequirePin() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        String selector = awaitVisibleText(getString(R.string.family_select_profile));
        assertTrue(selector.contains(getString(R.string.family_profile_adult)));
        assertTrue(selector.contains(getString(R.string.family_profile_child)));
        assertTrue(selector.contains(getString(R.string.family_profile_xbox)));

        String firstFocus = awaitFocusedText(null);
        assertNotNull("Profile selector has no focused D-pad option", firstFocus);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        String nextFocus = awaitFocusedText(firstFocus);
        assertNotNull("D-pad focus disappeared in the profile selector", nextFocus);
        assertFalse("D-pad down did not move focus", firstFocus.equals(nextFocus));
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View settings = activity.findViewById(R.id.btnSettings);
            assertNotNull(settings);
            assertTrue("Long-press Settings did not open Home tools", settings.performLongClick());
        });
        awaitVisibleText(getString(R.string.family_management));
        for (int i = 0; i < 4; i++) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        String authDialog = awaitVisibleText(getString(R.string.pin_entry_help));
        assertTrue("Management PIN prompt is missing", authDialog.contains(getString(R.string.family_management)));
        assertFalse("Overlay settings appeared before parental PIN authentication",
                authDialog.contains(getString(R.string.family_time_bar_open_settings)));
    }

    @Test public void timeOverlayWindowCannotTakeDpadOrPointerInput() {
        WindowManager.LayoutParams params = FamilyTimeOverlay.createLayoutParams(360,
                WindowManager.LayoutParams.WRAP_CONTENT, FamilyProfileStore.TimeBarCorner.RIGHT);
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type);
        assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        assertEquals(Gravity.BOTTOM | Gravity.RIGHT, params.gravity);
        assertEquals(0.75f, params.alpha, 0.001f);
    }

    @Test public void timeOverlayRespectsTheSpecialPermissionAndAnActiveChildSession() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FamilyProfileStore overlayStore = new FamilyProfileStore(overlayTestFile);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Lisbon"));
        long wall = now.toInstant().toEpochMilli();
        long elapsed = SystemClock.elapsedRealtime();
        assertTrue(overlayStore.setEnabled(true));
        assertTrue(overlayStore.startSession(FamilyProfileStore.Profile.CHILD_TV,
                now.toLocalDate(), wall, elapsed, SystemClock.uptimeMillis(), true));
        assertTrue(overlayStore.setSessionForeground(true, "test.tv.app", now.toLocalDate(), wall,
                elapsed, SystemClock.uptimeMillis()).saved);

        FamilyTimeOverlay overlay = new FamilyTimeOverlay(target);
        boolean permission = FamilyTimeOverlay.hasPermission(target);
        boolean attached = overlay.update(overlayStore.snapshot(now.toLocalDate()),
                now.toLocalDate(), true, true, null);
        assertEquals("Overlay visibility must follow Android's explicit app-op", permission, attached);
        overlay.hide();
    }

    @After public void tearDown() throws Exception {
        try {
            if (activity != null) {
                Activity toFinish = activity;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(toFinish::finish);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
        } finally {
            restore(familyFile, originalFamily);
            restore(pinFile, originalPin);
            restore(overlayTestFile, originalOverlayTestFile);
            if (testPin != null) Arrays.fill(testPin, '\0');
        }
    }

    private String awaitVisibleText(String expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        String text = "";
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            text = visibleText();
            if (text.contains(expected)) return text;
            Thread.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Text not visible in active window: " + expected + "\n" + text);
    }

    private String awaitFocusedText(String differentFrom) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        String text;
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            text = focusedText();
            if (text != null && (differentFrom == null || !differentFrom.equals(text))) return text;
            Thread.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        return text;
    }

    private String getString(int resource) {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getString(resource);
    }

    private String visibleText() {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        StringBuilder text = new StringBuilder();
        appendText(root, text);
        if (root != null) root.recycle();
        return text.toString();
    }

    private String focusedText() {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        AccessibilityNodeInfo focused = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        String result = nodeText(focused);
        if (result == null) result = findFocusedText(root);
        if (focused != null) focused.recycle();
        if (root != null) root.recycle();
        return result;
    }

    private static String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence value = node.getText();
        return value == null ? node.getClassName().toString() : value.toString();
    }

    private static void appendText(AccessibilityNodeInfo node, StringBuilder output) {
        if (node == null) return;
        CharSequence value = node.getText();
        if (value != null) output.append(value).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) appendText(node.getChild(i), output);
    }

    private static String findFocusedText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocused()) {
            CharSequence text = node.getText();
            return text == null ? node.getClassName().toString() : text.toString();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String result = findFocusedText(node.getChild(i));
            if (result != null) return result;
        }
        return null;
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

    private static void restore(File file, SavedFile saved) throws IOException {
        if (file == null || saved == null) return;
        if (!saved.existed) {
            if (file.exists() && !file.delete()) throw new IOException("Could not restore test file state");
            return;
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(saved.bytes);
            output.getFD().sync();
        }
    }

    private static final class SavedFile {
        final boolean existed;
        final byte[] bytes;
        SavedFile(boolean existed, byte[] bytes) { this.existed = existed; this.bytes = bytes; }
    }
}
