package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.UiAutomation;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

/** Device-side smoke test for family profile navigation and unlock-sequence-gated management. */
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
        Assume.assumeTrue("Family profile UI tests are restricted to Android emulators", isEmulator());
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
        assertTrue("Test sequence setup failed", launcherState.setUnlockSequence("UDLRUDLRUD".toCharArray()));
        FamilyProfileStore familyStore = new FamilyProfileStore(familyFile);
        assertTrue("Child app test setup failed", familyStore.initializeChildApps(Collections.emptySet()));
        assertTrue("Family test state setup failed", familyStore.setEnabled(true));
    }

    @Test public void profileSelectorAndManagementUseDpadAndRequireSequence() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        String selector = awaitVisibleText(getString(R.string.family_select_profile));
        assertTrue(selector.contains(getString(R.string.family_profile_adult)));
        assertTrue(selector.contains(getString(R.string.family_profile_child_short)));
        assertTrue(selector.contains(getString(R.string.family_profile_xbox_short)));
        assertProfileCard(R.id.familyProfileAdult);
        assertProfileCard(R.id.familyProfileChild);
        assertProfileCard(R.id.familyProfileXbox);
        assertProfileCardsAreHorizontal();
        assertProfileArtworkResourcesAreSquare();
        AccessibilityNodeInfo manageNode = awaitViewById(R.id.familyManageProfiles);
        assertNotNull("Profile management action is missing", manageNode);
        assertTrue("Profile management action must be focusable", manageNode.isFocusable());
        manageNode.recycle();

        AccessibilityNodeInfo adultFocus = awaitFocusedViewById(R.id.familyProfileAdult);
        assertNotNull("Adult profile should receive initial D-pad focus", adultFocus);
        adultFocus.recycle();
        AccessibilityNodeInfo childCard = awaitViewById(R.id.familyProfileChild);
        assertNotNull(childCard);
        boolean childFocusable = childCard.isFocusable();
        childCard.recycle();
        if (childFocusable) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            AccessibilityNodeInfo childFocus = awaitFocusedViewById(R.id.familyProfileChild);
            assertNotNull("Right arrow should move from Adult to Child", childFocus);
            childFocus.recycle();
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            AccessibilityNodeInfo returnedAdult = awaitFocusedViewById(R.id.familyProfileAdult);
            assertNotNull("Left arrow should return to Adult", returnedAdult);
            returnedAdult.recycle();
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        AccessibilityNodeInfo manageFocus = awaitFocusedViewById(R.id.familyManageProfiles);
        AccessibilityNodeInfo manageAfterKey = awaitViewById(R.id.familyManageProfiles);
        Rect manageBounds = new Rect();
        String manageState = "missing";
        if (manageAfterKey != null) {
            manageAfterKey.getBoundsInScreen(manageBounds);
            manageState = "focusable=" + manageAfterKey.isFocusable() + ", visible="
                    + manageAfterKey.isVisibleToUser() + ", bounds=" + manageBounds;
            manageAfterKey.recycle();
        }
        assertNotNull("D-pad down should focus Manage profiles; focused node: "
                + currentFocusDescription() + "; manage: " + manageState, manageFocus);
        manageFocus.recycle();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        String authDialog = awaitVisibleText(getString(R.string.sequence_auth_entry_help));
        assertTrue("Management sequence prompt is missing",
                authDialog.contains(getString(R.string.family_management)));
        assertFalse("Overlay settings appeared before sequence authentication",
                authDialog.contains(getString(R.string.family_time_bar_open_settings)));
    }

    @Test public void profileManagementActionRequiresParentalSequence() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        awaitVisibleText(getString(R.string.family_select_profile));

        AccessibilityNodeInfo manage = awaitViewById(R.id.familyManageProfiles);
        assertNotNull(manage);
        try {
            assertTrue("Manage profiles action should be available",
                    manage.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        } finally {
            manage.recycle();
        }

        String authDialog = awaitVisibleText(getString(R.string.sequence_auth_entry_help));
        assertTrue("Manage profiles must authenticate before opening family settings",
                authDialog.contains(getString(R.string.family_management)));
        assertFalse("Protected settings appeared before sequence authentication",
                authDialog.contains(getString(R.string.family_time_bar_open_settings)));
    }

    @Test public void authenticatedManagementExposesDiagnosticsStatisticsAndSchedules() throws Exception {
        launchMainActivity();
        awaitVisibleText(getString(R.string.family_select_profile));
        clickView(R.id.familyManageProfiles);
        awaitVisibleText(getString(R.string.sequence_auth_entry_help));
        enterSequence("UDLRUDLRUD");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);

        String management = awaitVisibleText(getString(R.string.family_management));
        assertViewExists(R.id.familyManagementCardChild, "Child card missing from family dashboard");
        assertViewExists(R.id.familyManagementCardXbox, "Xbox card missing from family dashboard");
        assertViewExists(R.id.familyManagementCardSecurity, "Adult security card missing from family dashboard");
        assertViewExists(R.id.familyManagementCardApps, "Allowed apps card missing from family dashboard");
        assertViewExists(R.id.familyManagementCardMonitor, "Monitoring card missing from family dashboard");
        assertTrue(management.contains(getString(R.string.family_protection_limited))
                || management.contains(getString(R.string.family_protection_ready)));

        assertFocusedView(R.id.familyManagementCardChild, "Child card should receive initial dashboard focus");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocusedView(R.id.familyManagementCardXbox, "Right should move from Child to Xbox");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        assertFocusedView(R.id.familyManagementCardApps, "Down should move from Xbox to allowed apps");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        assertFocusedView(R.id.familyManagementCardSecurity, "Left should move to Adult security");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        assertFocusedView(R.id.familyManagementCardMonitor, "Down should move to monitoring");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        String monitoring = awaitVisibleText(getString(R.string.family_management_section_monitoring));
        assertTrue(monitoring.contains(getString(R.string.family_diagnostic_title)));
        assertTrue(monitoring.contains(getString(R.string.family_statistics_title)));
        assertTrue(monitoring.contains(getString(R.string.family_advanced_schedule)));
        clickExactText(getString(R.string.family_advanced_schedule));
        awaitVisibleText(getString(R.string.family_profile_child));
        clickExactText(getString(R.string.family_profile_child));
        awaitVisibleText(getString(R.string.family_schedule_help));
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        awaitVisibleText(getString(R.string.family_schedule_restore_default));
        clickExactText(getString(R.string.save));
        awaitVisibleText(getString(R.string.family_schedule_help));
        clickExactText(getString(R.string.close));
        awaitVisibleText(getString(R.string.family_advanced_schedule));
        clickExactText(getString(R.string.close));
        awaitVisibleText(getString(R.string.family_management_section_monitoring));
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitVisibleText(getString(R.string.family_management));
        assertFocusedView(R.id.familyManagementCardMonitor,
                "Back should return to the previously selected dashboard card");
        clickView(R.id.familyManagementCardChild);
        awaitVisibleText(getString(R.string.family_management_section_time));
        assertTrue(visibleText().contains(getString(R.string.family_add_time_child)));
        clickExactText(getString(R.string.family_add_time_child));
        String extensionChoices = awaitVisibleText(getString(R.string.family_add_time_title));
        assertTrue(extensionChoices.contains(getString(R.string.family_add_time_15)));
        assertTrue(extensionChoices.contains(getString(R.string.family_add_time_30)));
        assertTrue(extensionChoices.contains(getString(R.string.family_add_time_60)));
    }

    private static boolean isEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase(java.util.Locale.ROOT);
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase(java.util.Locale.ROOT);
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE.toLowerCase(java.util.Locale.ROOT);
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase(java.util.Locale.ROOT);
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        return fingerprint.startsWith("generic") || fingerprint.contains("emulator")
                || model.contains("emulator") || model.contains("android sdk built for")
                || hardware.contains("goldfish") || hardware.contains("ranchu")
                || product.contains("sdk_gphone") || manufacturer.contains("genymotion");
    }

    private void assertViewExists(int id, String message) throws Exception {
        AccessibilityNodeInfo node = awaitViewById(id);
        assertNotNull(message, node);
        if (node != null) node.recycle();
    }

    private void assertFocusedView(int id, String message) throws Exception {
        AccessibilityNodeInfo node = awaitFocusedViewById(id);
        assertNotNull(message, node);
        if (node != null) node.recycle();
    }

    @Test public void protectedAppManagementRequiresSequenceBeforeShowingContextActions() throws Exception {
        LauncherState state = new LauncherState(pinFile);
        assertTrue(state.saveProtectedApps(Collections.singleton("com.android.settings")));
        launchMainActivity();
        awaitVisibleText(getString(R.string.family_select_profile));

        clickView(R.id.familyProfileAdult);
        awaitVisibleText(getString(R.string.sequence_auth_entry_help));
        int[] adultSequence = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN};
        for (int move : adultSequence) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(move);
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        Activity targetActivity = activity;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(
                        "showAppContextMenu", String.class, String.class, boolean.class);
                method.setAccessible(true);
                method.invoke(targetActivity, "com.android.settings", "Settings", false);
            } catch (Exception e) {
                throw new AssertionError("Could not open protected app context path", e);
            }
        });

        String prompt = awaitVisibleText(getString(R.string.sequence_auth_entry_help));
        assertFalse("Protected app actions must stay hidden until sequence authentication",
                prompt.contains(getString(R.string.context_unprotect_sequence)));
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    }

    @Test public void childProfileBlocksUnapprovedLibraryAndHomeTileLaunchPaths() throws Exception {
        launchMainActivity();
        awaitVisibleText(getString(R.string.family_select_profile));
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        MainActivity targetActivity = (MainActivity) activity;
        AppAdapter.Entry unapproved = new AppAdapter.Entry("Adult only",
                new ComponentName("example.adult.only", "example.adult.only.MainActivity"), null, null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                java.lang.reflect.Method setProfile = MainActivity.class.getDeclaredMethod(
                        "setActiveFamilyProfile", FamilyTimePolicy.Profile.class);
                setProfile.setAccessible(true);
                setProfile.invoke(targetActivity, FamilyTimePolicy.Profile.CHILD_TV);
                targetActivity.openFromLibrary("example.adult.only", unapproved.component,
                        "Adult only");
                targetActivity.onTileLaunch(unapproved);
                java.lang.reflect.Method manage = MainActivity.class.getDeclaredMethod("showFamilyManagement");
                manage.setAccessible(true);
                manage.invoke(targetActivity);
            } catch (Exception e) {
                throw new AssertionError("Child profile access checks failed", e);
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse("Blocked app launch must not create a usage session",
                new FamilyProfileStore(familyFile).hasPendingSession());
        assertNull("A child must not open the Adult management authentication path",
                awaitViewById(R.id.unlock_sequence_progress));
        assertTrue("The blocked routes must leave CleanYourTV in front", !activity.isFinishing());
    }

    @Test public void adultAuthenticationCapturesOnlyDpadMovesAndBackDeletesTheLastMove() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        awaitVisibleText(getString(R.string.family_select_profile));

        AccessibilityNodeInfo adult = awaitViewById(R.id.familyProfileAdult);
        assertNotNull("Adult profile must be selectable when parental authentication is configured", adult);
        try {
            assertTrue(adult.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        } finally {
            adult.recycle();
        }

        awaitVisibleText(getString(R.string.sequence_auth_entry_help));
        AccessibilityNodeInfo progress = awaitViewById(R.id.unlock_sequence_progress);
        assertNotNull("Sequence progress dots are missing", progress);
        String emptyProgress = String.valueOf(progress.getText());
        assertTrue("Progress should contain only neutral dot indicators", emptyProgress.matches("[●○ ]+"));
        assertEquals("Only ten move indicators should be shown", 10,
                emptyProgress.chars().filter(value -> value == '○').count());
        progress.recycle();
        AccessibilityNodeInfo count = awaitViewById(R.id.unlock_sequence_count);
        assertEquals("0 / 16", String.valueOf(count.getText()));
        count.recycle();

        int[] moves = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP};
        for (int move : moves) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(move);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        AccessibilityNodeInfo afterDelete = awaitViewById(R.id.unlock_sequence_progress);
        assertNotNull(afterDelete);
        assertTrue("Back must delete only the last move", String.valueOf(afterDelete.getContentDescription()).contains("8"));
        afterDelete.recycle();

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNull("Correct sequence should authorize the Adult profile", awaitViewById(R.id.unlock_sequence_progress));
    }

    @Test public void legacyPinMigratesOnceAndIsNotOfferedAfterSequenceActivation() throws Exception {
        Properties before = readProperties(pinFile);
        String legacyHash = before.getProperty("pin_hash");
        before.remove("unlock_sequence_salt");
        before.remove("unlock_sequence_hash");
        before.remove("unlock_sequence_configured");
        writeProperties(pinFile, before);

        launchMainActivity();
        awaitVisibleText(getString(R.string.family_select_profile));
        clickView(R.id.familyProfileAdult);
        awaitVisibleText(getString(R.string.sequence_migration_help));
        for (char digit : testPin) clickExactText(Character.toString(digit));
        clickExactText(getString(R.string.confirm));

        awaitVisibleText(getString(R.string.sequence_setup_title));
        enterSequence("UDL");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        assertNotNull("A sequence shorter than four moves must not be submitted",
                awaitViewById(R.id.unlock_sequence_progress));
        enterSequence("R");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        awaitVisibleText(getString(R.string.sequence_confirm_title));
        assertFalse("A new sequence must not be enabled before confirmation",
                readProperties(pinFile).containsKey("unlock_sequence_hash"));
        enterSequence("UDLR");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        LauncherState migrated = new LauncherState(pinFile);
        assertTrue("Sequence should be active only after it is saved", migrated.isUnlockSequenceConfigured());
        assertEquals(LauncherState.PinResult.MATCH,
                migrated.verifyUnlockSequence("UDLR".toCharArray(), System.currentTimeMillis()));
        assertEquals("The recovery PIN hash must survive migration", legacyHash,
                readProperties(pinFile).getProperty("pin_hash"));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        launchMainActivity();
        awaitVisibleText(getString(R.string.family_select_profile));
        clickView(R.id.familyProfileAdult);
        awaitVisibleText(getString(R.string.sequence_auth_entry_help));
        assertFalse("The active sequence prompt must not show the legacy numeric keypad",
                hasExactText("0"));
    }

    private void launchMainActivity() {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void enterSequence(String sequence) {
        for (char direction : sequence.toCharArray()) {
            int key = direction == 'U' ? KeyEvent.KEYCODE_DPAD_UP
                    : direction == 'D' ? KeyEvent.KEYCODE_DPAD_DOWN
                    : direction == 'L' ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);
        }
    }

    private void clickView(int id) throws Exception {
        AccessibilityNodeInfo node = awaitViewById(id);
        assertNotNull(node);
        try { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); }
        finally { node.recycle(); }
    }

    private void clickExactText(String expected) throws Exception {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        assertNotNull("No active accessibility window while looking for " + expected, root);
        try {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(expected)) {
                if (node.getText() != null && expected.contentEquals(node.getText())) {
                    try { assertTrue("Could not click " + expected, node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); }
                    finally { node.recycle(); }
                    return;
                }
                node.recycle();
            }
        } finally { root.recycle(); }
        throw new AssertionError("No exact visible button with text: " + expected);
    }

    private void scrollActiveWindowToBottom() {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .getRootInActiveWindow();
        assertNotNull("Management window should be active", root);
        AccessibilityNodeInfo scrollable = findScrollableNode(root);
        root.recycle();
        assertNotNull("Family management should expose a scrollable list", scrollable);
        try {
            for (int i = 0; i < 20; i++) {
                if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break;
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
        } finally {
            scrollable.recycle();
        }
    }

    private static AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node.isScrollable()) return AccessibilityNodeInfo.obtain(node);
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            AccessibilityNodeInfo found = findScrollableNode(child);
            child.recycle();
            if (found != null) return found;
        }
        return null;
    }

    private boolean hasExactText(String expected) {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        if (root == null) return false;
        try {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(expected)) {
                boolean exact = node.getText() != null && expected.contentEquals(node.getText());
                node.recycle();
                if (exact) return true;
            }
            return false;
        } finally { root.recycle(); }
    }

    private static Properties readProperties(File file) throws IOException {
        Properties properties = new Properties();
        try (FileInputStream in = new FileInputStream(file)) { properties.load(in); }
        return properties;
    }

    private static void writeProperties(File file, Properties properties) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            properties.store(out, "instrumentation fixture");
            out.getFD().sync();
        }
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

    @Test public void timeOverlayRespectsTheSpecialPermissionAndAnActiveChildSession() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean originalPermission = FamilyTimeOverlay.hasPermission(target);
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
        boolean[] attached = new boolean[1];
        try {
            setOverlayAppOp("ignore");
            assertFalse("The overlay app-op should be denied in this part of the test",
                    FamilyTimeOverlay.hasPermission(target));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> attached[0] = overlay.update(
                    overlayStore.snapshot(now.toLocalDate()), now.toLocalDate(), true, true, null));
            assertFalse("Denied overlay permission must prevent attachment", attached[0]);

            setOverlayAppOp("allow");
            assertTrue("The test must explicitly allow the overlay", FamilyTimeOverlay.hasPermission(target));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> attached[0] = overlay.update(
                    overlayStore.snapshot(now.toLocalDate()), now.toLocalDate(), true, true, null));
            assertTrue("An active session should attach after explicit authorization", attached[0]);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(overlay::hide);
            setOverlayAppOp(originalPermission ? "allow" : "ignore");
        }
    }

    @Test public void compactTimeOverlayShowsOneSummaryAndRefreshesProgress() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean originalPermission = FamilyTimeOverlay.hasPermission(target);
        FamilyProfileStore overlayStore = new FamilyProfileStore(overlayTestFile);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Lisbon"));
        long wall = now.toInstant().toEpochMilli();
        long elapsed = SystemClock.elapsedRealtime();
        long uptime = SystemClock.uptimeMillis();
        assertTrue(overlayStore.setEnabled(true));
        assertTrue(overlayStore.startSession(FamilyProfileStore.Profile.CHILD_TV,
                now.toLocalDate(), wall, elapsed, uptime, true));
        assertTrue(overlayStore.setSessionForeground(true, "test.tv.app", now.toLocalDate(),
                wall, elapsed, uptime).saved);

        FamilyTimeOverlay overlay = new FamilyTimeOverlay(target);
        try {
            setOverlayAppOp("allow");
            assertTrue("The emulator test requires the overlay app-op to be granted",
                    FamilyTimeOverlay.hasPermission(target));
            boolean[] attached = new boolean[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> attached[0] = overlay.update(
                    overlayStore.snapshot(now.toLocalDate()), now.toLocalDate(), true, true, null));
            assertTrue("Active child session should attach the time bar", attached[0]);

            LinearLayout card = field(overlay, "card", LinearLayout.class);
            assertEquals("One summary row, progress track, and hidden notice", 3, card.getChildCount());
            assertTrue("Card width should be compact", field(overlay, "layoutParams",
                    WindowManager.LayoutParams.class).width <= Math.round(300f
                    * target.getResources().getDisplayMetrics().density));
            TextView used = field(overlay, "usedText", TextView.class);
            TextView separator = field(overlay, "separatorText", TextView.class);
            TextView remaining = field(overlay, "remainingText", TextView.class);
            assertEquals(View.VISIBLE, used.getVisibility());
            assertEquals(View.VISIBLE, separator.getVisibility());
            assertEquals(View.VISIBLE, remaining.getVisibility());
            assertFalse("Profile label must not be part of the overlay description",
                    card.getContentDescription().toString().contains(target.getString(R.string.family_profile_child)));

            View progressTrack = field(overlay, "progressTrack", View.class);
            assertEquals(Math.round(4f * target.getResources().getDisplayMetrics().density),
                    progressTrack.getLayoutParams().height);
            int before = intField(progressTrack, "progress");
            CharSequence beforeSummary = used.getText();

            long fiveMinutes = 5L * FamilyTimePolicy.MINUTE_MS;
            assertTrue(overlayStore.setSessionForeground(true, "test.tv.app", now.toLocalDate(),
                    wall + fiveMinutes, elapsed + fiveMinutes, uptime + fiveMinutes).saved);
            FamilyProfileStore.Snapshot updated = overlayStore.snapshot(now.toLocalDate());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> attached[0] = overlay.update(
                    updated, now.toLocalDate(), true, true, null));
            assertTrue(attached[0]);
            assertFalse("Displayed used time should refresh",
                    beforeSummary.toString().equals(used.getText().toString()));
            assertTrue("Progress should advance after five minutes", intField(progressTrack, "progress") > before);

            long diagnosticStartWall = wall + fiveMinutes;
            long diagnosticStartElapsed = elapsed + fiveMinutes;
            long diagnosticStartUptime = uptime + fiveMinutes;
            assertTrue(overlayStore.finishSession(now.toLocalDate(), diagnosticStartWall,
                    diagnosticStartElapsed, diagnosticStartUptime).saved);
            assertTrue(overlayStore.startDiagnosticSession(FamilyProfileStore.Profile.CHILD_TV,
                    "test.tv.app", 2, now.toLocalDate(), diagnosticStartWall,
                    diagnosticStartElapsed, diagnosticStartUptime, true));
            assertTrue(overlayStore.setSessionForeground(true, "test.tv.app", now.toLocalDate(),
                    diagnosticStartWall, diagnosticStartElapsed, diagnosticStartUptime).saved);
            FamilyProfileStore.Snapshot diagnosticStart = overlayStore.snapshot(now.toLocalDate());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> attached[0] = overlay.update(
                    diagnosticStart, now.toLocalDate(), true, true, null));
            assertTrue(attached[0]);
            assertEquals(target.getString(R.string.family_time_bar_diagnostic_used, 0, 2), used.getText());

            assertTrue(overlayStore.recordSession(now.toLocalDate(),
                    diagnosticStartWall + FamilyTimePolicy.MINUTE_MS,
                    diagnosticStartElapsed + FamilyTimePolicy.MINUTE_MS,
                    diagnosticStartUptime + FamilyTimePolicy.MINUTE_MS).saved);
            FamilyProfileStore.Snapshot diagnosticProgress = overlayStore.snapshot(now.toLocalDate());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> attached[0] = overlay.update(
                    diagnosticProgress, now.toLocalDate(), true, true, null));
            assertEquals(target.getString(R.string.family_time_bar_diagnostic_used, 1, 2), used.getText());
            assertEquals(50, intField(progressTrack, "progress"));
            assertEquals("Diagnostic minutes must not be added to the real daily balance",
                    fiveMinutes, overlayStore.snapshot(now.toLocalDate()).childUsedMs);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(overlay::hide);
            setOverlayAppOp(originalPermission ? "allow" : "ignore");
        }
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

    private void assertProfileCard(int id) throws Exception {
        AccessibilityNodeInfo card = awaitViewById(id);
        assertNotNull("Profile card is missing: " + id, card);
        try {
            assertNotNull("Profile card has no accessible description: " + id,
                    card.getContentDescription());
        } finally {
            card.recycle();
        }
    }

    private void assertProfileCardsAreHorizontal() throws Exception {
        AccessibilityNodeInfo adult = awaitViewById(R.id.familyProfileAdult);
        AccessibilityNodeInfo child = awaitViewById(R.id.familyProfileChild);
        AccessibilityNodeInfo xbox = awaitViewById(R.id.familyProfileXbox);
        assertNotNull(adult);
        assertNotNull(child);
        assertNotNull(xbox);
        try {
            Rect adultBounds = new Rect();
            Rect childBounds = new Rect();
            Rect xboxBounds = new Rect();
            adult.getBoundsInScreen(adultBounds);
            child.getBoundsInScreen(childBounds);
            xbox.getBoundsInScreen(xboxBounds);
            assertTrue("Profile cards should be laid out left to right",
                    adultBounds.left < childBounds.left && childBounds.left < xboxBounds.left);
        } finally {
            adult.recycle();
            child.recycle();
            xbox.recycle();
        }
    }

    private void assertProfileArtworkResourcesAreSquare() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int[] artworkResources = {
                R.drawable.family_profile_adult,
                R.drawable.family_profile_child,
                R.drawable.family_profile_xbox
        };
        for (int resource : artworkResources) {
            Bitmap artwork = BitmapFactory.decodeResource(target.getResources(), resource);
            assertNotNull("Profile artwork must be packaged locally", artwork);
            try {
                assertEquals("Profile artwork should be square", artwork.getWidth(), artwork.getHeight());
                assertTrue("Profile artwork should have usable TV resolution", artwork.getWidth() >= 200);
            } finally {
                artwork.recycle();
            }
        }
    }

    private AccessibilityNodeInfo awaitViewById(int id) throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String resourceName = target.getResources().getResourceName(id);
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                java.util.List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(resourceName);
                root.recycle();
                if (matches != null && !matches.isEmpty()) {
                    AccessibilityNodeInfo match = matches.get(0);
                    for (int i = 1; i < matches.size(); i++) matches.get(i).recycle();
                    return match;
                }
            }
            Thread.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    private AccessibilityNodeInfo awaitFocusedViewById(int id) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        do {
            AccessibilityNodeInfo node = awaitViewById(id);
            if (node != null && node.isFocused()) return node;
            if (node != null) node.recycle();
            Thread.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    private String currentFocusDescription() {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .getRootInActiveWindow();
        AccessibilityNodeInfo focused = root == null ? null
                : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        String description = focused == null ? "none"
                : focused.getViewIdResourceName() + " text=" + focused.getText()
                        + " contentDescription=" + focused.getContentDescription();
        if (focused != null) focused.recycle();
        if (root != null) root.recycle();
        return description;
    }

    private String getString(int resource) {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getString(resource);
    }

    private static void setOverlayAppOp(String mode) throws IOException {
        if (!"allow".equals(mode) && !"ignore".equals(mode)) {
            throw new IllegalArgumentException("Unsupported app-op mode");
        }
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String command = "appops set " + target.getPackageName() + " SYSTEM_ALERT_WINDOW " + mode;
        try (java.io.InputStream output = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[256];
            while (output.read(buffer) != -1) { /* Wait until the shell command has completed. */ }
        }
    }

    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(object));
    }

    private static int intField(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(object);
    }

    private String visibleText() {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        StringBuilder text = new StringBuilder();
        appendText(root, text);
        if (root != null) root.recycle();
        return text.toString();
    }

    private static void appendText(AccessibilityNodeInfo node, StringBuilder output) {
        if (node == null) return;
        CharSequence value = node.getText();
        if (value != null) output.append(value).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) appendText(node.getChild(i), output);
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
