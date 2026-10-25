package com.gathin.tvhome;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Private family settings and daily usage. A profile is saved only while a monitored app session is active. */
final class FamilyProfileStore {
    // Activity and monitor use distinct store instances; serialize file transactions between them.
    private static final Object STORAGE_FILE_LOCK = new Object();
    private static final String ENABLED = "family_enabled";
    private static final String XBOX_INPUT = "xbox_input_id";
    private static final String CLOCK_UNCERTAIN = "clock_uncertain";
    private static final String LAST_WALL = "last_wall_ms";
    private static final String LAST_ELAPSED = "last_elapsed_ms";
    private static final String LAST_DAY = "last_day";
    private static final String SESSION_PROFILE = "session_profile";
    private static final String SESSION_DAY = "session_day";
    private static final String SESSION_ELAPSED = "session_elapsed_ms";
    private static final String SESSION_UPTIME = "session_uptime_ms";
    private static final String SESSION_SCREEN_ON = "session_screen_on";
    private static final String SESSION_FOREGROUND = "session_foreground";
    private static final String SESSION_PACKAGE = "session_package";
    private static final String SESSION_PACKAGE_OBSERVED = "session_package_observed";
    private static final String SESSION_STARTED_WALL = "session_started_wall_ms";
    private static final String SESSION_CURSOR_WALL = "session_cursor_wall_ms";
    private static final String SESSION_QUERY_WALL = "session_query_wall_ms";
    private static final String SESSION_DIAGNOSTIC = "session_diagnostic";
    private static final String SESSION_DIAGNOSTIC_LIMIT = "session_diagnostic_limit_ms";
    private static final String SESSION_DIAGNOSTIC_USED = "session_diagnostic_used_ms";
    private static final String SESSION_DIAGNOSTIC_WARNED = "session_diagnostic_warned";
    private static final String MUST_RETURN_HOME = "must_return_home";
    private static final String TIME_BAR_VISIBLE = "time_bar_visible";
    private static final String TIME_BAR_CORNER = "time_bar_corner";
    private static final String TIME_BAR_CONTENT = "time_bar_content";
    private static final String CHILD_APPS_INITIALIZED = "child_apps_initialized";
    private static final String CHILD_APPS = "child_apps";
    private static final long CLOCK_TOLERANCE_MS = 2L * 60L * 1000L;
    private static final ZoneId FAMILY_ZONE = ZoneId.of("Europe/Lisbon");

    enum Profile { ADULT, CHILD_TV, XBOX }
    enum TimeBarCorner { LEFT, RIGHT }
    enum TimeBarContent { BOTH, USED, REMAINING }

    static final class Snapshot {
        final boolean enabled;
        final boolean clockUncertain;
        final boolean sessionPending;
        final boolean sessionScreenOn;
        final boolean sessionForeground;
        final boolean sessionPackageObserved;
        final boolean mustReturnHome;
        final Profile sessionProfile;
        final String xboxInputId;
        final String sessionPackage;
        final long sessionStartedWallMs;
        final long sessionCursorWallMs;
        final long sessionQueryWallMs;
        final long childUsedMs;
        final long childExtensionMs;
        final long xboxUsedMs;
        final long xboxExtensionMs;
        final boolean sessionDiagnostic;
        final long diagnosticLimitMs;
        final long diagnosticUsedMs;
        final FamilyTimePolicy.Schedule childSchedule;
        final FamilyTimePolicy.Schedule xboxSchedule;
        final boolean timeBarVisible;
        final TimeBarCorner timeBarCorner;
        final TimeBarContent timeBarContent;

        private Snapshot(Properties p, LocalDate day) {
            enabled = Boolean.parseBoolean(p.getProperty(ENABLED, "false"));
            clockUncertain = Boolean.parseBoolean(p.getProperty(CLOCK_UNCERTAIN, "false"));
            String profileValue = p.getProperty(SESSION_PROFILE);
            sessionPending = profileValue != null;
            Profile parsed = null;
            if (profileValue != null) {
                try { parsed = Profile.valueOf(profileValue); } catch (IllegalArgumentException ignored) { }
            }
            sessionProfile = parsed;
            sessionScreenOn = Boolean.parseBoolean(p.getProperty(SESSION_SCREEN_ON, "false"));
            sessionForeground = Boolean.parseBoolean(p.getProperty(SESSION_FOREGROUND, "false"));
            sessionPackageObserved = Boolean.parseBoolean(p.getProperty(SESSION_PACKAGE_OBSERVED, "false"));
            mustReturnHome = Boolean.parseBoolean(p.getProperty(MUST_RETURN_HOME, "false"));
            xboxInputId = p.getProperty(XBOX_INPUT, "");
            sessionPackage = p.getProperty(SESSION_PACKAGE, "");
            sessionStartedWallMs = number(p, SESSION_STARTED_WALL, 0L);
            sessionCursorWallMs = number(p, SESSION_CURSOR_WALL, 0L);
            sessionQueryWallMs = number(p, SESSION_QUERY_WALL, 0L);
            childUsedMs = number(p, usageKey(Profile.CHILD_TV, day), 0L);
            childExtensionMs = number(p, extensionKey(Profile.CHILD_TV, day), 0L);
            xboxUsedMs = number(p, usageKey(Profile.XBOX, day), 0L);
            xboxExtensionMs = number(p, extensionKey(Profile.XBOX, day), 0L);
            sessionDiagnostic = Boolean.parseBoolean(p.getProperty(SESSION_DIAGNOSTIC, "false"));
            diagnosticLimitMs = number(p, SESSION_DIAGNOSTIC_LIMIT, 0L);
            diagnosticUsedMs = number(p, SESSION_DIAGNOSTIC_USED, 0L);
            childSchedule = schedule(p, Profile.CHILD_TV, day.getDayOfWeek());
            xboxSchedule = schedule(p, Profile.XBOX, day.getDayOfWeek());
            timeBarVisible = Boolean.parseBoolean(p.getProperty(TIME_BAR_VISIBLE, "true"));
            timeBarCorner = enumValue(TimeBarCorner.class, p.getProperty(TIME_BAR_CORNER), TimeBarCorner.LEFT);
            timeBarContent = enumValue(TimeBarContent.class, p.getProperty(TIME_BAR_CONTENT), TimeBarContent.BOTH);
        }

        long remainingMs(Profile profile, LocalDate day) {
            if (profile == Profile.ADULT) return Long.MAX_VALUE;
            long base = (profile == Profile.XBOX ? xboxSchedule : childSchedule).budgetMs;
            long extra = profile == Profile.XBOX ? xboxExtensionMs : childExtensionMs;
            long used = profile == Profile.XBOX ? xboxUsedMs : childUsedMs;
            long budget = base > Long.MAX_VALUE - extra ? Long.MAX_VALUE : base + extra;
            return Math.max(0L, budget - Math.min(budget, used));
        }

        long diagnosticRemainingMs() {
            return Math.max(0L, diagnosticLimitMs - Math.min(diagnosticLimitMs, diagnosticUsedMs));
        }
    }

    static final class UsageUpdate {
        final long remainingMs;
        final List<Integer> warningsMinutes;
        final boolean saved;
        final boolean diagnostic;

        private UsageUpdate(long remainingMs, List<Integer> warningsMinutes, boolean saved, boolean diagnostic) {
            this.remainingMs = remainingMs;
            this.warningsMinutes = Collections.unmodifiableList(warningsMinutes);
            this.saved = saved;
            this.diagnostic = diagnostic;
        }
    }

    private final File file;
    private boolean storageError;

    FamilyProfileStore(File file) {
        this.file = file;
    }

    synchronized boolean isEnabled() {
        Properties p = read();
        // A read failure is fail-closed: the caller must require a profile before any launch.
        return storageError || Boolean.parseBoolean(p.getProperty(ENABLED, "false"));
    }

    synchronized boolean hasStorageError() {
        return storageError;
    }

    synchronized boolean setEnabled(boolean enabled) {
        synchronized (STORAGE_FILE_LOCK) {
        Properties p = read();
        if (storageError) return false;
        p.setProperty(ENABLED, Boolean.toString(enabled));
        if (!enabled) p.setProperty(MUST_RETURN_HOME, "false");
        return write(p);
        }
    }

    synchronized boolean setTimeBarVisible(boolean visible) {
        return setStringPreference(TIME_BAR_VISIBLE, Boolean.toString(visible));
    }

    synchronized boolean setTimeBarCorner(TimeBarCorner corner) {
        return corner != null && setStringPreference(TIME_BAR_CORNER, corner.name());
    }

    synchronized boolean setTimeBarContent(TimeBarContent content) {
        return content != null && setStringPreference(TIME_BAR_CONTENT, content.name());
    }

    private boolean setStringPreference(String key, String value) {
        synchronized (STORAGE_FILE_LOCK) {
        Properties p = read();
        if (storageError) return false;
        p.setProperty(key, value);
        return write(p);
        }
    }

    synchronized Snapshot snapshot(LocalDate day) {
        Properties p = read();
        if (storageError) {
            p.setProperty(ENABLED, "true");
            p.setProperty(CLOCK_UNCERTAIN, "true");
        }
        return new Snapshot(p, day);
    }

    synchronized boolean setXboxInputId(String inputId) {
        synchronized (STORAGE_FILE_LOCK) {
        Properties p = read();
        if (storageError || inputId == null || inputId.length() > 512) return false;
        String clean = inputId.trim();
        if (clean.isEmpty()) p.remove(XBOX_INPUT);
        else p.setProperty(XBOX_INPUT, clean);
        return write(p);
        }
    }

    /** Captures the apps currently visible in the launcher once, leaving later installs unapproved. */
    synchronized boolean initializeChildApps(Collection<String> visiblePackages) {
        Map<String, Long> packages = new HashMap<>();
        if (visiblePackages != null) for (String pkg : visiblePackages) packages.put(pkg, 0L);
        return initializeChildApps(packages);
    }

    synchronized boolean initializeChildApps(Map<String, Long> visiblePackages) {
        synchronized (STORAGE_FILE_LOCK) {
        Properties p = read();
        if (storageError) return false;
        if (Boolean.parseBoolean(p.getProperty(CHILD_APPS_INITIALIZED, "false"))) return true;
        Map<String, Long> valid = new HashMap<>();
        if (visiblePackages != null) {
            for (Map.Entry<String, Long> entry : visiblePackages.entrySet()) {
                if (validPackageName(entry.getKey()) && entry.getValue() != null && entry.getValue() >= 0L) {
                    valid.put(entry.getKey(), entry.getValue());
                }
            }
        }
        p.setProperty(CHILD_APPS, joinChildApprovals(valid));
        p.setProperty(CHILD_APPS_INITIALIZED, "true");
        return write(p);
        }
    }

    synchronized boolean childAppsInitialized() {
        Properties p = read();
        return !storageError && Boolean.parseBoolean(p.getProperty(CHILD_APPS_INITIALIZED, "false"));
    }

    synchronized Set<String> childAllowedApps() {
        Properties p = read();
        if (storageError || !Boolean.parseBoolean(p.getProperty(CHILD_APPS_INITIALIZED, "false"))) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(parseChildApprovals(p.getProperty(CHILD_APPS, "")).keySet()));
    }

    synchronized boolean isChildAppAllowed(String packageName) {
        if (!validPackageName(packageName)) return false;
        Properties p = read();
        return !storageError && Boolean.parseBoolean(p.getProperty(CHILD_APPS_INITIALIZED, "false"))
                && parseChildApprovals(p.getProperty(CHILD_APPS, "")).containsKey(packageName);
    }

    synchronized boolean isChildAppAllowed(String packageName, long firstInstallTime) {
        if (!validPackageName(packageName) || firstInstallTime < 0L) return false;
        Properties p = read();
        Long savedInstallTime = parseChildApprovals(p.getProperty(CHILD_APPS, "")).get(packageName);
        return !storageError && Boolean.parseBoolean(p.getProperty(CHILD_APPS_INITIALIZED, "false"))
                && savedInstallTime != null && savedInstallTime == firstInstallTime;
    }

    synchronized boolean setChildAppAllowed(String packageName, boolean allowed) {
        return setChildAppAllowed(packageName, 0L, allowed);
    }

    synchronized boolean setChildAppAllowed(String packageName, long firstInstallTime, boolean allowed) {
        synchronized (STORAGE_FILE_LOCK) {
        if (!validPackageName(packageName)) return false;
        if (firstInstallTime < 0L) return false;
        Properties p = read();
        if (storageError || !Boolean.parseBoolean(p.getProperty(CHILD_APPS_INITIALIZED, "false"))) return false;
        Map<String, Long> packages = parseChildApprovals(p.getProperty(CHILD_APPS, ""));
        boolean changed;
        if (allowed) {
            Long old = packages.put(packageName, firstInstallTime);
            changed = old == null || old != firstInstallTime;
        } else changed = packages.remove(packageName) != null;
        if (!changed) return true;
        p.setProperty(CHILD_APPS, joinChildApprovals(packages));
        return write(p);
        }
    }

    /** A full uninstall revokes approval; package updates retain it. */
    synchronized boolean removeChildAppApproval(String packageName) {
        synchronized (STORAGE_FILE_LOCK) {
        if (!validPackageName(packageName)) return false;
        Properties p = read();
        if (storageError || !Boolean.parseBoolean(p.getProperty(CHILD_APPS_INITIALIZED, "false"))) return !storageError;
        Map<String, Long> packages = parseChildApprovals(p.getProperty(CHILD_APPS, ""));
        if (packages.remove(packageName) == null) return true;
        p.setProperty(CHILD_APPS, joinChildApprovals(packages));
        return write(p);
        }
    }

    synchronized boolean addExtension(Profile profile, LocalDate day, int minutes) {
        synchronized (STORAGE_FILE_LOCK) {
        if (profile == null || day == null || minutes < 1 || minutes > 120) return false;
        Properties p = read();
        if (storageError) return false;
        String key = extensionKey(profile, day);
        long current = number(p, key, 0L);
        long addition = minutes * FamilyTimePolicy.MINUTE_MS;
        p.setProperty(key, Long.toString(saturatedAdd(current, addition)));
        return write(p);
        }
    }

    /** Persists a weekday override; invalid or overnight windows are rejected without mutation. */
    synchronized boolean setSchedule(Profile profile, DayOfWeek day, int startMinute,
                                     int endMinute, int limitMinutes) {
        synchronized (STORAGE_FILE_LOCK) {
        if (profile == null || profile == Profile.ADULT || day == null
                || startMinute < 0 || startMinute >= 24 * 60
                || endMinute <= startMinute || endMinute > 24 * 60
                || limitMinutes < 15 || limitMinutes > 480) return false;
        Properties p = read();
        if (storageError) return false;
        FamilyTimePolicy.Schedule defaults = FamilyTimePolicy.defaultSchedule(
                policyProfile(profile), day);
        String prefix = schedulePrefix(profile, day);
        if (startMinute == defaults.startMinute && endMinute == defaults.endMinute
                && limitMinutes * FamilyTimePolicy.MINUTE_MS == defaults.budgetMs) {
            p.remove(prefix + "start");
            p.remove(prefix + "end");
            p.remove(prefix + "limit");
        } else {
            p.setProperty(prefix + "start", Integer.toString(startMinute));
            p.setProperty(prefix + "end", Integer.toString(endMinute));
            p.setProperty(prefix + "limit", Integer.toString(limitMinutes));
        }
        return write(p);
        }
    }

    /** Reads seven local days from the one private properties snapshot. */
    synchronized Map<LocalDate, Long> usageHistory(Profile profile, LocalDate throughDay, int dayCount) {
        if (profile == null || profile == Profile.ADULT || throughDay == null || dayCount < 1 || dayCount > 31) {
            return Collections.emptyMap();
        }
        Properties p = read();
        if (storageError) return Collections.emptyMap();
        Map<LocalDate, Long> result = new TreeMap<>();
        LocalDate firstDay = throughDay.minusDays(dayCount - 1L);
        for (int i = 0; i < dayCount; i++) {
            LocalDate day = firstDay.plusDays(i);
            result.put(day, number(p, usageKey(profile, day), 0L));
        }
        return Collections.unmodifiableMap(result);
    }

    synchronized boolean observeClock(long wallMillis, long elapsedMillis, LocalDate day) {
        synchronized (STORAGE_FILE_LOCK) {
        if (day == null) return false;
        Properties p = read();
        if (storageError) return false;
        observeClock(p, wallMillis, elapsedMillis, day);
        return write(p) && !Boolean.parseBoolean(p.getProperty(CLOCK_UNCERTAIN, "false"));
        }
    }

    /** Adult authenticated this adjustment. Usage records remain intact. */
    synchronized boolean confirmClock(long wallMillis, long elapsedMillis, LocalDate day) {
        synchronized (STORAGE_FILE_LOCK) {
        if (day == null) return false;
        Properties p = read();
        if (storageError) return false;
        p.setProperty(CLOCK_UNCERTAIN, "false");
        p.setProperty(LAST_WALL, Long.toString(wallMillis));
        p.setProperty(LAST_ELAPSED, Long.toString(elapsedMillis));
        p.setProperty(LAST_DAY, day.toString());
        p.setProperty(MUST_RETURN_HOME, "false");
        return write(p);
        }
    }

    synchronized boolean startSession(Profile profile, LocalDate day, long wallMillis,
                                      long elapsedMillis, long uptimeMillis, boolean screenOn) {
        return startSession(profile, "", day, wallMillis, elapsedMillis, uptimeMillis, screenOn);
    }

    synchronized boolean startSession(Profile profile, String targetPackage, LocalDate day, long wallMillis,
                                      long elapsedMillis, long uptimeMillis, boolean screenOn) {
        return startSessionInternal(profile, targetPackage, 0, day, wallMillis, elapsedMillis, uptimeMillis, screenOn);
    }

    synchronized boolean startDiagnosticSession(Profile profile, String targetPackage, int minutes,
                                                LocalDate day, long wallMillis, long elapsedMillis,
                                                long uptimeMillis, boolean screenOn) {
        if (profile == null || profile == Profile.ADULT || (minutes != 2 && minutes != 5)) return false;
        return startSessionInternal(profile, targetPackage, minutes, day, wallMillis, elapsedMillis,
                uptimeMillis, screenOn);
    }

    private boolean startSessionInternal(Profile profile, String targetPackage, int diagnosticMinutes,
                                         LocalDate day, long wallMillis, long elapsedMillis,
                                         long uptimeMillis, boolean screenOn) {
        synchronized (STORAGE_FILE_LOCK) {
        if (profile == null || day == null) return false;
        String target = targetPackage == null ? "" : targetPackage.trim();
        if (target.length() > 512) return false;
        Properties p = read();
        if (storageError || p.getProperty(SESSION_PROFILE) != null) return false;
        observeClock(p, wallMillis, elapsedMillis, day);
        if (Boolean.parseBoolean(p.getProperty(CLOCK_UNCERTAIN, "false"))) {
            write(p);
            return false;
        }
        p.setProperty(SESSION_PROFILE, profile.name());
        p.setProperty(SESSION_DAY, day.toString());
        p.setProperty(SESSION_ELAPSED, Long.toString(elapsedMillis));
        p.setProperty(SESSION_UPTIME, Long.toString(uptimeMillis));
        p.setProperty(SESSION_SCREEN_ON, Boolean.toString(screenOn));
        p.setProperty(SESSION_FOREGROUND, "false");
        p.setProperty(SESSION_PACKAGE, target);
        p.setProperty(SESSION_PACKAGE_OBSERVED, "false");
        p.setProperty(SESSION_STARTED_WALL, Long.toString(wallMillis));
        p.setProperty(SESSION_CURSOR_WALL, Long.toString(wallMillis));
        p.setProperty(SESSION_QUERY_WALL, Long.toString(wallMillis));
        p.setProperty(MUST_RETURN_HOME, "false");
        if (diagnosticMinutes > 0) {
            p.setProperty(SESSION_DIAGNOSTIC, "true");
            p.setProperty(SESSION_DIAGNOSTIC_LIMIT,
                    Long.toString(diagnosticMinutes * FamilyTimePolicy.MINUTE_MS));
            p.setProperty(SESSION_DIAGNOSTIC_USED, "0");
            p.setProperty(SESSION_DIAGNOSTIC_WARNED, "0");
        }
        return write(p);
        }
    }

    synchronized boolean hasPendingSession() {
        synchronized (STORAGE_FILE_LOCK) {
            return read().getProperty(SESSION_PROFILE) != null;
        }
    }

    synchronized UsageUpdate recordSession(LocalDate day, long wallMillis, long elapsedMillis,
                                           long uptimeMillis) {
        return recordSession(day, wallMillis, elapsedMillis, uptimeMillis, null, false);
    }

    synchronized UsageUpdate setSessionScreenOn(boolean screenOn, LocalDate day, long wallMillis,
                                                long elapsedMillis, long uptimeMillis) {
        return recordSession(day, wallMillis, elapsedMillis, uptimeMillis, screenOn, null, null, false);
    }

    synchronized UsageUpdate setSessionForeground(boolean foreground, String foregroundPackage,
                                                  LocalDate day, long wallMillis,
                                                  long elapsedMillis, long uptimeMillis) {
        return recordSession(day, wallMillis, elapsedMillis, uptimeMillis, null, foreground,
                foregroundPackage, false);
    }

    synchronized boolean setMonitorCursorWall(long wallMillis) {
        synchronized (STORAGE_FILE_LOCK) {
        Properties p = read();
        if (storageError || p.getProperty(SESSION_PROFILE) == null || wallMillis < 0L) return false;
        p.setProperty(SESSION_QUERY_WALL, Long.toString(wallMillis));
        return write(p);
        }
    }

    synchronized boolean markReturnHomeOnWake() {
        synchronized (STORAGE_FILE_LOCK) {
        Properties p = read();
        if (storageError) return false;
        p.setProperty(MUST_RETURN_HOME, "true");
        return write(p);
        }
    }

    synchronized boolean clearReturnHomeOnWake() {
        synchronized (STORAGE_FILE_LOCK) {
        Properties p = read();
        if (storageError) return false;
        p.setProperty(MUST_RETURN_HOME, "false");
        return write(p);
        }
    }

    /**
     * Resets only family data after the caller has authenticated the adult PIN. A private copy is
     * kept first so corrupt or unreadable usage records are not silently discarded.
     */
    synchronized boolean recoverStorageAfterAdultAuthentication(long wallMillis, long elapsedMillis,
                                                                 LocalDate day) {
        synchronized (STORAGE_FILE_LOCK) {
        if (file == null || day == null) return false;
        if (file.exists()) {
            if (!file.isFile()) {
                storageError = true;
                return false;
            }
            File backup = new File(file.getPath() + ".recovery-" + wallMillis);
            int suffix = 0;
            while (backup.exists()) backup = new File(file.getPath() + ".recovery-" + wallMillis + "-" + ++suffix);
            try {
                Files.copy(file.toPath(), backup.toPath());
            } catch (IOException cannotPreserveExistingData) {
                storageError = true;
                return false;
            }
        }

        Properties recovered = new Properties();
        recovered.setProperty(ENABLED, "false");
        recovered.setProperty(CLOCK_UNCERTAIN, "true");
        recovered.setProperty(LAST_WALL, Long.toString(wallMillis));
        recovered.setProperty(LAST_ELAPSED, Long.toString(elapsedMillis));
        recovered.setProperty(LAST_DAY, day.toString());
        return write(recovered);
        }
    }

    synchronized UsageUpdate finishSession(LocalDate day, long wallMillis, long elapsedMillis,
                                           long uptimeMillis) {
        return recordSession(day, wallMillis, elapsedMillis, uptimeMillis, null, true);
    }

    private UsageUpdate recordSession(LocalDate day, long wallMillis, long elapsedMillis,
                                      long uptimeMillis, Boolean newScreenState, boolean finish) {
        return recordSession(day, wallMillis, elapsedMillis, uptimeMillis, newScreenState,
                null, null, finish);
    }

    private UsageUpdate recordSession(LocalDate day, long wallMillis, long elapsedMillis,
                                      long uptimeMillis, Boolean newScreenState,
                                      Boolean newForeground, String newForegroundPackage, boolean finish) {
        synchronized (STORAGE_FILE_LOCK) {
        if (day == null) return failedUpdate();
        Properties p = read();
        if (storageError) return failedUpdate();
        String profileValue = p.getProperty(SESSION_PROFILE);
        if (profileValue == null) {
            observeClock(p, wallMillis, elapsedMillis, day);
            boolean saved = write(p);
            return new UsageUpdate(0L, new ArrayList<>(), saved, false);
        }

        Profile profile;
        try {
            profile = Profile.valueOf(profileValue);
        } catch (IllegalArgumentException corruptState) {
            p.setProperty(CLOCK_UNCERTAIN, "true");
            clearSession(p);
            return new UsageUpdate(0L, new ArrayList<>(), write(p), false);
        }
        observeClock(p, wallMillis, elapsedMillis, day);
        long previousElapsed = number(p, SESSION_ELAPSED, elapsedMillis);
        long previousUptime = number(p, SESSION_UPTIME, uptimeMillis);
        long previousWall = number(p, SESSION_CURSOR_WALL, wallMillis);
        boolean wasScreenOn = Boolean.parseBoolean(p.getProperty(SESSION_SCREEN_ON, "false"));
        boolean wasForeground = Boolean.parseBoolean(p.getProperty(SESSION_FOREGROUND, "false"));
        boolean diagnostic = Boolean.parseBoolean(p.getProperty(SESSION_DIAGNOSTIC, "false"));
        if (elapsedMillis < previousElapsed || uptimeMillis < previousUptime) {
            // A reboot during a running app makes the missing interval unknowable offline.
            p.setProperty(CLOCK_UNCERTAIN, "true");
            p.setProperty(MUST_RETURN_HOME, "true");
            clearSession(p);
            write(p);
            return failedUpdate();
        }

        long elapsedDelta = elapsedMillis - previousElapsed;
        long uptimeDelta = uptimeMillis - previousUptime;
        long activeDelta = wasScreenOn && wasForeground ? Math.min(elapsedDelta, uptimeDelta) : 0L;
        List<Integer> warnings = new ArrayList<>();
        if (activeDelta > 0L && profile != Profile.ADULT) {
            if (diagnostic) recordDiagnosticUse(p, activeDelta, warnings);
            else recordAcrossDays(p, profile, previousWall, activeDelta, warnings);
        }
        p.setProperty(SESSION_DAY, day.toString());
        p.setProperty(SESSION_ELAPSED, Long.toString(elapsedMillis));
        p.setProperty(SESSION_UPTIME, Long.toString(uptimeMillis));
        p.setProperty(SESSION_CURSOR_WALL, Long.toString(wallMillis));
        // Commit the UsageEvents query cursor with the same atomic write as the session tick.
        p.setProperty(SESSION_QUERY_WALL, Long.toString(wallMillis));
        if (newScreenState != null) p.setProperty(SESSION_SCREEN_ON, Boolean.toString(newScreenState));
        if (newForeground != null) p.setProperty(SESSION_FOREGROUND, Boolean.toString(newForeground));
        if (newForegroundPackage != null) {
            if (newForegroundPackage.length() <= 512) {
                p.setProperty(SESSION_PACKAGE, newForegroundPackage);
                p.setProperty(SESSION_PACKAGE_OBSERVED, "true");
            }
        }
        if (finish) clearSession(p);
        boolean saved = write(p);
        long after = profile == Profile.ADULT ? Long.MAX_VALUE
                : diagnostic ? diagnosticRemaining(p) : remaining(p, profile, day);
        return new UsageUpdate(after, warnings, saved, diagnostic);
        }
    }

    private static void recordDiagnosticUse(Properties p, long activeDelta, List<Integer> warnings) {
        long limit = number(p, SESSION_DIAGNOSTIC_LIMIT, 0L);
        long oldUsed = number(p, SESSION_DIAGNOSTIC_USED, 0L);
        long oldRemaining = Math.max(0L, limit - Math.min(limit, oldUsed));
        long newUsed = saturatedAdd(oldUsed, activeDelta);
        p.setProperty(SESSION_DIAGNOSTIC_USED, Long.toString(newUsed));
        long newRemaining = Math.max(0L, limit - Math.min(limit, newUsed));
        long warned = number(p, SESSION_DIAGNOSTIC_WARNED, 0L);
        long oneMinute = FamilyTimePolicy.MINUTE_MS;
        if ((warned & 1L) == 0L && oldRemaining > oneMinute && newRemaining <= oneMinute) {
            warnings.add(1);
            warned |= 1L;
        }
        p.setProperty(SESSION_DIAGNOSTIC_WARNED, Long.toString(warned));
    }

    private static long diagnosticRemaining(Properties p) {
        long limit = number(p, SESSION_DIAGNOSTIC_LIMIT, 0L);
        long used = number(p, SESSION_DIAGNOSTIC_USED, 0L);
        return Math.max(0L, limit - Math.min(limit, used));
    }

    /** Splits a monitored interval at each Europe/Lisbon civil midnight. */
    private static void recordAcrossDays(Properties p, Profile profile, long startWallMillis,
                                         long activeDelta, List<Integer> warnings) {
        long cursor = startWallMillis;
        long remainingDelta = activeDelta;
        LocalDate date = java.time.Instant.ofEpochMilli(cursor).atZone(FAMILY_ZONE).toLocalDate();
        while (remainingDelta > 0L) {
            long nextMidnight = date.plusDays(1).atStartOfDay(FAMILY_ZONE).toInstant().toEpochMilli();
            long untilMidnight = nextMidnight - cursor;
            if (untilMidnight <= 0L) {
                date = date.plusDays(1);
                cursor = nextMidnight;
                continue;
            }
            long amount = Math.min(remainingDelta, untilMidnight);
            long oldRemaining = remaining(p, profile, date);
            addUsage(p, profile, date, amount, oldRemaining, warnings);
            cursor += amount;
            remainingDelta -= amount;
            if (cursor >= nextMidnight) date = date.plusDays(1);
        }
    }

    /** Clear an interrupted session. If it was active, require an adult to review the clock. */
    synchronized boolean recoverInterruptedSession(long wallMillis, long elapsedMillis, LocalDate day) {
        synchronized (STORAGE_FILE_LOCK) {
        if (day == null) return false;
        Properties p = read();
        if (storageError) return false;
        if (p.getProperty(SESSION_PROFILE) != null) {
            String value = p.getProperty(SESSION_PROFILE);
            if (!Profile.ADULT.name().equals(value)) {
                // No monitor was available to reconcile a child/Xbox foreground session.
                p.setProperty(CLOCK_UNCERTAIN, "true");
            }
            p.setProperty(MUST_RETURN_HOME, "true");
            clearSession(p);
        }
        observeClock(p, wallMillis, elapsedMillis, day);
        return write(p);
        }
    }

    private void observeClock(Properties p, long wallMillis, long elapsedMillis, LocalDate day) {
        long previousWall = number(p, LAST_WALL, -1L);
        long previousElapsed = number(p, LAST_ELAPSED, -1L);
        String previousDay = p.getProperty(LAST_DAY);
        if (previousWall >= 0L) {
            if (wallMillis < previousWall - CLOCK_TOLERANCE_MS
                    || (previousDay != null && day.toString().compareTo(previousDay) < 0)) {
                p.setProperty(CLOCK_UNCERTAIN, "true");
            }
            if (previousElapsed >= 0L && elapsedMillis < previousElapsed) {
                // A reboot resets elapsedRealtime. Without a trusted clock source, require adult review.
                p.setProperty(CLOCK_UNCERTAIN, "true");
            } else if (previousElapsed >= 0L) {
                long wallDelta = wallMillis - previousWall;
                long elapsedDelta = elapsedMillis - previousElapsed;
                if (distance(wallDelta, elapsedDelta) > CLOCK_TOLERANCE_MS) {
                    p.setProperty(CLOCK_UNCERTAIN, "true");
                }
            }
        }
        p.setProperty(LAST_WALL, Long.toString(wallMillis));
        p.setProperty(LAST_ELAPSED, Long.toString(elapsedMillis));
        p.setProperty(LAST_DAY, day.toString());
    }

    private static long remaining(Properties p, Profile profile, LocalDate day) {
        if (profile == Profile.ADULT) return Long.MAX_VALUE;
        long base = schedule(p, profile, day.getDayOfWeek()).budgetMs;
        long extra = number(p, extensionKey(profile, day), 0L);
        long budget = saturatedAdd(base, extra);
        return Math.max(0L, budget - Math.min(budget, number(p, usageKey(profile, day), 0L)));
    }

    private static void addUsage(Properties p, Profile profile, LocalDate day, long amount,
                                 long oldRemaining, List<Integer> warnings) {
        String usageKey = usageKey(profile, day);
        long current = number(p, usageKey, 0L);
        p.setProperty(usageKey, Long.toString(saturatedAdd(current, amount)));
        long newRemaining = remaining(p, profile, day);
        String warningKey = "warned." + profile.name().toLowerCase(java.util.Locale.ROOT) + "." + day;
        int warned = (int) number(p, warningKey, 0L);
        int[] thresholds = {10, 5, 1};
        for (int i = 0; i < thresholds.length; i++) {
            long thresholdMs = thresholds[i] * FamilyTimePolicy.MINUTE_MS;
            int bit = 1 << i;
            if ((warned & bit) == 0 && oldRemaining > thresholdMs && newRemaining <= thresholdMs) {
                warnings.add(thresholds[i]);
                warned |= bit;
            }
        }
        p.setProperty(warningKey, Integer.toString(warned));
    }

    private static String usageKey(Profile profile, LocalDate day) {
        return "used." + profile.name().toLowerCase(java.util.Locale.ROOT) + "." + day;
    }

    private static String extensionKey(Profile profile, LocalDate day) {
        return "extra." + profile.name().toLowerCase(java.util.Locale.ROOT) + "." + day;
    }

    private static FamilyTimePolicy.Profile policyProfile(Profile profile) {
        return profile == Profile.XBOX ? FamilyTimePolicy.Profile.XBOX : FamilyTimePolicy.Profile.CHILD_TV;
    }

    private static String schedulePrefix(Profile profile, DayOfWeek day) {
        return "schedule." + profile.name().toLowerCase(java.util.Locale.ROOT) + "." + day.name() + ".";
    }

    private static FamilyTimePolicy.Schedule schedule(Properties p, Profile profile, DayOfWeek day) {
        FamilyTimePolicy.Schedule defaults = FamilyTimePolicy.defaultSchedule(policyProfile(profile), day);
        if (profile == Profile.ADULT) return defaults;
        String prefix = schedulePrefix(profile, day);
        int start = (int) number(p, prefix + "start", defaults.startMinute);
        int end = (int) number(p, prefix + "end", defaults.endMinute);
        long budget = number(p, prefix + "limit", defaults.budgetMs / FamilyTimePolicy.MINUTE_MS)
                * FamilyTimePolicy.MINUTE_MS;
        try { return new FamilyTimePolicy.Schedule(start, end, budget); }
        catch (IllegalArgumentException invalid) { return defaults; }
    }

    private static String joinChildApprovals(Map<String, Long> packages) {
        List<String> ordered = new ArrayList<>();
        for (Map.Entry<String, Long> entry : packages.entrySet()) {
            ordered.add(entry.getKey() + "@" + entry.getValue());
        }
        Collections.sort(ordered);
        return String.join(",", ordered);
    }

    private static Map<String, Long> parseChildApprovals(String value) {
        Map<String, Long> packages = new HashMap<>();
        if (value == null || value.isEmpty()) return packages;
        for (String approval : value.split(",", -1)) {
            int separator = approval.lastIndexOf('@');
            if (separator <= 0 || separator == approval.length() - 1) continue;
            String pkg = approval.substring(0, separator);
            try { packages.put(pkg, Long.parseLong(approval.substring(separator + 1))); }
            catch (NumberFormatException ignored) { }
        }
        return packages;
    }

    private static boolean validPackageName(String value) {
        if (value == null || value.isEmpty() || value.length() > 255
                || value.startsWith(".") || value.endsWith(".")) return false;
        boolean lastWasDot = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (lastWasDot) return false;
                lastWasDot = true;
            } else {
                if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')
                        && !(c >= '0' && c <= '9') && c != '_') return false;
                lastWasDot = false;
            }
        }
        return !lastWasDot;
    }

    private static boolean validPackageList(String value) {
        if (value == null) return false;
        if (value.isEmpty()) return true;
        Set<String> seen = new HashSet<>();
        for (String approval : value.split(",", -1)) {
            int separator = approval.lastIndexOf('@');
            if (separator <= 0 || separator == approval.length() - 1) return false;
            String pkg = approval.substring(0, separator);
            if (!validPackageName(pkg) || !seen.add(pkg)) return false;
            try { if (Long.parseLong(approval.substring(separator + 1)) < 0L) return false; }
            catch (NumberFormatException invalid) { return false; }
        }
        return true;
    }

    private static void clearSession(Properties p) {
        p.remove(SESSION_PROFILE);
        p.remove(SESSION_DAY);
        p.remove(SESSION_ELAPSED);
        p.remove(SESSION_UPTIME);
        p.remove(SESSION_SCREEN_ON);
        p.remove(SESSION_FOREGROUND);
        p.remove(SESSION_PACKAGE);
        p.remove(SESSION_PACKAGE_OBSERVED);
        p.remove(SESSION_STARTED_WALL);
        p.remove(SESSION_CURSOR_WALL);
        p.remove(SESSION_QUERY_WALL);
        p.remove(SESSION_DIAGNOSTIC);
        p.remove(SESSION_DIAGNOSTIC_LIMIT);
        p.remove(SESSION_DIAGNOSTIC_USED);
        p.remove(SESSION_DIAGNOSTIC_WARNED);
    }

    private static UsageUpdate failedUpdate() {
        return new UsageUpdate(0L, new ArrayList<>(), false, false);
    }

    private Properties read() {
        synchronized (STORAGE_FILE_LOCK) {
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
        boolean migrated;
        try (FileInputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (Exception ignored) {
            storageError = true;
            return p;
        }
        migrated = migrateLegacySession(p);
        if (!validState(p)) storageError = true;
        else if (migrated && !write(p)) storageError = true;
        return p;
        }
    }

    private boolean write(Properties p) {
        synchronized (STORAGE_FILE_LOCK) {
        if (file == null) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            storageError = true;
            return false;
        }
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            p.store(out, "CleanYourTV private family settings and usage");
            out.getFD().sync();
        } catch (IOException e) {
            temp.delete();
            storageError = true;
            return false;
        }
        try {
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
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
    }

    private static long number(Properties p, String key, long fallback) {
        try { return Long.parseLong(p.getProperty(key, Long.toString(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static long saturatedAdd(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    private static long distance(long a, long b) {
        try {
            return a >= b ? Math.subtractExact(a, b) : Math.subtractExact(b, a);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean validState(Properties p) {
        if (!validBoolean(p.getProperty(ENABLED)) || !validBoolean(p.getProperty(CLOCK_UNCERTAIN))
                || !validBoolean(p.getProperty(TIME_BAR_VISIBLE))
                || !validBoolean(p.getProperty(CHILD_APPS_INITIALIZED))
                || !validEnum(TimeBarCorner.class, p.getProperty(TIME_BAR_CORNER))
                || !validEnum(TimeBarContent.class, p.getProperty(TIME_BAR_CONTENT))
                || !validDay(p.getProperty(LAST_DAY))) return false;
        String childApps = p.getProperty(CHILD_APPS);
        boolean childAppsInitialized = Boolean.parseBoolean(p.getProperty(CHILD_APPS_INITIALIZED, "false"));
        if (childAppsInitialized != (childApps != null)
                || (childApps != null && !validPackageList(childApps))) return false;
        if (!validOptionalNonnegative(p.getProperty(LAST_WALL))
                || !validOptionalNonnegative(p.getProperty(LAST_ELAPSED))) return false;
        if (p.getProperty(LAST_WALL) != null && p.getProperty(LAST_ELAPSED) == null) return false;
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("used.") || key.startsWith("extra.")) {
                if (!validNonnegative(p.getProperty(key))) return false;
            } else if (key.startsWith("warned.")) {
                long value;
                try { value = Long.parseLong(p.getProperty(key)); } catch (Exception ignored) { return false; }
                if (value < 0L || value > 7L) return false;
            } else if (key.startsWith("schedule.") && !validScheduleProperty(p, key)) {
                return false;
            }
        }
        String session = p.getProperty(SESSION_PROFILE);
        if (session != null) {
            try { Profile.valueOf(session); } catch (Exception invalid) { return false; }
            if (!validDay(p.getProperty(SESSION_DAY)) || !validNonnegative(p.getProperty(SESSION_ELAPSED))
                    || !validNonnegative(p.getProperty(SESSION_UPTIME))
                    || !validBoolean(p.getProperty(SESSION_SCREEN_ON))
                    || !validBoolean(p.getProperty(SESSION_FOREGROUND))
                    || !validBoolean(p.getProperty(SESSION_PACKAGE_OBSERVED))
                    || !validNonnegative(p.getProperty(SESSION_STARTED_WALL))
                    || !validNonnegative(p.getProperty(SESSION_CURSOR_WALL))
                    || !validNonnegative(p.getProperty(SESSION_QUERY_WALL))
                    || p.getProperty(SESSION_PACKAGE, "").length() > 512) return false;
            boolean diagnostic = Boolean.parseBoolean(p.getProperty(SESSION_DIAGNOSTIC, "false"));
            if (diagnostic) {
                long limit = number(p, SESSION_DIAGNOSTIC_LIMIT, -1L);
                if (Profile.ADULT.name().equals(session) || (limit != 2L * FamilyTimePolicy.MINUTE_MS
                        && limit != 5L * FamilyTimePolicy.MINUTE_MS)
                        || !validNonnegative(p.getProperty(SESSION_DIAGNOSTIC_USED))
                        || !validNonnegative(p.getProperty(SESSION_DIAGNOSTIC_WARNED))
                        || number(p, SESSION_DIAGNOSTIC_WARNED, Long.MAX_VALUE) > 1L) return false;
            } else if (p.getProperty(SESSION_DIAGNOSTIC_LIMIT) != null
                    || p.getProperty(SESSION_DIAGNOSTIC_USED) != null
                    || p.getProperty(SESSION_DIAGNOSTIC_WARNED) != null) return false;
        } else if (p.getProperty(SESSION_DAY) != null || p.getProperty(SESSION_ELAPSED) != null
                || p.getProperty(SESSION_UPTIME) != null || p.getProperty(SESSION_SCREEN_ON) != null
                || p.getProperty(SESSION_FOREGROUND) != null || p.getProperty(SESSION_PACKAGE) != null
                || p.getProperty(SESSION_PACKAGE_OBSERVED) != null
                || p.getProperty(SESSION_STARTED_WALL) != null || p.getProperty(SESSION_CURSOR_WALL) != null
                || p.getProperty(SESSION_QUERY_WALL) != null || p.getProperty(SESSION_DIAGNOSTIC) != null
                || p.getProperty(SESSION_DIAGNOSTIC_LIMIT) != null
                || p.getProperty(SESSION_DIAGNOSTIC_USED) != null
                || p.getProperty(SESSION_DIAGNOSTIC_WARNED) != null) {
            return false;
        }
        if (!validBoolean(p.getProperty(MUST_RETURN_HOME))) return false;
        if (!validBoolean(p.getProperty(SESSION_DIAGNOSTIC))) return false;
        return true;
    }

    private static boolean validScheduleProperty(Properties p, String key) {
        String[] parts = key.split("\\.", -1);
        if (parts.length != 4 || !"schedule".equals(parts[0])) return false;
        Profile profile;
        DayOfWeek day;
        try {
            profile = Profile.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT));
            day = DayOfWeek.valueOf(parts[2]);
        } catch (IllegalArgumentException invalidName) {
            return false;
        }
        if (profile == Profile.ADULT) return false;
        String prefix = schedulePrefix(profile, day);
        String startText = p.getProperty(prefix + "start");
        String endText = p.getProperty(prefix + "end");
        String limitText = p.getProperty(prefix + "limit");
        if (startText == null || endText == null || limitText == null
                || !("start".equals(parts[3]) || "end".equals(parts[3]) || "limit".equals(parts[3]))) return false;
        int start, end, limit;
        try {
            start = Integer.parseInt(startText);
            end = Integer.parseInt(endText);
            limit = Integer.parseInt(limitText);
        } catch (NumberFormatException invalidNumber) {
            return false;
        }
        return start >= 0 && start < 24 * 60 && end > start && end <= 24 * 60
                && limit >= 15 && limit <= 480;
    }

    /** Old experimental builds only tracked launcher-opened child sessions and lack monitor cursors. */
    private static boolean migrateLegacySession(Properties p) {
        if (p.getProperty(SESSION_PROFILE) == null || p.getProperty(SESSION_STARTED_WALL) != null) return false;
        String profile = p.getProperty(SESSION_PROFILE);
        if (!Profile.ADULT.name().equals(profile)) p.setProperty(CLOCK_UNCERTAIN, "true");
        p.setProperty(MUST_RETURN_HOME, "true");
        clearSession(p);
        return true;
    }

    private static boolean validBoolean(String value) {
        return value == null || "true".equals(value) || "false".equals(value);
    }

    private static boolean validDay(String value) {
        if (value == null) return true;
        try { LocalDate.parse(value); return true; } catch (Exception ignored) { return false; }
    }

    private static boolean validOptionalNonnegative(String value) {
        return value == null || validNonnegative(value);
    }

    private static boolean validNonnegative(String value) {
        try { return Long.parseLong(value) >= 0L; } catch (Exception ignored) { return false; }
    }

    private static <T extends Enum<T>> boolean validEnum(Class<T> type, String value) {
        if (value == null) return true;
        try { Enum.valueOf(type, value); return true; } catch (IllegalArgumentException ignored) { return false; }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        if (!validEnum(type, value) || value == null) return fallback;
        return Enum.valueOf(type, value);
    }
}
