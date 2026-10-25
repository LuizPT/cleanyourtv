package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs only while a family profile has launched another app. Usage Access supplies package-level
 * foreground transitions; no window text or view hierarchy is read. It returns to Home at a limit.
 */
public final class FamilySessionMonitorService extends Service {
    static final String ACTION_STOP = "com.gathin.tvhome.action.STOP_FAMILY_SESSION";
    private static final String CHANNEL_ID = "family-session";
    private static final int NOTIFICATION_ID = 2404;
    private static final long POLL_MS = 10_000L;
    private static final long HOME_RETRY_MS = 4_000L;
    private static final ZoneId FAMILY_ZONE = ZoneId.of("Europe/Lisbon");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FamilyProfileStore store;
    private UsageStatsManager usageStats;
    private FamilyForegroundTracker tracker;
    private boolean receiverRegistered;
    private boolean finishing;
    private long lastHomeAttemptElapsed;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (finishing) return;
            pollForeground();
            if (!finishing && store != null) {
                if (store.hasPendingSession()) handler.postDelayed(this, POLL_MS);
                else stopMonitor();
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (store == null || intent == null || !store.isEnabled()) return;
            ZonedDateTime now = ZonedDateTime.now(FAMILY_ZONE);
            boolean screenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            if (!screenOn) {
                store.markReturnHomeOnWake();
            }
            if (store.hasPendingSession()) {
                store.setSessionScreenOn(screenOn, now.toLocalDate(), now.toInstant().toEpochMilli(),
                        SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
            }
            if (screenOn && store.snapshot(now.toLocalDate()).mustReturnHome) {
                handler.postDelayed(FamilySessionMonitorService.this::returnHome, 250L);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new FamilyProfileStore(new File(getFilesDir(), "family-profiles.properties"));
        usageStats = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        createNotificationChannel();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
        receiverRegistered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitor();
            return START_NOT_STICKY;
        }
        if (!store.isEnabled() || !store.hasPendingSession()) {
            stopMonitor();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        if (usageStats == null || !FamilyUsageAccess.isGranted(this)) {
            showMonitorFailure();
            returnHome();
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, POLL_MS);
            return START_STICKY;
        }
        FamilyProfileStore.Snapshot snapshot = store.snapshot(LocalDate.now(FAMILY_ZONE));
        tracker = new FamilyForegroundTracker(snapshot.sessionPackageObserved
                ? snapshot.sessionPackage : null);
        handler.removeCallbacks(poll);
        poll.run();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // START_STICKY lets the system restore the monitor if it evicts the process during a session.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        finishing = true;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private void pollForeground() {
        if (!store.isEnabled() || !store.hasPendingSession()) {
            stopMonitor();
            return;
        }
        if (!FamilyUsageAccess.isGranted(this)) {
            showMonitorFailure();
            returnHome();
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(FAMILY_ZONE);
        long wallNow = now.toInstant().toEpochMilli();
        long elapsedNow = SystemClock.elapsedRealtime();
        long uptimeNow = SystemClock.uptimeMillis();
        FamilyProfileStore.Snapshot before = store.snapshot(now.toLocalDate());
        if (before.mustReturnHome) {
            if (isScreenOn()) returnHome();
            else store.setSessionScreenOn(false, now.toLocalDate(), wallNow, elapsedNow, uptimeNow);
            return;
        }
        if (before.clockUncertain || store.hasStorageError()) {
            returnHome();
            return;
        }

        if (tracker == null) tracker = new FamilyForegroundTracker(before.sessionPackageObserved
                ? before.sessionPackage : null);
        if (!readForegroundEvents(before, wallNow)) {
            showMonitorFailure();
            returnHome();
            return;
        }

        String packageName = tracker.foregroundPackage();
        FamilyMonitorPolicy.Foreground foreground = classifyForeground(packageName);
        boolean screenOn = isScreenOn();
        if (!screenOn) {
            store.setSessionScreenOn(false, now.toLocalDate(), wallNow, elapsedNow, uptimeNow);
            return;
        }
        if (store.snapshot(now.toLocalDate()).sessionScreenOn == false) {
            store.setSessionScreenOn(true, now.toLocalDate(), wallNow, elapsedNow, uptimeNow);
            // Waking in an app always returns to the launcher, where Adult must authenticate again.
            store.markReturnHomeOnWake();
            returnHome();
            return;
        }

        boolean isAppForeground = foreground == FamilyMonitorPolicy.Foreground.APP
                || foreground == FamilyMonitorPolicy.Foreground.SETTINGS;
        // The initial package saved by MainActivity is only the requested target. Persist a
        // foreground package only after UsageEvents has actually reported one.
        String observedPackage = foreground == FamilyMonitorPolicy.Foreground.LAUNCHER
                || foreground == FamilyMonitorPolicy.Foreground.UNKNOWN ? null : packageName;
        FamilyProfileStore.UsageUpdate update = store.setSessionForeground(isAppForeground, observedPackage,
                now.toLocalDate(), wallNow, elapsedNow, uptimeNow);
        if (!update.saved || store.hasStorageError()) {
            showMonitorFailure();
            returnHome();
            return;
        }
        if (!update.warningsMinutes.isEmpty() && update.remainingMs > 0L) {
            int lowest = java.util.Collections.min(update.warningsMinutes);
            Toast.makeText(this, getString(R.string.family_time_warning, lowest), Toast.LENGTH_LONG).show();
        }

        FamilyProfileStore.Snapshot current = store.snapshot(now.toLocalDate());
        long sessionAgeMs = Math.max(0L, wallNow - current.sessionStartedWallMs);
        FamilyMonitorPolicy.Action action = FamilyMonitorPolicy.decide(true, true, current.sessionPending,
                screenOn, current.sessionProfile, foreground, now,
                current.sessionProfile == FamilyProfileStore.Profile.XBOX ? current.xboxUsedMs : current.childUsedMs,
                current.sessionProfile == FamilyProfileStore.Profile.XBOX ? current.xboxExtensionMs : current.childExtensionMs,
                current.clockUncertain, current.sessionPackageObserved, sessionAgeMs);
        if (action == FamilyMonitorPolicy.Action.RETURN_HOME) {
            returnHome();
        } else if (action == FamilyMonitorPolicy.Action.FINISH_SESSION) {
            store.finishSession(now.toLocalDate(), wallNow, elapsedNow, uptimeNow);
            stopMonitor();
        } else if (action == FamilyMonitorPolicy.Action.STOP) {
            stopMonitor();
        }
    }

    private boolean readForegroundEvents(FamilyProfileStore.Snapshot snapshot, long now) {
        if (usageStats == null || tracker == null) return false;
        long begin = snapshot.sessionQueryWallMs > 1_000L
                ? Math.max(snapshot.sessionStartedWallMs, snapshot.sessionQueryWallMs - 1_000L)
                : Math.max(0L, now - 10_000L);
        try {
            UsageEvents events = usageStats.queryEvents(begin, now);
            if (events == null) return false;
            ArrayList<FamilyForegroundTracker.Event> parsed = new ArrayList<>();
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    parsed.add(new FamilyForegroundTracker.Event(FamilyForegroundTracker.Type.FOREGROUND,
                            event.getPackageName()));
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    parsed.add(new FamilyForegroundTracker.Event(FamilyForegroundTracker.Type.BACKGROUND,
                            event.getPackageName()));
                }
            }
            tracker.apply(parsed);
            return store.setMonitorCursorWall(now);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private FamilyMonitorPolicy.Foreground classifyForeground(String packageName) {
        if (packageName == null) return FamilyMonitorPolicy.Foreground.UNKNOWN;
        if (getPackageName().equals(packageName)) return FamilyMonitorPolicy.Foreground.LAUNCHER;
        if (isSettingsPackage(packageName)) return FamilyMonitorPolicy.Foreground.SETTINGS;
        return FamilyMonitorPolicy.Foreground.APP;
    }

    private boolean isSettingsPackage(String pkg) {
        return "com.android.settings".equals(pkg) || "com.android.tv.settings".equals(pkg)
                || "com.google.android.tv.settings".equals(pkg) || "com.xiaomi.mitv.settings".equals(pkg);
    }

    private boolean isScreenOn() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power == null || power.isInteractive();
    }

    private void returnHome() {
        if (finishing) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed - lastHomeAttemptElapsed < HOME_RETRY_MS) return;
        lastHomeAttemptElapsed = nowElapsed;
        try {
            Intent home = new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(home);
        } catch (RuntimeException blockedByPlatform) {
            // The foreground-service status and quota gate remain active; retry on the next poll.
        }
    }

    private void showMonitorFailure() {
        Toast.makeText(this, R.string.family_monitor_error, Toast.LENGTH_LONG).show();
    }

    private Notification buildNotification() {
        FamilyProfileStore.Snapshot snapshot = store.snapshot(LocalDate.now(FAMILY_ZONE));
        String title = getString(R.string.family_monitor_notification_title);
        String profile = snapshot.sessionProfile == FamilyProfileStore.Profile.ADULT
                ? getString(R.string.family_profile_adult)
                : snapshot.sessionProfile == FamilyProfileStore.Profile.XBOX
                    ? getString(R.string.family_profile_xbox) : getString(R.string.family_profile_child);
        String text = getString(R.string.family_monitor_notification_text, profile);
        Intent home = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 2404, home,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        return builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title).setContentText(text).setContentIntent(pending)
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }

    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.family_monitor_channel), NotificationManager.IMPORTANCE_LOW));
    }

    private void stopMonitor() {
        finishing = true;
        handler.removeCallbacksAndMessages(null);
        stopForeground(true);
        stopSelf();
    }
}
