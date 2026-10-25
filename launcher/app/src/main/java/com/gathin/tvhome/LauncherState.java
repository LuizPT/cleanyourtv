package com.gathin.tvhome;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Private, offline state for the Home dock and parental controls. */
final class LauncherState {
    static final int PIN_MIN = 4;
    static final int PIN_MAX = 6;
    static final int PIN_ITERATIONS = 100_000;
    static final long FIRST_LOCK_MS = 30_000L;
    static final long MAX_LOCK_MS = 30L * 60L * 1000L;

    enum PinResult { MATCH, FAILED, LOCKED, NOT_CONFIGURED, INVALID, ERROR }

    private static final String DOCK_ORDER = "dock_order";
    private static final String PROTECTED_APPS = "protected_apps";
    private static final String HIDDEN_APPS = "hidden_apps_user";
    private static final String SHOWN_APPS = "shown_apps_user";
    private static final String CONTEXT_HINT_SHOWN = "context_hint_shown";
    private static final String PIN_SALT = "pin_salt";
    private static final String PIN_HASH = "pin_hash";
    private static final String SEQUENCE_SALT = "unlock_sequence_salt";
    private static final String SEQUENCE_HASH = "unlock_sequence_hash";
    private static final String SEQUENCE_CONFIGURED = "unlock_sequence_configured";
    private static final String FAILED_ATTEMPTS = "failed_attempts";
    private static final String LOCKED_UNTIL = "locked_until";

    private final File file;
    private final SecureRandom random = new SecureRandom();
    private boolean storageError;

    LauncherState(File file) {
        this.file = file;
    }

    synchronized List<String> dockOrder(List<String> defaults) {
        Properties p = read();
        String stored = p.getProperty(DOCK_ORDER);
        return stored == null ? clean(defaults) : parseList(stored);
    }

    synchronized boolean saveDockOrder(List<String> packages) {
        Properties p = read();
        if (storageError) return false;
        p.setProperty(DOCK_ORDER, join(clean(packages)));
        return write(p);
    }

    synchronized Set<String> protectedApps() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(parseList(read().getProperty(PROTECTED_APPS, ""))));
    }

    synchronized boolean saveProtectedApps(Set<String> packages) {
        Properties p = read();
        if (storageError) return false;
        p.setProperty(PROTECTED_APPS, join(clean(packages)));
        return write(p);
    }

    /** Effective hidden apps, including config defaults and explicit user overrides. */
    synchronized Set<String> effectiveHiddenApps(Set<String> configuredHidden) {
        Properties p = read();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (configuredHidden != null) result.addAll(clean(configuredHidden));
        result.addAll(parseList(p.getProperty(HIDDEN_APPS, "")));
        result.removeAll(parseList(p.getProperty(SHOWN_APPS, "")));
        return Collections.unmodifiableSet(result);
    }

    /** User visibility overrides are kept apart from config defaults so old settings remain intact. */
    synchronized boolean setAppHidden(String pkg, boolean hidden, Set<String> configuredHidden) {
        if (pkg == null || pkg.trim().isEmpty()) return false;
        Properties p = read();
        if (storageError) return false;
        LinkedHashSet<String> userHidden = new LinkedHashSet<>(parseList(p.getProperty(HIDDEN_APPS, "")));
        LinkedHashSet<String> userShown = new LinkedHashSet<>(parseList(p.getProperty(SHOWN_APPS, "")));
        String key = pkg.trim();
        if (hidden) {
            userHidden.add(key);
            userShown.remove(key);
        } else {
            userHidden.remove(key);
            if (configuredHidden != null && configuredHidden.contains(key)) userShown.add(key);
            else userShown.remove(key);
        }
        p.setProperty(HIDDEN_APPS, join(clean(userHidden)));
        p.setProperty(SHOWN_APPS, join(clean(userShown)));
        return write(p);
    }

    /** Mark the long-OK hint before showing it so a storage failure cannot make it repeat. */
    synchronized boolean markContextHintShown() {
        Properties p = read();
        if (storageError || Boolean.parseBoolean(p.getProperty(CONTEXT_HINT_SHOWN, "false"))) return false;
        p.setProperty(CONTEXT_HINT_SHOWN, "true");
        return write(p);
    }

    synchronized boolean isPinConfigured() {
        Properties p = read();
        return storageError || p.getProperty(PIN_SALT) != null || p.getProperty(PIN_HASH) != null;
    }

    /** True only when both parts of the new credential exist and are structurally valid. */
    synchronized boolean isUnlockSequenceConfigured() {
        Properties p = read();
        if (storageError) return false;
        String salt = p.getProperty(SEQUENCE_SALT);
        String hash = p.getProperty(SEQUENCE_HASH);
        String configured = p.getProperty(SEQUENCE_CONFIGURED);
        boolean initialized = "true".equals(configured);
        if (configured != null && !initialized && !"false".equals(configured)) {
            storageError = true;
            return false;
        }
        if (salt == null && hash == null) {
            if (initialized) storageError = true;
            return false;
        }
        if (!initialized || salt == null || hash == null) {
            storageError = true;
            return false;
        }
        try {
            byte[] decodedSalt = java.util.Base64.getDecoder().decode(salt);
            byte[] decodedHash = java.util.Base64.getDecoder().decode(hash);
            boolean valid = decodedSalt.length == 16 && decodedHash.length == 32;
            Arrays.fill(decodedSalt, (byte) 0);
            Arrays.fill(decodedHash, (byte) 0);
            if (!valid) storageError = true;
            return valid;
        } catch (IllegalArgumentException e) {
            storageError = true;
            return false;
        }
    }

    /** Legacy PIN remains present for the recovery APK; the current UI authenticates with a sequence. */
    synchronized boolean hasParentalCredential() {
        if (isUnlockSequenceConfigured()) return true;
        if (storageError) return false;
        boolean hasLegacyPin = isPinConfigured();
        return hasLegacyPin && !storageError;
    }

    synchronized boolean hasStorageError() {
        return storageError;
    }

    synchronized long lockRemainingMs(long nowMillis) {
        long until = longValue(read().getProperty(LOCKED_UNTIL), 0L);
        return Math.max(0L, until - nowMillis);
    }

    synchronized boolean setPin(char[] pin) {
        if (!validPin(pin)) return false;
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash;
        try {
            hash = derive(pin, salt);
        } catch (Exception e) {
            Arrays.fill(salt, (byte) 0);
            return false;
        }
        Properties p = read();
        if (storageError) {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(hash, (byte) 0);
            return false;
        }
        p.setProperty(PIN_SALT, java.util.Base64.getEncoder().encodeToString(salt));
        p.setProperty(PIN_HASH, java.util.Base64.getEncoder().encodeToString(hash));
        p.setProperty(FAILED_ATTEMPTS, "0");
        p.setProperty(LOCKED_UNTIL, "0");
        boolean saved = write(p);
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(hash, (byte) 0);
        return saved;
    }

    /** Persists a D-pad sequence without changing or deleting the legacy PIN used by the recovery APK. */
    synchronized boolean setUnlockSequence(char[] sequence) {
        if (!validUnlockSequence(sequence)) return false;
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash;
        try {
            hash = derive(sequence, salt);
        } catch (Exception e) {
            Arrays.fill(salt, (byte) 0);
            return false;
        }
        Properties p = read();
        if (storageError) {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(hash, (byte) 0);
            return false;
        }
        p.setProperty(SEQUENCE_SALT, java.util.Base64.getEncoder().encodeToString(salt));
        p.setProperty(SEQUENCE_HASH, java.util.Base64.getEncoder().encodeToString(hash));
        p.setProperty(SEQUENCE_CONFIGURED, "true");
        p.setProperty(FAILED_ATTEMPTS, "0");
        p.setProperty(LOCKED_UNTIL, "0");
        boolean saved = write(p);
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(hash, (byte) 0);
        return saved;
    }

    synchronized PinResult verifyPin(char[] pin, long nowMillis) {
        if (!validPin(pin)) return PinResult.INVALID;
        return verifyCredential(pin, nowMillis, PIN_SALT, PIN_HASH);
    }

    /** Used only by the one-time migration screen before a sequence has been saved. */
    synchronized PinResult verifyUnlockSequence(char[] sequence, long nowMillis) {
        if (!validUnlockSequence(sequence)) return PinResult.INVALID;
        return verifyCredential(sequence, nowMillis, SEQUENCE_SALT, SEQUENCE_HASH);
    }

    private PinResult verifyCredential(char[] credential, long nowMillis, String saltKey, String hashKey) {
        Properties p = read();
        String saltText = p.getProperty(saltKey);
        String hashText = p.getProperty(hashKey);
        if (storageError) return PinResult.ERROR;
        if (saltText == null && hashText == null) return PinResult.NOT_CONFIGURED;
        if (saltText == null || hashText == null) {
            storageError = true;
            return PinResult.ERROR;
        }
        long lockedUntil = longValue(p.getProperty(LOCKED_UNTIL), 0L);
        if (nowMillis < lockedUntil) return PinResult.LOCKED;

        byte[] salt;
        byte[] expected;
        try {
            salt = java.util.Base64.getDecoder().decode(saltText);
            expected = java.util.Base64.getDecoder().decode(hashText);
        } catch (IllegalArgumentException e) {
            storageError = true;
            return PinResult.ERROR;
        }
        if (salt.length != 16 || expected.length != 32) {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            storageError = true;
            return PinResult.ERROR;
        }
        byte[] actual;
        try {
            actual = derive(credential, salt);
        } catch (Exception e) {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            storageError = true;
            return PinResult.ERROR;
        }
        boolean match = MessageDigest.isEqual(expected, actual);
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(expected, (byte) 0);
        Arrays.fill(actual, (byte) 0);
        if (match) {
            p.setProperty(FAILED_ATTEMPTS, "0");
            p.setProperty(LOCKED_UNTIL, "0");
            return write(p) ? PinResult.MATCH : PinResult.ERROR;
        }

        int failures = Math.min(30, intValue(p.getProperty(FAILED_ATTEMPTS), 0) + 1);
        p.setProperty(FAILED_ATTEMPTS, Integer.toString(failures));
        if (failures >= 3) {
            int shift = Math.min(10, failures - 3);
            long delay = Math.min(MAX_LOCK_MS, FIRST_LOCK_MS << shift);
            long until = nowMillis > Long.MAX_VALUE - delay ? Long.MAX_VALUE : nowMillis + delay;
            p.setProperty(LOCKED_UNTIL, Long.toString(until));
            return write(p) ? PinResult.LOCKED : PinResult.ERROR;
        }
        return write(p) ? PinResult.FAILED : PinResult.ERROR;
    }

    static boolean validPin(char[] pin) {
        if (pin == null || pin.length < PIN_MIN || pin.length > PIN_MAX) return false;
        for (char c : pin) if (c < '0' || c > '9') return false;
        return true;
    }

    static boolean validUnlockSequence(char[] sequence) {
        if (sequence == null || sequence.length < 10 || sequence.length > 16) return false;
        for (char direction : sequence) {
            if (direction != 'U' && direction != 'D' && direction != 'L' && direction != 'R') return false;
        }
        return true;
    }

    static boolean samePin(char[] first, char[] second) {
        return sameSecret(first, second);
    }

    static boolean sameUnlockSequence(char[] first, char[] second) {
        return sameSecret(first, second);
    }

    private static boolean sameSecret(char[] first, char[] second) {
        if (first == null || second == null) return false;
        byte[] a = new byte[first.length];
        byte[] b = new byte[second.length];
        for (int i = 0; i < first.length; i++) a[i] = (byte) first[i];
        for (int i = 0; i < second.length; i++) b[i] = (byte) second[i];
        boolean same = MessageDigest.isEqual(a, b);
        Arrays.fill(a, (byte) 0);
        Arrays.fill(b, (byte) 0);
        return same;
    }

    static List<String> moveDock(List<String> current, String pkg, int delta) {
        List<String> moved = current == null ? new ArrayList<>() : new ArrayList<>(current);
        int from = moved.indexOf(pkg);
        int to = from + delta;
        if (from >= 0 && to >= 0 && to < moved.size()) Collections.swap(moved, from, to);
        return moved;
    }

    static boolean requiresPin(Set<String> protectedPackages, String pkg) {
        return pkg != null && protectedPackages != null && protectedPackages.contains(pkg);
    }

    private static byte[] derive(char[] pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, PIN_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private Properties read() {
        Properties p = new Properties();
        storageError = false;
        if (file == null) {
            storageError = true;
            return p;
        }
        if (!file.exists()) return p;
        if (!file.isFile()) {
            storageError = true;
            return p;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (Exception ignored) {
            // Keep the launcher fail-closed when parental state exists but cannot be read.
            storageError = true;
        }
        return p;
    }

    private boolean write(Properties p) {
        if (file == null) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            storageError = true;
            return false;
        }
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            p.store(out, "CleanYourTV private Home settings");
            out.getFD().sync();
        } catch (IOException e) {
            temp.delete();
            storageError = true;
            return false;
        }
        try {
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            storageError = false;
            return true;
        } catch (IOException e) {
            temp.delete();
            storageError = true;
            return false;
        }
    }

    private static List<String> clean(Iterable<String> values) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (values != null) for (String value : values) {
            if (value != null) {
                String item = value.trim();
                if (!item.isEmpty() && item.indexOf(',') < 0 && item.indexOf('\n') < 0) clean.add(item);
            }
        }
        return new ArrayList<>(clean);
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isEmpty()) return new ArrayList<>();
        return clean(Arrays.asList(value.split(",", -1)));
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    private static int intValue(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static long longValue(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }
}
