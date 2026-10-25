package com.gathin.tvhome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LauncherStateTest {
    private File file;

    @Before
    public void setUp() throws Exception {
        file = File.createTempFile("launcher-state", ".properties");
        assertTrue(file.delete());
    }

    @After
    public void tearDown() {
        if (file != null) {
            file.delete();
            new File(file.getPath() + ".tmp").delete();
        }
    }

    @Test
    public void dockOrderPersistsAcrossStoreInstances() {
        LauncherState first = new LauncherState(file);
        List<String> defaults = Arrays.asList("com.example.a", "com.example.b");
        assertEquals(defaults, first.dockOrder(defaults));
        assertTrue(first.saveDockOrder(Arrays.asList("com.example.b", "com.example.a", "com.example.b")));

        assertEquals(Arrays.asList("com.example.b", "com.example.a"), new LauncherState(file).dockOrder(defaults));
    }

    @Test
    public void dockOrderCanMoveWithoutWrappingAtEnds() {
        List<String> original = Arrays.asList("com.example.a", "com.example.b", "com.example.c");
        assertEquals(Arrays.asList("com.example.b", "com.example.a", "com.example.c"),
                LauncherState.moveDock(original, "com.example.b", -1));
        assertEquals(Arrays.asList("com.example.a", "com.example.c", "com.example.b"),
                LauncherState.moveDock(original, "com.example.b", 1));
        assertEquals(original, LauncherState.moveDock(original, "com.example.a", -1));
        assertEquals(original, LauncherState.moveDock(original, "com.example.c", 1));
        assertEquals(original, LauncherState.moveDock(original, "com.example.missing", 1));
    }

    @Test
    public void protectedAppsPersistSeparatelyFromDockOrder() {
        LauncherState first = new LauncherState(file);
        LinkedHashSet<String> protectedApps = new LinkedHashSet<>(Arrays.asList("com.example.video", "com.example.games"));
        assertTrue(first.saveProtectedApps(protectedApps));
        assertEquals(protectedApps, new LauncherState(file).protectedApps());
        assertEquals(Arrays.asList("com.example.default"), new LauncherState(file).dockOrder(Arrays.asList("com.example.default")));
    }

    @Test
    public void userVisibilityOverridesPersistWithoutReplacingConfigOrOtherState() {
        LauncherState first = new LauncherState(file);
        LinkedHashSet<String> configuredHidden = new LinkedHashSet<>(Arrays.asList("com.example.default_hidden"));
        assertTrue(first.saveDockOrder(Arrays.asList("com.example.video", "com.example.games")));
        assertTrue(first.saveProtectedApps(new LinkedHashSet<>(Arrays.asList("com.example.video"))));
        assertTrue(first.setAppHidden("com.example.default_hidden", false, configuredHidden));
        assertTrue(first.setAppHidden("com.example.video", true, configuredHidden));

        LauncherState reloaded = new LauncherState(file);
        assertEquals(new LinkedHashSet<>(Arrays.asList("com.example.video")),
                reloaded.effectiveHiddenApps(configuredHidden));
        assertEquals(Arrays.asList("com.example.video", "com.example.games"), reloaded.dockOrder(Collections.emptyList()));
        assertEquals(new LinkedHashSet<>(Arrays.asList("com.example.video")), reloaded.protectedApps());

        assertTrue(reloaded.setAppHidden("com.example.video", false, configuredHidden));
        assertEquals(Collections.emptySet(), new LauncherState(file).effectiveHiddenApps(configuredHidden));
    }

    @Test
    public void contextHintIsPersistedAndShownOnlyOnce() {
        LauncherState first = new LauncherState(file);
        assertTrue(first.markContextHintShown());
        assertFalse(new LauncherState(file).markContextHintShown());
    }

    @Test
    public void contextualActionsAreCompactAndKeepAppInfoLast() {
        assertEquals(Arrays.asList(AppActionPolicy.Action.MOVE, AppActionPolicy.Action.HIDE,
                        AppActionPolicy.Action.PROTECT, AppActionPolicy.Action.APP_INFO),
                AppActionPolicy.contextActions(false, false, false, false));
        assertEquals(Arrays.asList(AppActionPolicy.Action.MOVE, AppActionPolicy.Action.REMOVE_FROM_HOME,
                        AppActionPolicy.Action.SHOW, AppActionPolicy.Action.UNPROTECT, AppActionPolicy.Action.APP_INFO),
                AppActionPolicy.contextActions(true, true, true, true));
        assertEquals(Arrays.asList(AppActionPolicy.Action.ADD_TO_HOME, AppActionPolicy.Action.HIDE,
                        AppActionPolicy.Action.PROTECT, AppActionPolicy.Action.APP_INFO),
                AppActionPolicy.contextActions(true, false, false, false));
    }

    @Test
    public void libraryStatusesKeepHiddenAndProtectedIndependent() {
        assertEquals(Arrays.asList(AppActionPolicy.Status.HIDDEN, AppActionPolicy.Status.PROTECTED),
                AppActionPolicy.statuses(true, true));
        assertEquals(Arrays.asList(AppActionPolicy.Status.ON_HOME), AppActionPolicy.statuses(false, false));
    }

    @Test
    public void accessPolicyProtectsOnlySelectedPackages() {
        LinkedHashSet<String> protectedApps = new LinkedHashSet<>(Arrays.asList("com.example.video"));
        assertTrue(LauncherState.requiresPin(protectedApps, "com.example.video"));
        assertFalse(LauncherState.requiresPin(protectedApps, "com.example.hidden"));
        assertFalse(LauncherState.requiresPin(protectedApps, null));
    }

    @Test
    public void pinIsDerivedAndVerifiedAfterReload() throws Exception {
        char[] pin = "4826".toCharArray();
        LauncherState first = new LauncherState(file);
        assertFalse(first.isPinConfigured());
        assertFalse(first.setPin("12a4".toCharArray()));
        assertTrue(first.setPin(pin));

        String persisted = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse(persisted.contains("4826"));
        LauncherState reloaded = new LauncherState(file);
        assertTrue(reloaded.isPinConfigured());
        assertEquals(LauncherState.PinResult.MATCH, reloaded.verifyPin("4826".toCharArray(), 1_000L));
        assertEquals(LauncherState.PinResult.FAILED, reloaded.verifyPin("5639".toCharArray(), 2_000L));
    }

    @Test
    public void failedPinAttemptsCauseIncreasingPersistentLockouts() {
        LauncherState first = new LauncherState(file);
        assertTrue(first.setPin("704918".toCharArray()));
        assertEquals(LauncherState.PinResult.FAILED, first.verifyPin("9185".toCharArray(), 1_000L));
        assertEquals(LauncherState.PinResult.FAILED, first.verifyPin("9185".toCharArray(), 2_000L));
        assertEquals(LauncherState.PinResult.LOCKED, first.verifyPin("9185".toCharArray(), 3_000L));
        assertEquals(LauncherState.PinResult.LOCKED, new LauncherState(file).verifyPin("704918".toCharArray(), 32_999L));
        assertEquals(LauncherState.PinResult.LOCKED, new LauncherState(file).verifyPin("9185".toCharArray(), 33_000L));
        assertEquals(LauncherState.PinResult.LOCKED, new LauncherState(file).verifyPin("531807".toCharArray(), 92_999L));
        assertEquals(LauncherState.PinResult.MATCH, new LauncherState(file).verifyPin("704918".toCharArray(), 93_000L));
    }

    @Test
    public void corruptPinRecordFailsClosed() throws Exception {
        LauncherState first = new LauncherState(file);
        assertTrue(first.setPin("704918".toCharArray()));
        Properties stored = new Properties();
        try (FileInputStream in = new FileInputStream(file)) { stored.load(in); }
        stored.setProperty("pin_hash", "not-base64");
        try (FileOutputStream out = new FileOutputStream(file)) { stored.store(out, "test corruption"); }

        LauncherState reloaded = new LauncherState(file);
        assertTrue(reloaded.isPinConfigured());
        assertEquals(LauncherState.PinResult.ERROR, reloaded.verifyPin("704918".toCharArray(), 1_000L));
        assertTrue(reloaded.hasStorageError());
    }

    @Test
    public void validatesLengthAndMatchesConfirmationInConstantTime() {
        assertFalse(LauncherState.validPin(null));
        assertFalse(LauncherState.validPin("835".toCharArray()));
        assertFalse(LauncherState.validPin("7049183".toCharArray()));
        assertFalse(LauncherState.validPin("12x4".toCharArray()));
        assertTrue(LauncherState.validPin("7049".toCharArray()));
        assertTrue(LauncherState.validPin("70491".toCharArray()));
        assertTrue(LauncherState.validPin("704918".toCharArray()));
        assertTrue(LauncherState.samePin("704918".toCharArray(), "704918".toCharArray()));
        assertFalse(LauncherState.samePin("704918".toCharArray(), "704919".toCharArray()));
    }

    @Test
    public void unlockSequenceIsDerivedPersistedAndLeavesRecoveryPinUntouched() throws Exception {
        char[] oldPin = "704918".toCharArray();
        char[] sequence = "UURRDDLULRDU".toCharArray();
        LauncherState first = new LauncherState(file);
        assertTrue(first.setPin(oldPin));
        Properties before = readProperties();
        String recoveryHash = before.getProperty("pin_hash");
        String recoverySalt = before.getProperty("pin_salt");

        assertTrue(first.setUnlockSequence(sequence));
        Properties stored = readProperties();
        String serialized = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse("Sequence must not be stored in plain text", serialized.contains(new String(sequence)));
        assertEquals("Recovery APK PIN hash must remain unchanged", recoveryHash, stored.getProperty("pin_hash"));
        assertEquals("Recovery APK PIN salt must remain unchanged", recoverySalt, stored.getProperty("pin_salt"));

        LauncherState reloaded = new LauncherState(file);
        assertTrue(reloaded.isUnlockSequenceConfigured());
        assertEquals(LauncherState.PinResult.MATCH,
                reloaded.verifyUnlockSequence("UURRDDLULRDU".toCharArray(), 1_000L));
        assertEquals(LauncherState.PinResult.FAILED,
                reloaded.verifyUnlockSequence("UURRDDLULRDL".toCharArray(), 2_000L));
        assertEquals(LauncherState.PinResult.MATCH, reloaded.verifyPin(oldPin.clone(), 3_000L));
    }

    @Test
    public void sequenceAttemptsUsePersistentProgressiveLockoutAcrossReloads() {
        LauncherState first = new LauncherState(file);
        assertTrue(first.setUnlockSequence("UDLRUDLRUD".toCharArray()));
        assertEquals(LauncherState.PinResult.FAILED,
                first.verifyUnlockSequence("UDLRUDLRUU".toCharArray(), 1_000L));
        assertEquals(LauncherState.PinResult.FAILED,
                first.verifyUnlockSequence("UDLRUDLRUU".toCharArray(), 2_000L));
        assertEquals(LauncherState.PinResult.LOCKED,
                first.verifyUnlockSequence("UDLRUDLRUU".toCharArray(), 3_000L));
        assertEquals(LauncherState.PinResult.LOCKED,
                new LauncherState(file).verifyUnlockSequence("UDLRUDLRUD".toCharArray(), 32_999L));
        assertEquals(LauncherState.PinResult.MATCH,
                new LauncherState(file).verifyUnlockSequence("UDLRUDLRUD".toCharArray(), 33_000L));
    }

    @Test
    public void replacingSequenceInvalidatesThePreviousOne() {
        LauncherState first = new LauncherState(file);
        assertTrue(first.setUnlockSequence("UDLRUDLRUD".toCharArray()));
        assertTrue(first.setUnlockSequence("RRDDLLUUUR".toCharArray()));
        LauncherState reloaded = new LauncherState(file);
        assertEquals(LauncherState.PinResult.FAILED,
                reloaded.verifyUnlockSequence("UDLRUDLRUD".toCharArray(), 1_000L));
        assertEquals(LauncherState.PinResult.MATCH,
                new LauncherState(file).verifyUnlockSequence("RRDDLLUUUR".toCharArray(), 2_000L));
    }

    @Test
    public void malformedSequenceCredentialFailsClosedWithoutRemovingLegacyPin() throws Exception {
        LauncherState first = new LauncherState(file);
        assertTrue(first.setPin("704918".toCharArray()));
        assertTrue(first.setUnlockSequence("UDLRUDLRUD".toCharArray()));
        Properties stored = readProperties();
        stored.setProperty("unlock_sequence_hash", "not-base64");
        writeProperties(stored);

        LauncherState reloaded = new LauncherState(file);
        assertFalse(reloaded.isUnlockSequenceConfigured());
        assertTrue(reloaded.hasStorageError());
        assertEquals(LauncherState.PinResult.ERROR,
                reloaded.verifyUnlockSequence("UDLRUDLRUD".toCharArray(), 1_000L));
        assertTrue(reloaded.isPinConfigured());
    }

    @Test
    public void missingSequenceAfterMigrationCannotFallBackToRecoveryPin() throws Exception {
        LauncherState first = new LauncherState(file);
        assertTrue(first.setPin("704918".toCharArray()));
        assertTrue(first.setUnlockSequence("UDLRUDLRUD".toCharArray()));
        Properties stored = readProperties();
        stored.remove("unlock_sequence_salt");
        stored.remove("unlock_sequence_hash");
        writeProperties(stored);

        LauncherState reloaded = new LauncherState(file);
        assertFalse("A completed migration with missing sequence data must fail closed",
                reloaded.hasParentalCredential());
        assertTrue(reloaded.hasStorageError());
        assertTrue("Legacy PIN remains stored for APK recovery", readProperties().getProperty("pin_hash") != null);
    }

    @Test
    public void sequenceValidationAcceptsOnlyTenToSixteenDirections() {
        assertFalse(LauncherState.validUnlockSequence(null));
        assertFalse(LauncherState.validUnlockSequence("UDLRUDLRU".toCharArray()));
        assertTrue(LauncherState.validUnlockSequence("UDLRUDLRUD".toCharArray()));
        assertTrue(LauncherState.validUnlockSequence("UDLRUDLRUDLRUDLR".toCharArray()));
        assertFalse(LauncherState.validUnlockSequence("UDLRUDLRUDLRUDLRU".toCharArray()));
        assertFalse(LauncherState.validUnlockSequence("UDLRUDLRU1".toCharArray()));
        assertTrue(LauncherState.sameUnlockSequence("UDLRUDLRUD".toCharArray(), "UDLRUDLRUD".toCharArray()));
        assertFalse(LauncherState.sameUnlockSequence("UDLRUDLRUD".toCharArray(), "UDLRUDLRUU".toCharArray()));
    }

    @Test
    public void heldDpadKeyRepeatsDoNotAddExtraSequenceMoves() {
        assertTrue(DpadSequencePolicy.acceptsPress(0, 0));
        assertFalse(DpadSequencePolicy.acceptsPress(0, 1));
        assertFalse(DpadSequencePolicy.acceptsPress(0, 4));
        assertFalse(DpadSequencePolicy.acceptsPress(1, 0));
    }

    private Properties readProperties() throws Exception {
        Properties properties = new Properties();
        try (FileInputStream in = new FileInputStream(file)) { properties.load(in); }
        return properties;
    }

    private void writeProperties(Properties properties) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { properties.store(out, "test fixture"); }
    }
}
