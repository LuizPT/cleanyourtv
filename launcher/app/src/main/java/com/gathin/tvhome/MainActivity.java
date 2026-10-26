package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    static final String ACTION_SHOW_INPUTS = "com.gathin.tvhome.action.SHOW_INPUTS";
    static final String ACTION_OPEN_SETTINGS = "com.gathin.tvhome.action.OPEN_SETTINGS";
    private static final long MINUTE_MS = 60_000L;
    private static final ZoneId FAMILY_ZONE = ZoneId.of("Europe/Lisbon");
    private static final String FAMILY_XBOX_TIMING_TAG = "FamilyXboxTiming";
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private LauncherState launcherState;
    private FamilyProfileStore familyStore;
    private TextView familyStatus;
    private TextView familyOverlayPermissionStatus;
    private FamilyTimePolicy.Profile activeFamilyProfile;
    private boolean familyActivityResumed;
    private boolean familySelectorShowing;
    private boolean familyScreenReceiverRegistered;
    private boolean familyLimitNotified;
    private boolean familyLaunchInFlight;
    private int diagnosticMinutesNextLaunch;
    private boolean configuredHomeSettingsShortcut;
    private long xboxLauncherAttemptStartedMs;
    private List<String> defaultDockOrder = Collections.emptyList();
    private Set<String> configuredHiddenApps = Collections.emptySet();
    private Set<String> protectedApps = Collections.emptySet();
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private int ambientColor = -1;
    private boolean heroShown;
    private TextView greeting;
    private View btnInputs;
    private TextView btnWifi;
    private TextView shamsiDate;
    private boolean shamsi;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver familyScreenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (familyStore == null || intent == null) return;
            if (!familyStore.isEnabled() && !familyStore.hasPendingSession()) return;
            boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            if (!on) familyStore.markReturnHomeOnWake();
            FamilyProfileStore.UsageUpdate update = familyStore.setSessionScreenOn(on, familyToday(),
                    System.currentTimeMillis(), SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
            handleFamilyUsageUpdate(update);
            if (!on) {
                setActiveFamilyProfile(null);
            } else if (familyActivityResumed) {
                handler.postDelayed(MainActivity.this::maybeShowFamilyProfileSelector, 350L);
            }
            if (on && familyStore.snapshot(familyToday()).mustReturnHome
                    && !FamilyUsageAccess.isGranted(MainActivity.this)) {
                try {
                    startActivity(new Intent(MainActivity.this, MainActivity.class)
                            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                } catch (RuntimeException platformBlockedBackgroundLaunch) {
                    // Android 9 permits this while the process is alive; newer systems may require Home manually.
                }
            }
        }
    };
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                Uri packageUri = intent.getData();
                String packageName = packageUri == null ? null : packageUri.getSchemeSpecificPart();
                if (packageName != null && familyStore != null) familyStore.removeChildAppApproval(packageName);
            }
            reloadApps(false);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            refreshFamilyProfileAccess(true);
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        launcherState = new LauncherState(new File(getFilesDir(), "launcher-state.properties"));
        familyStore = new FamilyProfileStore(new File(getFilesDir(), "family-profiles.properties"));
        if (familyStore.hasPendingSession() && !FamilyUsageAccess.isGranted(this)) {
            familyStore.recoverInterruptedSession(System.currentTimeMillis(), SystemClock.elapsedRealtime(), familyToday());
        } else if (familyStore.hasPendingSession() && familyStore.isEnabled()) {
            startFamilySessionMonitor();
        }
        registerFamilyScreenReceiver();
        defaultDockOrder = new ArrayList<>(cfg.dockApps);
        protectedApps = new HashSet<>(launcherState.protectedApps());
        COLUMNS = cfg.columns;
        reducedMotion = cfg.reducedMotion;
        configuredHomeSettingsShortcut = cfg.showHomeSettingsShortcut;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        familyStatus = findViewById(R.id.familyStatus);
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        configuredHiddenApps = new HashSet<>(cfg.hiddenApps);
        hiddenApps = new HashSet<>(launcherState.effectiveHiddenApps(configuredHiddenApps));

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
        float enterDelay = reducedMotion ? 0f : 0.06f;
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        adapter.setDockOrder(launcherState.dockOrder(defaultDockOrder));
        adapter.setProtectedApps(protectedApps);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density)), density);
            int dockRow = itemH + (int) ((6 + 12) * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            int want = Math.max(0, h - dockRow - gridRows - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                // Stiff and almost critically damped: settles in a few frames instead of oscillating,
                // and stops once the highlight is within 1.5 px of its tile.
                s.setSpring(new SpringForce().setStiffness(1400f).setDampingRatio(0.95f));
                s.setMinimumVisibleChange(1.5f);
            } else {
                s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> open(systemIntent(Settings.ACTION_SETTINGS)));
        btnSettings.setOnLongClickListener(v -> {
            showLauncherTools();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // The left end is Inputs, or Wi-Fi on a box without inputs.
                return v.getId() == R.id.btnInputs
                        || (v.getId() == R.id.btnWifi && btnInputs.getVisibility() != View.VISIBLE);
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        Intent initial = getIntent();
        if (initial != null && (ACTION_SHOW_INPUTS.equals(initial.getAction())
                || ACTION_OPEN_SETTINGS.equals(initial.getAction()))) {
            root.post(() -> handleLauncherAction(initial));
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleLauncherAction(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
    }

    private boolean handleLauncherAction(Intent intent) {
        if (intent == null) return false;
        if (ACTION_SHOW_INPUTS.equals(intent.getAction())) {
            if (btnInputs.getVisibility() == View.VISIBLE) showInputs();
            else Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (ACTION_OPEN_SETTINGS.equals(intent.getAction())) {
            open(systemIntent(Settings.ACTION_SETTINGS));
            return true;
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        familyActivityResumed = true;
        updateFamilyOverlayPermissionStatus();
        finishFamilyUsageSession();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
        tilesReady = false;
        promptsPaused = false;
        reloadApps(true);
        updateGreeting();
        updateWifiLabel();
        updateInputsButton();
        updateFamilyStatus();
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        reloadTiles();
        if (animate && !reducedMotion) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
            maybeShowFamilyProfileSelector();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        Set<String> profileHidden = profileHiddenApps();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, profileHidden, Cards.load(Cards.storeFile(this))), none) : none;
        adapter.setHiddenApps(profileHidden);
        adapter.setHomeSettingsVisible(configuredHomeSettingsShortcut && !childScopedLauncher());
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    private boolean childScopedLauncher() {
        return familyEnabled() && activeFamilyProfile != FamilyTimePolicy.Profile.ADULT;
    }

    private boolean blockNonAdultAppManagement() {
        if (!familyEnabled() || activeFamilyProfile == FamilyTimePolicy.Profile.ADULT) return false;
        Toast.makeText(this, R.string.family_admin_adult_required, Toast.LENGTH_LONG).show();
        return true;
    }

    private Set<String> launchablePackages() {
        return new HashSet<>(launchableInstallTimes().keySet());
    }

    private java.util.Map<String, Long> launchableInstallTimes() {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = getPackageManager().queryIntentActivities(probe, 0);
        java.util.Map<String, Long> packages = new java.util.HashMap<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (getPackageName().equals(pkg) || packages.containsKey(pkg)) continue;
            long installTime = packageFirstInstallTime(pkg);
            if (installTime >= 0L) packages.put(pkg, installTime);
        }
        return packages;
    }

    private long packageFirstInstallTime(String packageName) {
        try {
            return getPackageManager().getPackageInfo(packageName, 0).firstInstallTime;
        } catch (Exception unavailable) {
            return -1L;
        }
    }

    private boolean initializeChildAppsIfNeeded() {
        if (familyStore.childAppsInitialized()) return true;
        if (familyStore.hasStorageError()) return false;
        java.util.Map<String, Long> initiallyVisible = launchableInstallTimes();
        for (String pkg : hiddenApps) initiallyVisible.remove(pkg);
        return familyStore.initializeChildApps(initiallyVisible);
    }

    private Set<String> profileHiddenApps() {
        if (!childScopedLauncher()) return new HashSet<>(hiddenApps);
        java.util.Map<String, Long> installTimes = launchableInstallTimes();
        Set<String> allowed = new HashSet<>();
        if (familyStore != null) {
            for (java.util.Map.Entry<String, Long> entry : installTimes.entrySet()) {
                if (familyStore.isChildAppAllowed(entry.getKey(), entry.getValue())) allowed.add(entry.getKey());
            }
        }
        return FamilyAppVisibilityPolicy.hiddenPackages(activeFamilyProfile, installTimes.keySet(), allowed);
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
            maybeShowFamilyProfileSelector();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (xboxLauncherAttemptStartedMs > 0L) {
            Log.i(FAMILY_XBOX_TIMING_TAG, "launcher_to_activity_pause_ms="
                    + (SystemClock.elapsedRealtime() - xboxLauncherAttemptStartedMs));
            xboxLauncherAttemptStartedMs = 0L;
        }
        familyActivityResumed = false;
        unregisterReceiver(packageReceiver);
        handler.removeCallbacks(minuteTick);
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            showCardPrompt();
            if (cardPrompt == null) maybeShowContextHint();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (familyScreenReceiverRegistered) {
            unregisterReceiver(familyScreenReceiver);
            familyScreenReceiverRegistered = false;
        }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        if (diagnosticMinutesNextLaunch > 0) {
            diagnosticMinutesNextLaunch = 0;
            setActiveFamilyProfile(null);
            Toast.makeText(this, R.string.family_diagnostic_cancelled, Toast.LENGTH_SHORT).show();
            maybeShowFamilyProfileSelector();
        }
        // At Home, Back otherwise goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileLaunch(AppAdapter.Entry entry) {
        openTile(entry);
    }

    @Override
    public void onTileManage(AppAdapter.Entry entry) {
        if (blockNonAdultAppManagement()) return;
        showAppContextMenu(entry.pkg, entry.label, false);
    }

    private void openTile(AppAdapter.Entry entry) {
        if (entry.kind == AppAdapter.Entry.HOME_SETTINGS) {
            open(systemIntent(Settings.ACTION_HOME_SETTINGS));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        open(intent);
    }

    void openFromLibrary(String pkg, ComponentName component, String label) {
        openTile(new AppAdapter.Entry(label, component, null, null));
    }

    boolean onDockOrderChanged(List<String> order) {
        if (blockNonAdultAppManagement()) return false;
        if (!launcherState.saveDockOrder(order)) {
            Toast.makeText(this, R.string.settings_not_saved, Toast.LENGTH_SHORT).show();
            return false;
        }
        adapter.setDockOrder(order);
        adapter.refreshOrder();
        int focused = adapter.positionOf(lastFocusedKey);
        if (focused >= 0) lastFocused = focused;
        return true;
    }

    void showLibraryContextMenu(String pkg, String label) {
        showAppContextMenu(pkg, label, true);
    }

    private void showAppContextMenu(String pkg, String label, boolean inLibrary) {
        if (blockNonAdultAppManagement()) return;
        Runnable showMenu = () -> showAppContextMenuDialog(pkg, label, inLibrary);
        if (LauncherState.requiresPin(protectedApps, pkg)) requirePin(pkg, label, true, showMenu);
        else showMenu.run();
    }

    private void showAppContextMenuDialog(String pkg, String label, boolean inLibrary) {
        boolean hidden = hiddenApps.contains(pkg);
        boolean protectedApp = protectedApps.contains(pkg);
        boolean pinned = launcherState.dockOrder(defaultDockOrder).contains(pkg);
        List<AppActionPolicy.Action> actions = AppActionPolicy.contextActions(inLibrary, hidden, protectedApp, pinned);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        panel.setPadding(pad, pad, pad, (int) (12 * density));
        panel.setBackgroundResource(R.drawable.glass_panel);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        View first = null;
        for (AppActionPolicy.Action action : actions) {
            TextView option = actionOption(action, () -> {
                dialog.dismiss();
                runAppAction(action, pkg, label, protectedApp);
            });
            if (first == null) first = option;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = (int) (6 * density);
            panel.addView(option, lp);
        }
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.65f);
        }
        dialog.show();
        if (window != null) window.setLayout((int) (430 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (first != null) first.requestFocus();
    }

    private TextView actionOption(AppActionPolicy.Action action, Runnable onClick) {
        int label;
        switch (action) {
            case MOVE: label = R.string.context_move; break;
            case HIDE: label = R.string.context_hide; break;
            case SHOW: label = R.string.context_show; break;
            case PROTECT: label = R.string.context_protect_sequence; break;
            case UNPROTECT: label = R.string.context_unprotect_sequence; break;
            case ADD_TO_HOME: label = R.string.context_add_home; break;
            case REMOVE_FROM_HOME: label = R.string.context_remove_home; break;
            default: label = R.string.context_app_info; break;
        }
        TextView option = new TextView(this);
        option.setText(label);
        option.setTextColor(Color.WHITE);
        option.setTextSize(17);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding((int) (14 * density), (int) (10 * density), (int) (14 * density), (int) (10 * density));
        option.setBackgroundResource(R.drawable.focus_row_bg);
        option.setFocusable(true);
        option.setClickable(true);
        option.setOnClickListener(v -> onClick.run());
        return option;
    }

    private void runAppAction(AppActionPolicy.Action action, String pkg, String label,
                              boolean alreadyAuthenticated) {
        if (blockNonAdultAppManagement()) return;
        switch (action) {
            case MOVE:
                moveAppToDockAndShowControls(pkg);
                break;
            case HIDE:
                setAppVisibility(pkg, false);
                break;
            case SHOW:
                setAppVisibility(pkg, true);
                break;
            case PROTECT:
                toggleAppProtection(pkg, label, alreadyAuthenticated);
                break;
            case UNPROTECT:
                toggleAppProtection(pkg, label, alreadyAuthenticated);
                break;
            case APP_INFO:
                Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg));
                performOpen(details);
                break;
            case ADD_TO_HOME:
                setHomeShortcut(pkg, true);
                break;
            case REMOVE_FROM_HOME:
                setHomeShortcut(pkg, false);
                break;
        }
    }

    private void setAppVisibility(String pkg, boolean visible) {
        if (blockNonAdultAppManagement()) return;
        if (!launcherState.setAppHidden(pkg, !visible, configuredHiddenApps)) {
            Toast.makeText(this, R.string.settings_not_saved, Toast.LENGTH_SHORT).show();
            return;
        }
        hiddenApps = new HashSet<>(launcherState.effectiveHiddenApps(configuredHiddenApps));
        reloadApps(true);
        Toast.makeText(this, visible ? R.string.app_shown_on_home : R.string.app_hidden_from_home,
                Toast.LENGTH_SHORT).show();
    }

    private void setHomeShortcut(String pkg, boolean add) {
        if (blockNonAdultAppManagement()) return;
        List<String> order = new ArrayList<>(launcherState.dockOrder(defaultDockOrder));
        if (add) {
            if (order.contains(pkg)) return;
            if (activeDockCount(order) >= COLUMNS) {
                Toast.makeText(this, R.string.home_shortcuts_full, Toast.LENGTH_SHORT).show();
                return;
            }
            order.add(pkg);
        } else if (!order.remove(pkg)) return;
        onDockOrderChanged(order);
    }

    private int activeDockCount(List<String> order) {
        int count = 0;
        for (String pkg : order) if (!hiddenApps.contains(pkg)) count++;
        return count;
    }

    private void moveAppToDockAndShowControls(String pkg) {
        if (blockNonAdultAppManagement()) return;
        List<String> order = new ArrayList<>(launcherState.dockOrder(defaultDockOrder));
        if (!order.contains(pkg)) {
            if (activeDockCount(order) >= COLUMNS) {
                Toast.makeText(this, R.string.home_shortcuts_full, Toast.LENGTH_SHORT).show();
                return;
            }
            order.add(pkg);
            if (!onDockOrderChanged(order)) return;
        }
        showMoveOptions(pkg);
    }

    private void showMoveOptions(String pkg) {
        List<String> order = launcherState.dockOrder(defaultDockOrder);
        int index = order.indexOf(pkg);
        if (index < 0) return;
        String label = appLabel(pkg);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        panel.setPadding(pad, pad, pad, (int) (12 * density));
        panel.setBackgroundResource(R.drawable.glass_panel);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.65f);
        }
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            panel.removeAllViews();
            TextView title = new TextView(this);
            title.setText(getString(R.string.move_app_title, label));
            title.setTextColor(Color.WHITE);
            title.setTextSize(21);
            panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
            List<String> current = launcherState.dockOrder(defaultDockOrder);
            int at = current.indexOf(pkg);
            TextView position = new TextView(this);
            position.setText(getString(R.string.move_app_position, Math.max(1, at + 1), current.size()));
            position.setTextColor(0xFFD8DDE5);
            position.setTextSize(14);
            LinearLayout.LayoutParams posLp = new LinearLayout.LayoutParams(-1, -2);
            posLp.topMargin = (int) (4 * density);
            panel.addView(position, posLp);
            TextView up = actionOption(AppActionPolicy.Action.MOVE, () -> moveDockStep(pkg, -1, render[0]));
            up.setText(R.string.move_up);
            up.setEnabled(at > 0);
            TextView down = actionOption(AppActionPolicy.Action.MOVE, () -> moveDockStep(pkg, 1, render[0]));
            down.setText(R.string.move_down);
            down.setEnabled(at >= 0 && at < current.size() - 1);
            TextView close = actionOption(AppActionPolicy.Action.APP_INFO, dialog::dismiss);
            close.setText(R.string.close);
            for (TextView option : new TextView[]{up, down, close}) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = (int) (6 * density);
                panel.addView(option, lp);
            }
            panel.setTag(at > 0 ? up : at < current.size() - 1 ? down : close);
            panel.post(() -> {
                View target = (View) panel.getTag();
                if (target != null) target.requestFocus();
            });
        };
        render[0].run();
        dialog.show();
        if (window != null) window.setLayout((int) (430 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.post(() -> {
            View target = (View) panel.getTag();
            if (target != null) target.requestFocus();
        });
    }

    void showMoveOptionsFromLibrary(String pkg) {
        String label = appLabel(pkg);
        Runnable move = () -> showMoveOptions(pkg);
        if (LauncherState.requiresPin(protectedApps, pkg)) requirePin(pkg, label, true, move);
        else move.run();
    }

    private void moveDockStep(String pkg, int delta, Runnable rerender) {
        List<String> current = launcherState.dockOrder(defaultDockOrder);
        List<String> moved = LauncherState.moveDock(current, pkg, delta);
        if (!moved.equals(current) && onDockOrderChanged(moved)) rerender.run();
    }

    private void toggleAppProtection(String pkg, String label, boolean alreadyAuthenticated) {
        if (!parentalStateAvailable()) return;
        Runnable save = () -> {
            Set<String> changed = new HashSet<>(protectedApps);
            boolean protecting = !changed.contains(pkg);
            if (protecting) changed.add(pkg);
            else changed.remove(pkg);
            if (!launcherState.saveProtectedApps(changed)) {
                Toast.makeText(this, R.string.protected_apps_not_saved, Toast.LENGTH_SHORT).show();
                return;
            }
            protectedApps = changed;
            adapter.setProtectedApps(protectedApps);
            Toast.makeText(this, protecting ? R.string.app_protected_by_sequence : R.string.app_sequence_removed,
                    Toast.LENGTH_SHORT).show();
        };
        if (alreadyAuthenticated) save.run();
        else if (launcherState.hasParentalCredential()) authenticate(getString(R.string.sequence_manage_app, label), save);
        else if (!protectedApps.isEmpty())
            Toast.makeText(this, R.string.parental_state_unavailable, Toast.LENGTH_SHORT).show();
        else authenticate(getString(R.string.sequence_manage_app, label), save);
    }

    private void maybeShowContextHint() {
        if (launcherState != null && launcherState.markContextHintShown()) {
            Toast.makeText(this, R.string.context_hint, Toast.LENGTH_LONG).show();
        }
    }

    private void showLauncherTools() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) (22 * density), (int) (18 * density), (int) (22 * density), (int) (14 * density));
        panel.setBackgroundResource(R.drawable.glass_panel);
        TextView title = new TextView(this);
        title.setText(R.string.settings);
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView help = new TextView(this);
        help.setText(R.string.tools_help);
        help.setTextColor(0xFFD8DDE5);
        help.setTextSize(14);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(-1, -2);
        helpLp.topMargin = (int) (3 * density);
        panel.addView(help, helpLp);
        boolean restricted = childScopedLauncher();
        if (!restricted) addToolOption(panel, R.string.organize_apps, () -> {
            dialog.dismiss();
            showAppLibrary(true);
        });
        addToolOption(panel, R.string.applications, () -> {
            dialog.dismiss();
            showAppLibrary(false);
        });
        if (!restricted) {
            addToolOption(panel, R.string.parental_control, () -> {
                dialog.dismiss();
                showParentalControl();
            });
            addToolOption(panel, R.string.family_profiles, () -> {
                dialog.dismiss();
                if (familyStore != null && familyStore.isEnabled()) showFamilyProfileSelector();
                else showFamilyManagement();
            });
            addToolOption(panel, R.string.family_management, () -> {
                dialog.dismiss();
                showFamilyManagement();
            });
            if (moduleCards) addToolOption(panel, R.string.module_cards, () -> {
                dialog.dismiss();
                showModuleCards();
            });
        }
        addToolOption(panel, R.string.close, dialog::dismiss);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.65f);
        }
        dialog.show();
        if (window != null) window.setLayout((int) (420 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.getChildAt(2).requestFocus();
    }

    private void addToolOption(LinearLayout panel, int labelRes, Runnable action) {
        TextView option = new TextView(this);
        option.setText(labelRes);
        option.setTextColor(Color.WHITE);
        option.setTextSize(17);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding((int) (14 * density), (int) (10 * density), (int) (14 * density), (int) (10 * density));
        option.setBackgroundResource(R.drawable.focus_row_bg);
        option.setFocusable(true);
        option.setClickable(true);
        option.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (6 * density);
        panel.addView(option, lp);
    }

    private void showAppLibrary(boolean orderOnly) {
        boolean restricted = childScopedLauncher();
        AppLibraryDialog.show(this, restricted ? false : orderOnly,
                launcherState.dockOrder(defaultDockOrder),
                restricted ? profileHiddenApps() : hiddenApps, protectedApps, restricted);
    }

    private void requirePin(String pkg, String label, boolean management, Runnable allowed) {
        if (!parentalStateAvailable()) return;
        if (!LauncherState.requiresPin(protectedApps, pkg)) {
            allowed.run();
            return;
        }
        if (!launcherState.hasParentalCredential()) {
            if (launcherState.hasStorageError()) {
                Toast.makeText(this, R.string.parental_state_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!protectedApps.isEmpty()) {
                Toast.makeText(this, R.string.parental_state_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        authenticate(getString(management ? R.string.sequence_manage_app : R.string.sequence_unlock_app, label), allowed);
    }

    private void authenticate(String title, Runnable allowed) {
        if (!parentalStateAvailable()) return;
        if (launcherState.isUnlockSequenceConfigured()) {
            showSequenceEntry(title, LauncherState.UNLOCK_SEQUENCE_MAX_LEGACY_MOVES,
                    R.string.sequence_auth_entry_help, sequence -> {
                LauncherState.PinResult result = launcherState.verifyUnlockSequence(sequence, System.currentTimeMillis());
                Arrays.fill(sequence, '\0');
                handleCredentialResult(result, allowed, true);
            }, () -> { });
            return;
        }
        if (launcherState.hasStorageError()) {
            Toast.makeText(this, R.string.parental_state_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        if (launcherState.isPinConfigured()) {
            if (launcherState.hasStorageError()) {
                Toast.makeText(this, R.string.parental_state_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
            showPinEntry(getString(R.string.sequence_migration_title), pin -> {
            LauncherState.PinResult result = launcherState.verifyPin(pin, System.currentTimeMillis());
            Arrays.fill(pin, '\0');
            if (result == LauncherState.PinResult.MATCH) {
                setupUnlockSequence(() -> {
                    Toast.makeText(this, R.string.sequence_saved, Toast.LENGTH_SHORT).show();
                    allowed.run();
                });
            } else {
                handleCredentialResult(result, allowed, false);
            }
            }, () -> { });
            return;
        }
        if (!protectedApps.isEmpty()) {
            Toast.makeText(this, R.string.parental_state_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        setupUnlockSequence(() -> {
            Toast.makeText(this, R.string.sequence_saved, Toast.LENGTH_SHORT).show();
            allowed.run();
        });
    }

    private void handleCredentialResult(LauncherState.PinResult result, Runnable allowed, boolean sequence) {
        if (result == LauncherState.PinResult.MATCH) {
            allowed.run();
        } else if (result == LauncherState.PinResult.LOCKED) {
            long ms = launcherState.lockRemainingMs(System.currentTimeMillis());
            int seconds = (int) Math.max(1L, (ms + 999L) / 1000L);
            Toast.makeText(this, getString(R.string.sequence_locked, seconds), Toast.LENGTH_LONG).show();
        } else if (result == LauncherState.PinResult.ERROR || result == LauncherState.PinResult.NOT_CONFIGURED) {
            Toast.makeText(this, R.string.parental_state_unavailable, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, sequence ? R.string.sequence_incorrect : R.string.pin_incorrect,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void setupUnlockSequence(Runnable afterSave) {
        showSequenceEntry(getString(R.string.sequence_setup_title), LauncherState.UNLOCK_SEQUENCE_MAX_MOVES,
                R.string.sequence_entry_help, first -> {
            showSequenceEntry(getString(R.string.sequence_confirm_title), LauncherState.UNLOCK_SEQUENCE_MAX_MOVES,
                    R.string.sequence_entry_help, second -> {
                boolean same = LauncherState.sameUnlockSequence(first, second);
                Arrays.fill(second, '\0');
                if (!same) {
                    Arrays.fill(first, '\0');
                    Toast.makeText(this, R.string.sequence_mismatch, Toast.LENGTH_SHORT).show();
                    setupUnlockSequence(afterSave);
                    return;
                }
                boolean saved = launcherState.setUnlockSequence(first);
                Arrays.fill(first, '\0');
                if (!saved) {
                    Toast.makeText(this, R.string.sequence_not_saved, Toast.LENGTH_SHORT).show();
                    return;
                }
                afterSave.run();
            }, () -> Arrays.fill(first, '\0'));
        }, () -> { });
    }

    private void changeUnlockSequence() {
        authenticate(getString(R.string.sequence_change), () -> setupUnlockSequence(() ->
                Toast.makeText(this, R.string.sequence_saved, Toast.LENGTH_SHORT).show()));
    }

    private interface PinSubmit { void onPin(char[] pin); }

    /** A numeric keypad built for D-pad/OK; PIN digits are never sent to an IME or written to logs. */
    private void showPinEntry(String titleText, PinSubmit submit, Runnable onCancel) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (22 * density);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackgroundResource(R.drawable.glass_panel);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView help = new TextView(this);
        help.setText(R.string.sequence_migration_help);
        help.setTextColor(0xFFB8B8BE);
        help.setTextSize(14);
        panel.addView(help, new LinearLayout.LayoutParams(-1, -2));
        TextView display = new TextView(this);
        display.setGravity(Gravity.CENTER);
        display.setTextColor(Color.WHITE);
        display.setTextSize(28);
        display.setPadding(0, (int) (10 * density), 0, (int) (10 * density));
        display.setText(" ");
        panel.addView(display, new LinearLayout.LayoutParams(-1, -2));

        StringBuilder digits = new StringBuilder(LauncherState.PIN_MAX);
        Runnable updateDisplay = () -> {
            StringBuilder bullets = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) bullets.append("● ");
            display.setText(bullets.length() == 0 ? " " : bullets.toString().trim());
        };
        boolean[] submitted = {false};
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"},
                {getString(R.string.delete_digit), "0", getString(R.string.confirm)}};
        TextView firstKey = null;
        for (String[] keyRow : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (String key : keyRow) {
                TextView button = pinKey(key, () -> {
                    if (getString(R.string.confirm).equals(key)) {
                        char[] pin = new char[digits.length()];
                        digits.getChars(0, digits.length(), pin, 0);
                        if (!LauncherState.validPin(pin)) {
                            Arrays.fill(pin, '\0');
                            Toast.makeText(this, R.string.pin_error_length, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        submitted[0] = true;
                        clear(digits);
                        dialog.dismiss();
                        submit.onPin(pin);
                    } else if (getString(R.string.delete_digit).equals(key)) {
                        if (digits.length() > 0) digits.deleteCharAt(digits.length() - 1);
                        updateDisplay.run();
                    } else if (digits.length() < LauncherState.PIN_MAX) {
                        digits.append(key);
                        updateDisplay.run();
                    }
                });
                if (firstKey == null) firstKey = button;
                LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams((int) (82 * density), (int) (52 * density));
                keyLp.setMargins((int) (4 * density), (int) (4 * density), (int) (4 * density), (int) (4 * density));
                row.addView(button, keyLp);
            }
            panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> {
            clear(digits);
            if (!submitted[0]) onCancel.run();
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.7f);
        }
        dialog.show();
        if (window != null) window.setLayout((int) (330 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (firstKey != null) firstKey.requestFocus();
    }

    private interface SequenceSubmit { void onSequence(char[] sequence); }

    /** D-pad sequence input is local to this dialog; the secret is represented only by progress dots. */
    private void showSequenceEntry(String titleText, int maxMoves, int helpText, SequenceSubmit submit,
                                   Runnable onCancel) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackgroundResource(R.drawable.glass_panel);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView help = new TextView(this);
        help.setText(helpText);
        help.setTextColor(0xFFB8B8BE);
        help.setTextSize(14);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(-1, -2);
        helpLp.topMargin = dp(4);
        panel.addView(help, helpLp);

        TextView progress = new TextView(this);
        progress.setId(R.id.unlock_sequence_progress);
        progress.setGravity(Gravity.CENTER);
        progress.setTextColor(Color.WHITE);
        progress.setTextSize(19);
        progress.setPadding(0, dp(16), 0, dp(12));
        panel.addView(progress, new LinearLayout.LayoutParams(-1, -2));

        TextView progressCount = new TextView(this);
        progressCount.setId(R.id.unlock_sequence_count);
        progressCount.setGravity(Gravity.CENTER);
        progressCount.setTextColor(0xFFB8B8BE);
        progressCount.setTextSize(14);
        progressCount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(progressCount, new LinearLayout.LayoutParams(-1, -2));

        char[] entered = new char[maxMoves];
        int[] length = {0};
        boolean[] submitted = {false};
        boolean[] cancelled = {false};
        Runnable redraw = () -> {
            StringBuilder dots = new StringBuilder(LauncherState.UNLOCK_SEQUENCE_MAX_MOVES * 2);
            for (int i = 0; i < LauncherState.UNLOCK_SEQUENCE_MAX_MOVES; i++) {
                dots.append(i < length[0] ? "● " : "○ ");
            }
            progress.setText(dots.toString().trim());
            progress.setContentDescription(getString(R.string.sequence_progress_accessibility, length[0], maxMoves));
            progressCount.setText(getString(R.string.sequence_progress_count, length[0], maxMoves));
        };
        Runnable[] confirm = new Runnable[1];
        Runnable appendUp = () -> appendSequenceMove(entered, length, 'U', redraw);
        Runnable appendDown = () -> appendSequenceMove(entered, length, 'D', redraw);
        Runnable appendLeft = () -> appendSequenceMove(entered, length, 'L', redraw);
        Runnable appendRight = () -> appendSequenceMove(entered, length, 'R', redraw);

        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);
        TextView up = sequenceArrowKey("↑", R.string.sequence_direction_up, appendUp);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER);
        top.addView(up, sequenceArrowLayout());
        pad.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout middle = new LinearLayout(this);
        middle.setGravity(Gravity.CENTER);
        middle.addView(sequenceArrowKey("←", R.string.sequence_direction_left, appendLeft), sequenceArrowLayout());
        View spacer = new View(this);
        middle.addView(spacer, sequenceArrowLayout());
        middle.addView(sequenceArrowKey("→", R.string.sequence_direction_right, appendRight), sequenceArrowLayout());
        pad.addView(middle, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.addView(sequenceArrowKey("↓", R.string.sequence_direction_down, appendDown), sequenceArrowLayout());
        pad.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(pad, new LinearLayout.LayoutParams(-1, -2));
        redraw.run();

        confirm[0] = () -> {
            if (length[0] < LauncherState.UNLOCK_SEQUENCE_MIN_MOVES) {
                Toast.makeText(this, R.string.sequence_length_error, Toast.LENGTH_SHORT).show();
                return;
            }
            submitted[0] = true;
            char[] value = Arrays.copyOf(entered, length[0]);
            Arrays.fill(entered, '\0');
            length[0] = 0;
            dialog.dismiss();
            submit.onSequence(value);
        };
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (DpadSequencePolicy.acceptsPress(event.getAction(), event.getRepeatCount())) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) appendUp.run();
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) appendDown.run();
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) appendLeft.run();
                    else appendRight.run();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (DpadSequencePolicy.acceptsPress(event.getAction(), event.getRepeatCount())) confirm[0].run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (DpadSequencePolicy.acceptsPress(event.getAction(), event.getRepeatCount())) {
                    if (length[0] > 0) {
                        entered[--length[0]] = '\0';
                        redraw.run();
                    } else if (!cancelled[0]) {
                        cancelled[0] = true;
                        Arrays.fill(entered, '\0');
                        dialog.dismiss();
                        onCancel.run();
                    }
                }
                return true;
            }
            return false;
        });
        dialog.setContentView(panel);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            if (!submitted[0]) Arrays.fill(entered, '\0');
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.72f);
        }
        dialog.show();
        if (window != null) window.setLayout(dp(410), ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();
    }

    private void appendSequenceMove(char[] entered, int[] length, char direction, Runnable redraw) {
        if (length[0] >= entered.length) return;
        entered[length[0]++] = direction;
        redraw.run();
    }

    private TextView sequenceArrowKey(String glyph, int description, Runnable action) {
        TextView key = new TextView(this);
        key.setText(glyph);
        key.setContentDescription(getString(description));
        key.setTextColor(Color.WHITE);
        key.setTextSize(28);
        key.setGravity(Gravity.CENTER);
        key.setBackgroundResource(R.drawable.focus_button_bg);
        key.setFocusable(false);
        key.setClickable(true);
        key.setOnClickListener(v -> action.run());
        return key;
    }

    private LinearLayout.LayoutParams sequenceArrowLayout() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(48));
        lp.setMargins(dp(4), dp(3), dp(4), dp(3));
        return lp;
    }

    private TextView pinKey(String text, Runnable action) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(text.length() > 1 ? 13 : 20);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.focus_button_bg);
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private static void clear(StringBuilder value) {
        for (int i = 0; i < value.length(); i++) value.setCharAt(i, '\0');
        value.setLength(0);
    }

    private void showParentalControl() {
        if (blockNonAdultAppManagement()) return;
        if (!parentalStateAvailable()) return;
        if (!launcherState.hasParentalCredential() && launcherState.hasStorageError()) {
            Toast.makeText(this, R.string.parental_state_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        authenticate(getString(R.string.parental_control), this::showProtectedApps);
    }

    private void showProtectedApps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = getPackageManager().queryIntentActivities(query, 0);
        List<ResolveInfo> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo == null || info.activityInfo.packageName.equals(getPackageName())) continue;
            if (seen.add(info.activityInfo.packageName)) apps.add(info);
        }
        apps.sort((a, b) -> String.valueOf(a.loadLabel(getPackageManager())).compareToIgnoreCase(
                String.valueOf(b.loadLabel(getPackageManager()))));

        Set<String> selected = new HashSet<>(protectedApps);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * density);
        panel.setPadding(padding, padding, padding, (int) (12 * density));
        panel.setBackgroundResource(R.drawable.glass_panel);
        TextView title = new TextView(this);
        title.setText(R.string.parental_select_apps);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView help = new TextView(this);
        help.setText(R.string.parental_select_sequence_help);
        help.setTextColor(0xFFB8B8BE);
        help.setTextSize(14);
        panel.addView(help, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = (int) (12 * density);
        panel.addView(scroll, scrollLp);
        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            String label = Cards.cleanLabel(String.valueOf(info.loadLabel(getPackageManager())), pkg);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding((int) (8 * density), (int) (3 * density), (int) (8 * density), (int) (3 * density));
            CheckBox check = new CheckBox(this);
            check.setText(label);
            check.setTextColor(Color.WHITE);
            check.setTextSize(16);
            check.setChecked(selected.contains(pkg));
            check.setFocusable(true);
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(pkg);
                else selected.remove(pkg);
            });
            row.addView(check, new LinearLayout.LayoutParams(-1, -2));
            TextView status = new TextView(this);
            String value = hiddenApps.contains(pkg) ? getString(R.string.hidden_from_home) : getString(R.string.in_app_list);
            status.setText(protectedApps.contains(pkg) ? value + "  ·  " + getString(R.string.protected_by_sequence) : value);
            status.setTextColor(0xFFB8B8BE);
            status.setTextSize(12);
            status.setPadding((int) (48 * density), 0, 0, (int) (5 * density));
            row.addView(status, new LinearLayout.LayoutParams(-1, -2));
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.addView(controlButton(R.string.sequence_change, () -> {
            dialog.dismiss();
            changeUnlockSequence();
        }), controlButtonLp());
        buttons.addView(controlButton(R.string.cancel, dialog::dismiss), controlButtonLp());
        buttons.addView(controlButton(R.string.save, () -> {
            if (!launcherState.saveProtectedApps(selected)) {
                Toast.makeText(this, R.string.protected_apps_not_saved, Toast.LENGTH_SHORT).show();
                return;
            }
            protectedApps = new HashSet<>(selected);
            adapter.setProtectedApps(protectedApps);
            dialog.dismiss();
            Toast.makeText(this, R.string.protected_apps_saved, Toast.LENGTH_SHORT).show();
        }), controlButtonLp());
        panel.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.65f);
        }
        dialog.show();
        if (window != null) {
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.76f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
        else buttons.getChildAt(0).requestFocus();
    }

    private TextView controlButton(int textRes, Runnable action) {
        TextView button = new TextView(this);
        button.setText(textRes);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        button.setBackgroundResource(R.drawable.focus_button_bg);
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams controlButtonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = (int) (6 * density);
        return lp;
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(reducedMotion ? 100 : 180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, reducedMotion);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroName.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            if (!heroShown) {
                ambient.setAlpha(0f);
                ambient.animate().alpha(1f).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture; changing apps only uploads it once.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        int w = 480;
        int h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(
                w * 0.32f, h * 0.42f, w * 0.62f,
                new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bmp);
        drawable.setGravity(Gravity.FILL);
        ambient.setBackground(drawable);
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
        ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return position % COLUMNS == 0;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return position % COLUMNS == COLUMNS - 1 || position == count - 1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        if (!parentalStateAvailable()) return;
        if (!familyAllowsTarget(FamilyAccessPolicy.Target.UNKNOWN, null)) return;
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        Runnable start = () -> {
            try {
                startService(i);
            } catch (Exception e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        };
        if (c != null && LauncherState.requiresPin(protectedApps, c.getPackageName())) {
        authenticate(getString(R.string.sequence_unlock_app, appLabel(c.getPackageName())), start);
        } else start.run();
    }

    private boolean open(Intent i) {
        return open(i, true);
    }

    private boolean open(Intent i, boolean showError) {
        return open(i, showError, false);
    }

    private boolean open(Intent i, boolean showError, boolean familyAuthorizationChecked) {
        if (!parentalStateAvailable()) return false;
        if (i == null) {
            if (showError) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!familyAuthorizationChecked && !familyAllowsIntent(i)) return false;
        String pkg = targetPackage(i);
        if (LauncherState.requiresPin(protectedApps, pkg)) {
            String label = appLabel(pkg);
            authenticate(getString(R.string.sequence_unlock_app, label), () -> performOpen(i, showError));
            return true;
        }
        return dispatchAuthorizedOpen(i, showError);
    }

    private String targetPackage(Intent i) {
        if (i.getComponent() != null) return i.getComponent().getPackageName();
        if (i.getPackage() != null) return i.getPackage();
        ResolveInfo resolved = getPackageManager().resolveActivity(i, 0);
        return resolved != null && resolved.activityInfo != null ? resolved.activityInfo.packageName : null;
    }

    private String appLabel(String pkg) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            return String.valueOf(info.loadLabel(getPackageManager()));
        } catch (Exception ignored) {
            return pkg;
        }
    }

    private boolean performOpen(Intent i) {
        return performOpen(i, true);
    }

    private boolean parentalStateAvailable() {
        if (!launcherState.hasStorageError()) return true;
        Toast.makeText(this, R.string.parental_state_unavailable, Toast.LENGTH_SHORT).show();
        return false;
    }

    private LocalDate familyToday() {
        return LocalDate.now(FAMILY_ZONE);
    }

    private ZonedDateTime familyNow() {
        return ZonedDateTime.now(FAMILY_ZONE);
    }

    private boolean familyEnabled() {
        return familyStore != null && familyStore.isEnabled();
    }

    private void setActiveFamilyProfile(FamilyTimePolicy.Profile profile) {
        setActiveFamilyProfile(profile, true);
    }

    private void setActiveFamilyProfile(FamilyTimePolicy.Profile profile, boolean refreshApps) {
        if (activeFamilyProfile == profile) return;
        if (profile == null || profile == FamilyTimePolicy.Profile.ADULT) {
            diagnosticMinutesNextLaunch = 0;
        }
        activeFamilyProfile = profile;
        updateFamilyStatus();
        if (refreshApps && adapter != null) reloadApps(false);
    }

    private void startFamilySessionMonitor() {
        if (familyStore == null || !familyStore.isEnabled() || !familyStore.hasPendingSession()
                || !FamilyUsageAccess.isGranted(this)) return;
        try {
            startForegroundService(new Intent(this, FamilySessionMonitorService.class));
        } catch (RuntimeException unavailable) {
            // A pending child session remains fail-closed; Home will ask an adult to review it.
        }
    }

    private void openFamilyUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.family_usage_access_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void registerFamilyScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(familyScreenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(familyScreenReceiver, filter);
        }
        familyScreenReceiverRegistered = true;
    }

    private boolean isScreenInteractive() {
        PowerManager power = getSystemService(PowerManager.class);
        return power == null || power.isInteractive();
    }

    private boolean familyAllowsIntent(Intent intent) {
        if (!familyEnabled()) {
            if (familyStore != null && familyStore.hasStorageError()) {
                Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        }
        if (intent == null) return familyAllowsTarget(FamilyAccessPolicy.Target.UNKNOWN, null);
        if (isSettingsIntent(intent)) return familyAllowsTarget(FamilyAccessPolicy.Target.SYSTEM_SETTINGS, null);

        String inputId = passthroughInputId(intent.getData());
        if (inputId != null) {
            TvInputInfo info = tvInputInfo(inputId);
            if (info == null) return familyAllowsTarget(FamilyAccessPolicy.Target.UNKNOWN, inputId);
            if (info.getType() == TvInputInfo.TYPE_TUNER) {
                return familyAllowsTarget(FamilyAccessPolicy.Target.TUNER_INPUT, inputId);
            }
            if (info.isPassthroughInput()) {
                return familyAllowsTarget(FamilyAccessPolicy.Target.PASSTHROUGH_INPUT, inputId);
            }
            return familyAllowsTarget(FamilyAccessPolicy.Target.UNKNOWN, inputId);
        }

        String pkg = intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
        if (pkg == null) {
            ResolveInfo resolved = getPackageManager().resolveActivity(intent, 0);
            if (resolved != null && resolved.activityInfo != null) pkg = resolved.activityInfo.packageName;
        }
        if (isSettingsPackage(pkg)) return familyAllowsTarget(FamilyAccessPolicy.Target.SYSTEM_SETTINGS, null);
        long firstInstallTime = activeFamilyProfile == FamilyTimePolicy.Profile.CHILD_TV
                && pkg != null ? packageFirstInstallTime(pkg) : -1L;
        boolean childApprovalMatchesInstall = pkg != null && firstInstallTime >= 0L
                && familyStore.isChildAppAllowed(pkg, firstInstallTime);
        if (activeFamilyProfile == FamilyTimePolicy.Profile.CHILD_TV
                && !FamilyAppVisibilityPolicy.mayLaunch(activeFamilyProfile, pkg, childApprovalMatchesInstall)) {
            if (familyStore.hasStorageError()) {
                Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.family_child_app_blocked, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        return familyAllowsTarget(pkg == null ? FamilyAccessPolicy.Target.UNKNOWN : FamilyAccessPolicy.Target.APP, null);
    }

    private boolean familyAllowsTarget(FamilyAccessPolicy.Target target, String inputId) {
        if (!familyEnabled()) {
            if (familyStore != null && familyStore.hasStorageError()) {
                Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        }
        if (familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return false;
        }
        if (activeFamilyProfile == null) {
            showFamilyProfileSelector();
            return false;
        }
        if (!FamilyUsageAccess.isGranted(this)) {
            Toast.makeText(this, R.string.family_monitor_required, Toast.LENGTH_LONG).show();
            return false;
        }
        ZonedDateTime now = familyNow();
        LocalDate day = now.toLocalDate();
        familyStore.observeClock(now.toInstant().toEpochMilli(), SystemClock.elapsedRealtime(), day);
        if (familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return false;
        }
        FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(day);
        if (snapshot.sessionPending) {
            showFamilyDecision(FamilyAccessPolicy.Reason.SESSION_PENDING);
            return false;
        }
        FamilyAccessPolicy.Decision decision = FamilyAccessPolicy.evaluate(activeFamilyProfile, target,
                inputId, snapshot.xboxInputId, now, activeFamilyProfile == FamilyTimePolicy.Profile.XBOX
                        ? snapshot.xboxUsedMs : snapshot.childUsedMs,
                activeFamilyProfile == FamilyTimePolicy.Profile.XBOX
                        ? snapshot.xboxExtensionMs : snapshot.childExtensionMs,
                snapshot.clockUncertain,
                activeFamilyProfile == FamilyTimePolicy.Profile.XBOX
                        ? snapshot.xboxSchedule : snapshot.childSchedule,
                diagnosticMinutesNextLaunch > 0);
        if (decision.allowed) return true;
        showFamilyDecision(decision.reason);
        if (decision.reason == FamilyAccessPolicy.Reason.OUTSIDE_HOURS
                || decision.reason == FamilyAccessPolicy.Reason.DAILY_LIMIT_REACHED
                || decision.reason == FamilyAccessPolicy.Reason.CLOCK_UNCERTAIN) {
            setActiveFamilyProfile(null);
        }
        return false;
    }

    private void showFamilyDecision(FamilyAccessPolicy.Reason reason) {
        int message;
        switch (reason) {
            case PROFILE_REQUIRED:
                showFamilyProfileSelector();
                return;
            case OUTSIDE_HOURS:
                message = R.string.family_outside_hours;
                break;
            case DAILY_LIMIT_REACHED:
                message = R.string.family_daily_limit;
                break;
            case CLOCK_UNCERTAIN:
                message = R.string.family_clock_uncertain;
                break;
            case MONITOR_REQUIRED:
                message = R.string.family_monitor_required;
                break;
            case SESSION_PENDING:
                message = R.string.family_session_recovery;
                break;
            case WRONG_XBOX_INPUT:
                message = R.string.family_wrong_input;
                break;
            default:
                message = R.string.family_target_blocked;
                break;
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private boolean beginFamilyUsageSession(Intent target) {
        if (!familyEnabled()) return false;
        if (!FamilyUsageAccess.isGranted(this)) {
            Toast.makeText(this, R.string.family_monitor_required, Toast.LENGTH_LONG).show();
            return false;
        }
        if (familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return false;
        }
        if (activeFamilyProfile == null) return false;
        FamilyProfileStore.Profile profile = activeFamilyProfile == FamilyTimePolicy.Profile.ADULT
                ? FamilyProfileStore.Profile.ADULT
                : activeFamilyProfile == FamilyTimePolicy.Profile.XBOX
                    ? FamilyProfileStore.Profile.XBOX : FamilyProfileStore.Profile.CHILD_TV;
        ZonedDateTime now = familyNow();
        boolean diagnostic = diagnosticMinutesNextLaunch > 0;
        boolean saved = diagnostic
                ? familyStore.startDiagnosticSession(profile, targetPackage(target), diagnosticMinutesNextLaunch,
                        now.toLocalDate(), now.toInstant().toEpochMilli(), SystemClock.elapsedRealtime(),
                        SystemClock.uptimeMillis(), isScreenInteractive())
                : familyStore.startSession(profile, targetPackage(target), now.toLocalDate(),
                        now.toInstant().toEpochMilli(), SystemClock.elapsedRealtime(),
                        SystemClock.uptimeMillis(), isScreenInteractive());
        if (!saved) {
            diagnosticMinutesNextLaunch = 0;
            Toast.makeText(this, familyStore.hasStorageError() ? R.string.family_storage_error
                    : R.string.family_session_recovery, Toast.LENGTH_LONG).show();
            return false;
        }
        diagnosticMinutesNextLaunch = 0;
        trackedFamilyProfile = activeFamilyProfile;
        familyLimitNotified = false;
        try {
            startForegroundService(new Intent(this, FamilySessionMonitorService.class));
        } catch (RuntimeException cannotStartMonitor) {
            abortFamilyUsageSession();
            Toast.makeText(this, R.string.family_monitor_error, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private FamilyTimePolicy.Profile trackedFamilyProfile;

    private void finishFamilyUsageSession() {
        if (familyStore != null && familyStore.hasPendingSession()) {
            ZonedDateTime now = familyNow();
            FamilyProfileStore.UsageUpdate update = familyStore.finishSession(now.toLocalDate(),
                    now.toInstant().toEpochMilli(), SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
            handleFamilyUsageUpdate(update);
            trackedFamilyProfile = null;
            stopService(new Intent(this, FamilySessionMonitorService.class));
        }
        if (familyLaunchInFlight && familyEnabled()) setActiveFamilyProfile(null);
        familyLaunchInFlight = false;
        updateFamilyStatus();
    }

    private void abortFamilyUsageSession() {
        if (familyStore == null || !familyStore.hasPendingSession()) return;
        ZonedDateTime now = familyNow();
        familyStore.finishSession(now.toLocalDate(), now.toInstant().toEpochMilli(),
                SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
        trackedFamilyProfile = null;
        stopService(new Intent(this, FamilySessionMonitorService.class));
    }

    private void handleFamilyUsageUpdate(FamilyProfileStore.UsageUpdate update) {
        if (update == null) return;
        if (!update.saved) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        if (!update.warningsMinutes.isEmpty() && update.remainingMs > 0L) {
            int minutes = FamilyTimeBarPolicy.warningMinutes(update.warningsMinutes, update.remainingMs);
            Toast.makeText(this, getString(R.string.family_time_warning, minutes), Toast.LENGTH_LONG).show();
        }
        if (trackedFamilyProfile != null && update.remainingMs <= 0L) {
            if (!familyLimitNotified) {
                int message = trackedFamilyProfile == FamilyTimePolicy.Profile.XBOX
                        ? R.string.family_time_bar_xbox_ended : R.string.family_time_bar_child_ended;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                familyLimitNotified = true;
            }
            setActiveFamilyProfile(null);
        }
        else updateFamilyStatus();
        refreshFamilyProfileAccess(true);
    }

    private void refreshFamilyProfileAccess(boolean notify) {
        if (!familyEnabled() || activeFamilyProfile == null || activeFamilyProfile == FamilyTimePolicy.Profile.ADULT) return;
        ZonedDateTime now = familyNow();
        LocalDate day = now.toLocalDate();
        familyStore.observeClock(now.toInstant().toEpochMilli(), SystemClock.elapsedRealtime(), day);
        if (familyStore.hasStorageError()) {
            setActiveFamilyProfile(null);
            if (notify) Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(day);
        boolean xbox = activeFamilyProfile == FamilyTimePolicy.Profile.XBOX;
        FamilyAccessPolicy.Decision decision = FamilyAccessPolicy.evaluate(activeFamilyProfile,
                xbox ? FamilyAccessPolicy.Target.PASSTHROUGH_INPUT : FamilyAccessPolicy.Target.APP,
                xbox ? snapshot.xboxInputId : null, snapshot.xboxInputId, now,
                xbox ? snapshot.xboxUsedMs : snapshot.childUsedMs,
                xbox ? snapshot.xboxExtensionMs : snapshot.childExtensionMs, snapshot.clockUncertain,
                xbox ? snapshot.xboxSchedule : snapshot.childSchedule, snapshot.sessionDiagnostic);
        if (!decision.allowed) {
            setActiveFamilyProfile(null);
            if (notify) showFamilyDecision(decision.reason);
            if (familyActivityResumed) handler.postDelayed(this::maybeShowFamilyProfileSelector, 250L);
        }
    }

    private String passthroughInputId(Uri uri) {
        return uri != null && TvContract.isChannelUriForPassthroughInput(uri)
                ? uri.getLastPathSegment() : null;
    }

    private TvInputInfo tvInputInfo(String inputId) {
        try {
            TvInputManager manager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
            return manager == null ? null : manager.getTvInputInfo(inputId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isSettingsIntent(Intent intent) {
        String action = intent.getAction();
        if (action != null && (action.equals(Settings.ACTION_SETTINGS)
                || action.equals(Settings.ACTION_WIFI_SETTINGS)
                || action.equals(Settings.ACTION_HOME_SETTINGS)
                || action.equals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                || action.equals(Settings.ACTION_APPLICATION_SETTINGS)
                || action.equals(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                || action.equals(Settings.ACTION_SECURITY_SETTINGS)
                || action.equals(Settings.ACTION_PRIVACY_SETTINGS)
                || action.equals(Settings.ACTION_INPUT_METHOD_SETTINGS)
                || action.equals(Settings.ACTION_DISPLAY_SETTINGS)
                || action.equals(Settings.ACTION_SOUND_SETTINGS)
                || action.equals(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                || action.equals(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                || action.equals(Settings.ACTION_DATE_SETTINGS)
                || action.equals(Settings.ACTION_DEVICE_INFO_SETTINGS)
                || action.equals(Settings.ACTION_USAGE_ACCESS_SETTINGS))) return true;
        return isSettingsPackage(intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage());
    }

    private boolean isSettingsPackage(String pkg) {
        return "com.android.settings".equals(pkg) || "com.android.tv.settings".equals(pkg)
                || "com.google.android.tv.settings".equals(pkg) || "com.xiaomi.mitv.settings".equals(pkg);
    }

    private void updateFamilyStatus() {
        if (familyStatus == null) return;
        if (!familyEnabled() || activeFamilyProfile == null) {
            familyStatus.setVisibility(View.GONE);
            return;
        }
        LocalDate today = familyToday();
        FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(today);
        String profileName;
        String remaining;
        if (activeFamilyProfile == FamilyTimePolicy.Profile.ADULT) {
            profileName = getString(R.string.family_profile_adult);
            remaining = getString(R.string.family_unlimited);
        } else {
            boolean xbox = activeFamilyProfile == FamilyTimePolicy.Profile.XBOX;
            profileName = getString(xbox ? R.string.family_profile_xbox : R.string.family_profile_child);
            long ms = snapshot.remainingMs(xbox ? FamilyProfileStore.Profile.XBOX : FamilyProfileStore.Profile.CHILD_TV, today);
            remaining = android.text.format.DateUtils.formatElapsedTime((ms + 999L) / 1000L);
        }
        familyStatus.setText(getString(R.string.family_status, profileName, remaining));
        familyStatus.setVisibility(View.VISIBLE);
    }

    private void maybeShowFamilyProfileSelector() {
        if (!familyEnabled() || activeFamilyProfile != null || !familyActivityResumed || !tilesReady
                || familySelectorShowing || isFinishing()) return;
        if (cardPrompt != null && cardPrompt.isShowing()) return;
        showFamilyProfileSelector();
    }

    private void showFamilyProfileSelector() {
        if (!familyEnabled()) return;
        if (familySelectorShowing || isFinishing()) return;
        if (familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        familySelectorShowing = true;
        boolean childAppsWereInitialized = familyStore.childAppsInitialized();
        if (!initializeChildAppsIfNeeded()) {
            familySelectorShowing = false;
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        if (!childAppsWereInitialized && adapter != null) reloadApps(false);
        LocalDate today = familyToday();
        ZonedDateTime currentTime = familyNow();
        familyStore.observeClock(currentTime.toInstant().toEpochMilli(), SystemClock.elapsedRealtime(), today);
        if (familyStore.hasStorageError()) {
            familySelectorShowing = false;
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(today);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = familyPanel();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(20), dp(12), dp(20), dp(10));
        TextView title = familyText(R.string.family_select_profile, 27, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView help = familyText(R.string.family_select_profile_help, 14, 0xFFC5CBD4);
        help.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(-1, -2);
        helpLp.topMargin = dp(3);
        panel.addView(help, helpLp);

        if (snapshot.clockUncertain) {
            TextView warning = familyText(R.string.family_clock_uncertain, 13, 0xFFFFD180);
            warning.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(-1, -2);
            warningLp.topMargin = dp(4);
            panel.addView(warning, warningLp);
        }
        if (!FamilyUsageAccess.isGranted(this)) {
            TextView warning = familyText(R.string.family_monitor_required, 13, 0xFFFFD180);
            warning.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(-1, -2);
            warningLp.topMargin = dp(4);
            panel.addView(warning, warningLp);
        } else if (snapshot.sessionPending) {
            TextView warning = familyText(R.string.family_session_recovery, 13, 0xFFFFD180);
            warning.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(-1, -2);
            warningLp.topMargin = dp(4);
            panel.addView(warning, warningLp);
        }

        boolean adultPin = launcherState.hasParentalCredential() && !launcherState.hasStorageError();
        int warningHeightDp = (snapshot.clockUncertain ? 34 : 0)
                + ((!FamilyUsageAccess.isGranted(this) || snapshot.sessionPending) ? 34 : 0)
                + (adultPin ? 0 : 26);
        int screenHeightDp = Math.round(getResources().getDisplayMetrics().heightPixels / density);
        int profileTileHeightDp = Math.max(120, Math.min(214, screenHeightDp - 167 - warningHeightDp));
        int screenWidthDp = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        int profileRowWidthDp = Math.min(Math.round(screenWidthDp * 0.72f), profileTileHeightDp * 3 + 36);

        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams choicesLp = new LinearLayout.LayoutParams(
                dp(profileRowWidthDp), -2);
        choicesLp.gravity = Gravity.CENTER;
        choicesLp.topMargin = dp(12);
        panel.addView(choices, choicesLp);

        View adult = familyProfileTile(R.id.familyProfileAdult, R.string.family_profile_adult,
                R.drawable.ic_family_adult, 0xFF35577D,
                getString(R.string.family_profile_adult_sequence_hint), adultPin, () -> {
            dialog.dismiss();
            familySelectorShowing = false;
            if (!adultPin) {
                Toast.makeText(this, R.string.family_sequence_required, Toast.LENGTH_LONG).show();
                return;
            }
            authenticate(getString(R.string.family_sequence_adult_auth),
                    () -> selectFamilyProfile(FamilyTimePolicy.Profile.ADULT));
        });
        addFamilyProfileTile(choices, adult, profileTileHeightDp);

        FamilyAccessPolicy.Decision child = profileChoiceDecision(FamilyTimePolicy.Profile.CHILD_TV, snapshot, today);
        long childRemaining = snapshot.remainingMs(FamilyProfileStore.Profile.CHILD_TV, today);
        String childStatus = familyProfileStatus(child, childRemaining, FamilyTimePolicy.Profile.CHILD_TV);
        View childButton = familyProfileTile(R.id.familyProfileChild, R.string.family_profile_child_short,
                R.drawable.ic_family_child, 0xFF8057A5, childStatus, child.allowed, () -> {
                    dialog.dismiss();
                    familySelectorShowing = false;
                    selectFamilyProfile(FamilyTimePolicy.Profile.CHILD_TV);
                });
        addFamilyProfileTile(choices, childButton, profileTileHeightDp);

        boolean hasXboxInput = isConfiguredXboxInput(snapshot.xboxInputId);
        FamilyAccessPolicy.Decision xbox = profileChoiceDecision(FamilyTimePolicy.Profile.XBOX, snapshot, today);
        long xboxRemaining = snapshot.remainingMs(FamilyProfileStore.Profile.XBOX, today);
        String xboxStatus = hasXboxInput ? familyProfileStatus(xbox, xboxRemaining, FamilyTimePolicy.Profile.XBOX)
                : getString(R.string.family_profile_xbox_configure);
        View xboxButton = familyProfileTile(R.id.familyProfileXbox, R.string.family_profile_xbox_short,
                R.drawable.ic_family_xbox, 0xFF167A47, xboxStatus, hasXboxInput && xbox.allowed, () -> {
                    dialog.dismiss();
                    familySelectorShowing = false;
                    selectFamilyProfile(FamilyTimePolicy.Profile.XBOX);
                });
        addFamilyProfileTile(choices, xboxButton, profileTileHeightDp);

        if (!adultPin) {
            TextView pinHint = familyText(R.string.family_sequence_required, 13, 0xFFFFD180);
            pinHint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(-1, -2);
            pinLp.topMargin = dp(8);
            panel.addView(pinHint, pinLp);
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = dp(8);
        panel.addView(actions, actionsLp);
        TextView manage = familyChoice(getString(R.string.family_manage_profiles), true, () -> {
            dialog.dismiss();
            familySelectorShowing = false;
            showFamilyManagement();
        });
        manage.setId(R.id.familyManageProfiles);
        manage.setGravity(Gravity.CENTER);
        manage.setPadding(dp(20), dp(8), dp(20), dp(8));
        manage.setBackgroundResource(R.drawable.focus_button_bg);
        actions.addView(manage, familyActionLp());
        TextView cancel = familyChoice(getString(R.string.close), true, () -> {
            dialog.dismiss();
            familySelectorShowing = false;
        });
        cancel.setId(View.generateViewId());
        cancel.setGravity(Gravity.CENTER);
        actions.addView(cancel, familyActionLp());

        boolean childEnabled = child.allowed;
        boolean xboxEnabled = hasXboxInput && xbox.allowed;
        boolean rtl = panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int nextFromAdult = childEnabled ? childButton.getId()
                : xboxEnabled ? xboxButton.getId() : manage.getId();
        int nextFromChild = xboxEnabled ? xboxButton.getId() : manage.getId();
        int previousFromXbox = childEnabled ? childButton.getId()
                : adultPin ? adult.getId() : manage.getId();
        if (rtl) {
            adult.setNextFocusLeftId(nextFromAdult);
            childButton.setNextFocusRightId(adultPin ? adult.getId() : manage.getId());
            childButton.setNextFocusLeftId(nextFromChild);
            xboxButton.setNextFocusRightId(previousFromXbox);
            manage.setNextFocusLeftId(cancel.getId());
            cancel.setNextFocusRightId(manage.getId());
        } else {
            adult.setNextFocusRightId(nextFromAdult);
            childButton.setNextFocusLeftId(adultPin ? adult.getId() : manage.getId());
            childButton.setNextFocusRightId(nextFromChild);
            xboxButton.setNextFocusLeftId(previousFromXbox);
            manage.setNextFocusRightId(cancel.getId());
            cancel.setNextFocusLeftId(manage.getId());
        }
        adult.setNextFocusDownId(manage.getId());
        childButton.setNextFocusDownId(manage.getId());
        xboxButton.setNextFocusDownId(manage.getId());
        manage.setNextFocusUpId(adultPin ? adult.getId()
                : childEnabled ? childButton.getId() : xboxEnabled ? xboxButton.getId() : View.NO_ID);
        cancel.setNextFocusUpId(manage.getId());
        View.OnKeyListener downToManage = (view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) manage.requestFocus();
                return true;
            }
            return false;
        };
        adult.setOnKeyListener(downToManage);
        childButton.setOnKeyListener(downToManage);
        xboxButton.setOnKeyListener(downToManage);
        manage.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == (rtl ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT)) {
                return cancel.requestFocus();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                View firstAvailable = adultPin ? adult : childEnabled ? childButton : xboxEnabled ? xboxButton : null;
                return firstAvailable != null && firstAvailable.requestFocus();
            }
            return false;
        });
        cancel.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == (rtl ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT)
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                return manage.requestFocus();
            }
            return false;
        });
        dialog.setOnKeyListener((source, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
                View focused = dialog.getWindow() == null ? null : dialog.getWindow().getCurrentFocus();
                if (focused == adult || focused == childButton || focused == xboxButton) {
                    return manage.requestFocus();
                }
            }
            return false;
        });
        dialog.setContentView(panel);
        configureFamilyDialog(dialog, 0.72f);
        dialog.setOnCancelListener(d -> familySelectorShowing = false);
        dialog.show();
        sizeFamilyDialog(dialog, 0.88f);
        (adultPin ? adult : child.allowed ? childButton : hasXboxInput && xbox.allowed ? xboxButton : manage).requestFocus();
    }

    private void addFamilyProfileTile(LinearLayout row, View tile, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
        lp.setMarginStart(dp(6));
        lp.setMarginEnd(dp(6));
        row.addView(tile, lp);
    }

    private View familyProfileTile(int id, int nameResource, int iconResource, int color,
            String status, boolean enabled, Runnable action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setId(id);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(9), dp(10), dp(9), dp(9));
        tile.setBackgroundResource(R.drawable.family_profile_tile_bg);
        tile.setFocusable(enabled);
        tile.setFocusableInTouchMode(enabled);
        tile.setClickable(enabled);
        tile.setEnabled(enabled);
        tile.setAlpha(enabled ? 1f : 0.62f);
        String name = getString(nameResource);
        tile.setContentDescription(getString(R.string.family_profile_accessibility, name, status));
        if (enabled) tile.setOnClickListener(v -> action.run());

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        icon.setContentDescription(null);
        android.graphics.drawable.GradientDrawable avatar = new android.graphics.drawable.GradientDrawable();
        avatar.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        avatar.setColor(color);
        icon.setBackground(avatar);
        icon.setPadding(dp(17), dp(17), dp(17), dp(17));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(68), dp(68));
        iconLp.bottomMargin = dp(9);
        tile.addView(icon, iconLp);

        TextView label = familyText(name, 20, Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        tile.addView(label, new LinearLayout.LayoutParams(-1, -2));

        TextView detail = familyText(status, 12, 0xFFD1D8E2);
        detail.setGravity(Gravity.CENTER);
        detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(5);
        tile.addView(detail, detailLp);
        return tile;
    }

    private String familyProfileStatus(FamilyAccessPolicy.Decision decision, long remainingMs,
                                       FamilyTimePolicy.Profile profile) {
        String status;
        switch (decision.reason) {
            case MONITOR_REQUIRED:
                status = getString(R.string.family_profile_usage_required);
                break;
            case SESSION_PENDING:
                status = getString(R.string.family_profile_session_pending);
                break;
            case CLOCK_UNCERTAIN:
                status = getString(R.string.family_profile_clock_uncertain);
                break;
            case OUTSIDE_HOURS:
                status = getString(R.string.family_profile_outside_hours);
                break;
            case DAILY_LIMIT_REACHED:
                status = getString(R.string.family_profile_time_over);
                break;
            default:
                status = getString(R.string.family_profile_remaining, durationText(remainingMs));
                break;
        }
        if (decision.reason == FamilyAccessPolicy.Reason.OUTSIDE_HOURS
                || decision.reason == FamilyAccessPolicy.Reason.DAILY_LIMIT_REACHED) {
            String next = nextFamilyAvailability(profile, decision.reason);
            status = getString(R.string.family_profile_status_next, status, next);
        }
        return status;
    }

    private String nextFamilyAvailability(FamilyTimePolicy.Profile profile,
                                          FamilyAccessPolicy.Reason reason) {
        ZonedDateTime now = familyNow();
        LocalDate today = now.toLocalDate();
        FamilyProfileStore.Snapshot current = familyStore.snapshot(today);
        FamilyTimePolicy.Schedule currentSchedule = profile == FamilyTimePolicy.Profile.XBOX
                ? current.xboxSchedule : current.childSchedule;
        int minute = now.getHour() * 60 + now.getMinute();
        boolean laterToday = reason == FamilyAccessPolicy.Reason.OUTSIDE_HOURS
                && minute < currentSchedule.startMinute;
        LocalDate date = laterToday ? today : today.plusDays(1);
        FamilyProfileStore.Snapshot nextDay = laterToday ? current : familyStore.snapshot(date);
        FamilyTimePolicy.Schedule schedule = profile == FamilyTimePolicy.Profile.XBOX
                ? nextDay.xboxSchedule : nextDay.childSchedule;
        int start = schedule.startMinute;
        ZonedDateTime available = date.atStartOfDay(FAMILY_ZONE).plusMinutes(start);
        String time = android.text.format.DateFormat.getTimeFormat(this)
                .format(new java.util.Date(available.toInstant().toEpochMilli()));
        return getString(laterToday ? R.string.family_profile_available_today_at
                : R.string.family_profile_available_tomorrow_at, time);
    }

    private LinearLayout.LayoutParams familyActionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(6));
        lp.setMarginEnd(dp(6));
        return lp;
    }

    private FamilyAccessPolicy.Decision profileChoiceDecision(FamilyTimePolicy.Profile profile,
            FamilyProfileStore.Snapshot snapshot, LocalDate day) {
        if (profile != FamilyTimePolicy.Profile.ADULT && !FamilyUsageAccess.isGranted(this)) {
            return FamilyAccessPolicy.blocked(FamilyAccessPolicy.Reason.MONITOR_REQUIRED);
        }
        if (profile != FamilyTimePolicy.Profile.ADULT && snapshot.sessionPending) {
            return FamilyAccessPolicy.blocked(FamilyAccessPolicy.Reason.SESSION_PENDING);
        }
        ZonedDateTime now = familyNow();
        boolean xbox = profile == FamilyTimePolicy.Profile.XBOX;
        return FamilyAccessPolicy.evaluate(profile,
                xbox ? FamilyAccessPolicy.Target.PASSTHROUGH_INPUT : FamilyAccessPolicy.Target.APP,
                xbox ? snapshot.xboxInputId : null, snapshot.xboxInputId, now,
                xbox ? snapshot.xboxUsedMs : snapshot.childUsedMs,
                xbox ? snapshot.xboxExtensionMs : snapshot.childExtensionMs, snapshot.clockUncertain,
                xbox ? snapshot.xboxSchedule : snapshot.childSchedule, false);
    }

    private boolean isConfiguredXboxInput(String id) {
        if (id == null || id.isEmpty()) return false;
        TvInputInfo info = tvInputInfo(id);
        return info != null && info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI;
    }

    private void selectFamilyProfile(FamilyTimePolicy.Profile profile) {
        if (profile == FamilyTimePolicy.Profile.ADULT) {
            if (familyStore.hasPendingSession()) {
                ZonedDateTime now = familyNow();
                if (!familyStore.recoverInterruptedSession(now.toInstant().toEpochMilli(),
                        SystemClock.elapsedRealtime(), now.toLocalDate())) {
                    Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                    return;
                }
                stopService(new Intent(this, FamilySessionMonitorService.class));
            }
            familyStore.clearReturnHomeOnWake();
            setActiveFamilyProfile(profile);
            return;
        }
        LocalDate today = familyToday();
        if (profile == FamilyTimePolicy.Profile.XBOX) {
            FamilyProfileStore.Snapshot xboxSnapshot = familyStore.snapshot(today);
            if (familyStore.hasStorageError()) {
                Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                return;
            }
            if (!isConfiguredXboxInput(xboxSnapshot.xboxInputId)) {
                showFamilyDecision(FamilyAccessPolicy.Reason.WRONG_XBOX_INPUT);
                handler.postDelayed(this::maybeShowFamilyProfileSelector, 250L);
                return;
            }
            // openInput performs the authoritative time, monitor, and single-session validation.
            setActiveFamilyProfile(profile, false);
            if (!openInput(xboxSnapshot.xboxInputId) && activeFamilyProfile == profile) {
                setActiveFamilyProfile(null);
            }
            return;
        }
        ZonedDateTime currentTime = familyNow();
        familyStore.observeClock(currentTime.toInstant().toEpochMilli(), SystemClock.elapsedRealtime(), today);
        if (familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(today);
        FamilyAccessPolicy.Decision decision = profileChoiceDecision(profile, snapshot, today);
        if (!decision.allowed) {
            showFamilyDecision(decision.reason);
            updateFamilyStatus();
            return;
        }
        setActiveFamilyProfile(profile);
    }

    private LinearLayout familyPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        panel.setPadding(padding, padding, padding, dp(14));
        panel.setBackgroundResource(R.drawable.glass_panel);
        return panel;
    }

    private TextView familyText(int resource, int sizeSp, int color) {
        return familyText(getString(resource), sizeSp, color);
    }

    private TextView familyText(String value, int sizeSp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(sizeSp);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private TextView familyChoice(String value, boolean enabled, Runnable action) {
        TextView button = new TextView(this);
        button.setText(value);
        button.setTextColor(enabled ? Color.WHITE : 0xFF8C929B);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setMinHeight(dp(48));
        button.setBackgroundResource(R.drawable.focus_row_bg);
        button.setFocusable(enabled);
        button.setFocusableInTouchMode(enabled);
        button.setClickable(enabled);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.68f);
        if (enabled) button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams familyChoiceLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(5);
        return lp;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private String durationText(long milliseconds) {
        return android.text.format.DateUtils.formatElapsedTime((Math.max(0L, milliseconds) + 999L) / 1000L);
    }

    private void configureFamilyDialog(Dialog dialog, float dim) {
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(dim);
        }
    }

    private void sizeFamilyDialog(Dialog dialog, float widthFraction) {
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * widthFraction),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void showFamilyManagement() {
        if (!parentalStateAvailable()) return;
        if (familyEnabled() && activeFamilyProfile != null
                && activeFamilyProfile != FamilyTimePolicy.Profile.ADULT) {
            Toast.makeText(this, R.string.family_admin_adult_required, Toast.LENGTH_LONG).show();
            return;
        }
        familyStore.isEnabled();
        if (!launcherState.hasParentalCredential() || launcherState.hasStorageError()) {
            Toast.makeText(this, R.string.family_sequence_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (familyStore.hasStorageError()) {
            authenticate(getString(R.string.family_storage_recovery_title), this::showFamilyStorageRecovery);
            return;
        }
        authenticate(getString(R.string.family_management), this::showFamilyManagementPanel);
    }

    private void showFamilyStorageRecovery() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = familyPanel();
        panel.addView(familyText(R.string.family_storage_recovery_title, 22, Color.WHITE),
                new LinearLayout.LayoutParams(-1, -2));
        TextView help = familyText(R.string.family_storage_recovery_help, 15, 0xFFD8DDE5);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(-1, -2);
        helpLp.topMargin = dp(8);
        panel.addView(help, helpLp);

        TextView recover = familyChoice(getString(R.string.family_storage_recovery_confirm), true, () -> {
            ZonedDateTime now = familyNow();
            if (!familyStore.recoverStorageAfterAdultAuthentication(now.toInstant().toEpochMilli(),
                    SystemClock.elapsedRealtime(), now.toLocalDate())) {
                Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                return;
            }
            stopService(new Intent(this, FamilySessionMonitorService.class));
            setActiveFamilyProfile(null);
            dialog.dismiss();
            Toast.makeText(this, R.string.family_storage_recovered, Toast.LENGTH_LONG).show();
        });
        panel.addView(recover, familyChoiceLp());
        TextView cancel = familyChoice(getString(R.string.cancel), true, dialog::dismiss);
        panel.addView(cancel, familyChoiceLp());
        dialog.setContentView(panel);
        configureFamilyDialog(dialog, 0.68f);
        dialog.show();
        sizeFamilyDialog(dialog, 0.72f);
        recover.requestFocus();
    }

    private void showFamilyManagementPanel() {
        if (!initializeChildAppsIfNeeded()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        LocalDate today = familyToday();
        ZonedDateTime now = familyNow();
        familyStore.observeClock(now.toInstant().toEpochMilli(), SystemClock.elapsedRealtime(), today);
        FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(today);
        if (familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(42), dp(22), dp(42), dp(16));
        panel.setBackgroundResource(R.drawable.family_management_background);
        panel.addView(familyText(R.string.family_management_brand, 12, 0xFF91BFFF),
                new LinearLayout.LayoutParams(-1, -2));
        TextView title = familyText(R.string.family_management, 30, Color.WHITE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(3);
        panel.addView(title, titleLp);
        TextView help = familyText(R.string.family_management_help, 15, 0xFFC5CFDD);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(-1, -2);
        helpLp.topMargin = dp(2);
        panel.addView(help, helpLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = dp(13);
        scrollLp.bottomMargin = dp(12);
        panel.addView(scroll, scrollLp);
        boolean usageAccess = FamilyUsageAccess.isGranted(this);
        content.addView(familyText(R.string.family_management_today, 15, 0xFFB9D8FC),
                familyChoiceLp());
        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(-1, -2);
        summaryLp.topMargin = dp(7);
        content.addView(summary, summaryLp);
        long childRemainingMs = snapshot.remainingMs(FamilyProfileStore.Profile.CHILD_TV, today);
        long xboxRemainingMs = snapshot.remainingMs(FamilyProfileStore.Profile.XBOX, today);
        TextView childSummary = addFamilyManagementSummaryCard(summary, R.string.family_profile_child_short,
                getString(R.string.family_management_time_summary, familyDisplayMinutes(snapshot.childUsedMs),
                        familyDisplayMinutes(safeAdd(snapshot.childUsedMs, childRemainingMs)),
                        familyRemainingMinutes(childRemainingMs)),
                Color.WHITE, false);
        childSummary.setContentDescription(getString(R.string.family_time_label,
                durationText(snapshot.childUsedMs), durationText(childRemainingMs)));
        TextView xboxSummary = addFamilyManagementSummaryCard(summary, R.string.family_profile_xbox_short,
                getString(R.string.family_management_time_summary, familyDisplayMinutes(snapshot.xboxUsedMs),
                        familyDisplayMinutes(safeAdd(snapshot.xboxUsedMs, xboxRemainingMs)),
                        familyRemainingMinutes(xboxRemainingMs)),
                Color.WHITE, false);
        xboxSummary.setContentDescription(getString(R.string.family_time_label,
                durationText(snapshot.xboxUsedMs), durationText(xboxRemainingMs)));
        addFamilyManagementSummaryCard(summary, R.string.family_management_protection_status,
                familyReliabilityStatus(snapshot, usageAccess),
                familyReliabilityColor(snapshot, usageAccess), true);
        TextView hours = familyText(R.string.family_time_hours, 13, 0xFF9EADC0);
        LinearLayout.LayoutParams hoursLp = new LinearLayout.LayoutParams(-1, -2);
        hoursLp.topMargin = dp(5);
        content.addView(hours, hoursLp);

        LinearLayout accessSection = familyManagementSection(content,
                R.string.family_management_profiles_section);
        accessSection.addView(familyText(R.string.family_management_sequence_ready, 13, 0xFF9FE2BE),
                familyManagementNoteLp());
        TextView usageStatus = familyText(usageAccess ? R.string.family_management_usage_ready
                : R.string.family_management_usage_missing, 13,
                usageAccess ? 0xFF9FE2BE : 0xFFFFD180);
        usageStatus.setContentDescription(getString(usageAccess ? R.string.family_usage_access_granted
                : R.string.family_usage_access_missing));
        accessSection.addView(usageStatus, familyManagementNoteLp());
        accessSection.addView(familyText(getString(R.string.family_management_allowed_apps_count,
                        familyAllowedChildAppCount()), 13, 0xFFB8C8DB), familyManagementNoteLp());
        TextView firstAction = addFamilyManagementSectionAction(accessSection,
                R.string.family_usage_access_open, this::openFamilyUsageAccessSettings);
        addFamilyManagementSectionAction(accessSection, R.string.sequence_change, this::changeUnlockSequence);
        addFamilyManagementSectionAction(accessSection, R.string.family_child_apps_action, () -> {
            dialog.dismiss();
            showFamilyChildApps();
        });

        LinearLayout overlaySection = familyManagementSection(content, R.string.family_time_bar_section);
        TextView overlayHelp = familyText(R.string.family_time_bar_help, 13, 0xFFC5CFDD);
        overlaySection.addView(overlayHelp, familyManagementNoteLp());
        TextView overlayStatus = familyText(FamilyTimeOverlay.hasPermission(this)
                ? R.string.family_time_bar_permission_granted : R.string.family_time_bar_permission_missing,
                13, FamilyTimeOverlay.hasPermission(this) ? 0xFF9FE2BE : 0xFFFFD180);
        familyOverlayPermissionStatus = overlayStatus;
        overlaySection.addView(overlayStatus, familyManagementNoteLp());
        dialog.setOnDismissListener(ignored -> {
            if (familyOverlayPermissionStatus == overlayStatus) familyOverlayPermissionStatus = null;
        });
        addFamilyManagementSectionAction(overlaySection, R.string.family_time_bar_open_settings,
                this::openFamilyOverlaySettings);

        boolean[] showTimeBar = { snapshot.timeBarVisible };
        TextView visibilityOption = familyChoice(getString(showTimeBar[0]
                ? R.string.family_time_bar_visible : R.string.family_time_bar_hidden), true, () -> { });
        visibilityOption.setId(View.generateViewId());
        visibilityOption.setOnClickListener(view -> {
            boolean next = !showTimeBar[0];
            if (familyStore.setTimeBarVisible(next)) {
                showTimeBar[0] = next;
                visibilityOption.setText(getString(next ? R.string.family_time_bar_visible
                        : R.string.family_time_bar_hidden));
            } else Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
        });
        overlaySection.addView(visibilityOption, familyManagementActionLp());

        FamilyProfileStore.TimeBarCorner[] barCorner = { snapshot.timeBarCorner };
        TextView cornerOption = familyChoice(timeBarCornerLabel(barCorner[0]), true, () -> { });
        cornerOption.setId(View.generateViewId());
        cornerOption.setOnClickListener(view -> {
            FamilyProfileStore.TimeBarCorner next = barCorner[0] == FamilyProfileStore.TimeBarCorner.LEFT
                    ? FamilyProfileStore.TimeBarCorner.RIGHT : FamilyProfileStore.TimeBarCorner.LEFT;
            if (familyStore.setTimeBarCorner(next)) {
                barCorner[0] = next;
                cornerOption.setText(timeBarCornerLabel(next));
            } else Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
        });
        overlaySection.addView(cornerOption, familyManagementActionLp());

        FamilyProfileStore.TimeBarContent[] barContent = { snapshot.timeBarContent };
        TextView contentOption = familyChoice(timeBarContentLabel(barContent[0]), true, () -> { });
        contentOption.setId(View.generateViewId());
        contentOption.setOnClickListener(view -> {
            FamilyProfileStore.TimeBarContent next = nextTimeBarContent(barContent[0]);
            if (familyStore.setTimeBarContent(next)) {
                barContent[0] = next;
                contentOption.setText(timeBarContentLabel(next));
            } else Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
        });
        overlaySection.addView(contentOption, familyManagementActionLp());

        String inputName = getString(R.string.family_xbox_input_required);
        TvInputInfo configured = tvInputInfo(snapshot.xboxInputId);
        if (configured != null) {
            CharSequence label = configured.loadLabel(this);
            inputName = label == null ? snapshot.xboxInputId : label.toString();
        }
        TextView input = new TextView(this);
        input.setText(getString(R.string.family_xbox_input_status, getString(R.string.family_xbox_input), inputName));
        input.setTextColor(Color.WHITE);
        input.setTextSize(14);
        LinearLayout xboxSection = familyManagementSection(content,
                R.string.family_management_xbox_section);
        xboxSection.addView(input, familyManagementNoteLp());
        TextView limitations = familyText(R.string.family_limitations, 13, 0xFFFFD180);
        LinearLayout.LayoutParams limitLp = familyManagementNoteLp();
        limitLp.topMargin = dp(7);
        xboxSection.addView(limitations, limitLp);
        addFamilyManagementSectionAction(xboxSection, R.string.family_xbox_input, () -> {
            dialog.dismiss();
            showFamilyInputChooser();
        });
        TextView lastAction = addFamilyManagementSectionAction(accessSection,
                snapshot.enabled ? R.string.family_disable : R.string.family_enable,
                () -> {
                    if (!snapshot.enabled && (!launcherState.hasParentalCredential() || launcherState.hasStorageError())) {
                        Toast.makeText(this, R.string.family_sequence_required, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!snapshot.enabled && !FamilyUsageAccess.isGranted(this)) {
                        dialog.dismiss();
                        Toast.makeText(this, R.string.family_monitor_required, Toast.LENGTH_LONG).show();
                        openFamilyUsageAccessSettings();
                        return;
                    }
                    if (!snapshot.enabled) {
                        ZonedDateTime verifiedNow = familyNow();
                        if (!familyStore.confirmClock(verifiedNow.toInstant().toEpochMilli(),
                                SystemClock.elapsedRealtime(), verifiedNow.toLocalDate())) {
                            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    if (!familyStore.setEnabled(!snapshot.enabled)) {
                        Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (snapshot.enabled) {
                        finishFamilyUsageSession();
                        stopService(new Intent(this, FamilySessionMonitorService.class));
                        setActiveFamilyProfile(null);
                    } else {
                        reloadTiles();
                    }
                    dialog.dismiss();
                    updateFamilyStatus();
                    showFamilyManagementPanel();
                });
        LinearLayout toolsSection = null;
        if (snapshot.enabled) {
            toolsSection = familyManagementSection(content, R.string.family_management_tools_section);
            lastAction = addFamilyManagementSectionAction(toolsSection, R.string.family_diagnostic_title,
                    () -> showFamilyDiagnostics(dialog));
            lastAction = addFamilyManagementSectionAction(toolsSection, R.string.family_statistics_title,
                    () -> { dialog.dismiss(); showFamilyStatistics(); });
            lastAction = addFamilyManagementSectionAction(toolsSection, R.string.family_advanced_schedule,
                    () -> { dialog.dismiss(); showFamilyScheduleChooser(); });
            lastAction = addFamilyManagementSectionAction(toolsSection, R.string.family_add_time_child, () ->
                    chooseFamilyTimeExtension(dialog, FamilyProfileStore.Profile.CHILD_TV, today));
            lastAction = addFamilyManagementSectionAction(xboxSection, R.string.family_add_time_xbox, () ->
                    chooseFamilyTimeExtension(dialog, FamilyProfileStore.Profile.XBOX, today));
        }
        if (snapshot.clockUncertain) {
            if (toolsSection == null) toolsSection = familyManagementSection(content,
                    R.string.family_management_tools_section);
            toolsSection.addView(familyText(R.string.family_clock_uncertain, 13, 0xFFFFD180),
                    familyManagementNoteLp());
            lastAction = addFamilyManagementSectionAction(toolsSection, R.string.family_clock_confirm, () -> {
                ZonedDateTime confirmedNow = familyNow();
                if (familyStore.confirmClock(confirmedNow.toInstant().toEpochMilli(),
                        SystemClock.elapsedRealtime(), confirmedNow.toLocalDate())) {
                    dialog.dismiss();
                    setActiveFamilyProfile(null);
                    showFamilyManagementPanel();
                } else Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            });
        }
        TextView close = familyChoice(getString(R.string.close), true, () -> {
            dialog.dismiss();
            if (familyEnabled() && activeFamilyProfile == null && adapter != null) reloadApps(false);
            maybeShowFamilyProfileSelector();
        });
        close.setId(View.generateViewId());
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(22), dp(8), dp(22), dp(8));
        close.setBackgroundResource(R.drawable.focus_button_bg);
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(familyText(R.string.family_management_back_hint, 13, 0xFF9EADC0),
                new LinearLayout.LayoutParams(0, -2, 1f));
        footer.addView(close, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        if (lastAction != null) {
            lastAction.setNextFocusDownId(close.getId());
            close.setNextFocusUpId(lastAction.getId());
        }
        firstAction.setNextFocusUpId(firstAction.getId());

        Runnable returnToProfiles = () -> {
            if (familyEnabled() && activeFamilyProfile == null && adapter != null) reloadApps(false);
            maybeShowFamilyProfileSelector();
        };
        dialog.setOnCancelListener(ignored -> returnToProfiles.run());

        dialog.setContentView(panel);
        configureFamilyDialog(dialog, 0f);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.FILL);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        firstAction.requestFocus();
    }

    private LinearLayout familyManagementSection(LinearLayout content, int title) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(16), dp(13), dp(16), dp(15));
        section.setBackgroundResource(R.drawable.family_management_card_background);
        section.addView(familyText(title, 16, 0xFFB9D8FC),
                new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.topMargin = dp(11);
        content.addView(section, sectionLp);
        return section;
    }

    private TextView addFamilyManagementSummaryCard(LinearLayout row, int title, String value,
                                                    int valueColor, boolean isStatus) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setMinimumHeight(dp(82));
        card.setBackgroundResource(R.drawable.family_management_summary_background);
        card.addView(familyText(title, 13, 0xFF9EADC0),
                new LinearLayout.LayoutParams(-1, -2));
        TextView detail = familyText(value, isStatus ? 12 : 13, valueColor);
        detail.setGravity(Gravity.TOP);
        detail.setMaxLines(isStatus ? 3 : 2);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(4);
        card.addView(detail, detailLp);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, -2, 1f);
        cardLp.setMarginEnd(dp(8));
        row.addView(card, cardLp);
        return detail;
    }

    private TextView addFamilyManagementSectionAction(LinearLayout section, int label, Runnable action) {
        TextView button = familyChoice(getString(label), true, action);
        button.setId(View.generateViewId());
        section.addView(button, familyManagementActionLp());
        return button;
    }

    private LinearLayout.LayoutParams familyManagementActionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(7);
        return lp;
    }

    private LinearLayout.LayoutParams familyManagementNoteLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(5);
        return lp;
    }

    private int familyAllowedChildAppCount() {
        return familyStore.childAllowedApps().size();
    }

    private static long familyDisplayMinutes(long milliseconds) {
        return Math.max(0L, milliseconds) / MINUTE_MS;
    }

    private static long familyRemainingMinutes(long milliseconds) {
        long remaining = Math.max(0L, milliseconds);
        return remaining / MINUTE_MS + (remaining % MINUTE_MS == 0L ? 0L : 1L);
    }

    private static long safeAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private void showFamilyChildApps() {
        if (familyEnabled() && activeFamilyProfile != null
                && activeFamilyProfile != FamilyTimePolicy.Profile.ADULT) {
            Toast.makeText(this, R.string.family_admin_adult_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (!initializeChildAppsIfNeeded()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = getPackageManager().queryIntentActivities(query, 0);
        java.util.Map<String, ResolveInfo> unique = new java.util.HashMap<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) continue;
            unique.putIfAbsent(info.activityInfo.packageName, info);
        }
        List<ResolveInfo> applications = new ArrayList<>(unique.values());
        Collections.sort(applications, (a, b) -> String.valueOf(a.loadLabel(getPackageManager()))
                .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager()))));

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = familyPanel();
        panel.addView(familyText(R.string.family_child_apps_title, 22, Color.WHITE),
                new LinearLayout.LayoutParams(-1, -2));
        TextView help = familyText(R.string.family_child_apps_help, 14, 0xFFD8DDE5);
        LinearLayout.LayoutParams helpLp = familyChoiceLp();
        helpLp.topMargin = dp(4);
        panel.addView(help, helpLp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = dp(10);
        scrollLp.bottomMargin = dp(8);
        panel.addView(scroll, scrollLp);
        TextView saved = familyText("", 13, 0xFF9FE2BE);

        if (applications.isEmpty()) {
            TextView empty = familyText(R.string.no_apps_found, 16, 0xFFD8DDE5);
            empty.setFocusable(true);
            rows.addView(empty, familyChoiceLp());
        }
        for (ResolveInfo info : applications) {
            String pkg = info.activityInfo.packageName;
            String label = Cards.cleanLabel(String.valueOf(info.loadLabel(getPackageManager())), pkg);
            long approvedInstallTime = packageFirstInstallTime(pkg);
            boolean[] visible = { approvedInstallTime >= 0L
                    && familyStore.isChildAppAllowed(pkg, approvedInstallTime) };
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackgroundResource(R.drawable.focus_row_bg);
            row.setFocusable(true);
            row.setClickable(true);

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(info.loadIcon(getPackageManager()));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(38), dp(38));
            iconLp.setMarginEnd(dp(12));
            row.addView(icon, iconLp);

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = familyText(label, 17, Color.WHITE);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
            TextView state = familyText("", 13, 0xFFB8C3D1);
            LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-1, -2);
            stateLp.topMargin = dp(2);
            labels.addView(state, stateLp);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView marker = familyText("", 18, 0xFF9FE2BE);
            marker.setGravity(Gravity.CENTER);
            row.addView(marker, new LinearLayout.LayoutParams(dp(48), -2));
            Runnable updateState = () -> {
                int text = visible[0] ? R.string.family_child_app_visible : R.string.family_child_app_hidden;
                state.setText(text);
                marker.setText(visible[0] ? "✓" : "—");
                row.setContentDescription(getString(R.string.family_profile_accessibility,
                        label, getString(text)));
            };
            updateState.run();
            row.setOnClickListener(view -> {
                try {
                    getPackageManager().getActivityInfo(
                            new ComponentName(pkg, info.activityInfo.name), 0);
                } catch (Exception removed) {
                    Toast.makeText(this, R.string.family_child_app_unavailable, Toast.LENGTH_SHORT).show();
                    return;
                }
                long currentInstallTime = packageFirstInstallTime(pkg);
                if (currentInstallTime < 0L) {
                    Toast.makeText(this, R.string.family_child_app_unavailable, Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean next = !visible[0];
                if (!familyStore.setChildAppAllowed(pkg, currentInstallTime, next)) {
                    Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                    return;
                }
                visible[0] = next;
                updateState.run();
                saved.setText(R.string.family_child_app_saved);
            });
            LinearLayout.LayoutParams rowLp = familyChoiceLp();
            rowLp.bottomMargin = dp(4);
            rows.addView(row, rowLp);
        }
        panel.addView(saved, familyChoiceLp());
        TextView close = familyChoice(getString(R.string.close), true, () -> {
            dialog.dismiss();
            showFamilyManagementPanel();
        });
        panel.addView(close, familyChoiceLp());
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(ignored -> showFamilyManagementPanel());
        configureFamilyDialog(dialog, 0.74f);
        dialog.show();
        sizeFamilyDialog(dialog, 0.84f);
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
        else close.requestFocus();
    }

    private void addFamilyManagementAction(LinearLayout content, int label, Runnable action) {
        TextView button = familyChoice(getString(label), true, action);
        content.addView(button, familyChoiceLp());
    }

    private String timeBarCornerLabel(FamilyProfileStore.TimeBarCorner corner) {
        return getString(R.string.family_time_bar_corner_status,
                getString(corner == FamilyProfileStore.TimeBarCorner.RIGHT
                        ? R.string.family_time_bar_corner_right : R.string.family_time_bar_corner_left));
    }

    private String timeBarContentLabel(FamilyProfileStore.TimeBarContent content) {
        int label = content == FamilyProfileStore.TimeBarContent.USED ? R.string.family_time_bar_content_used
                : content == FamilyProfileStore.TimeBarContent.REMAINING
                    ? R.string.family_time_bar_content_remaining : R.string.family_time_bar_content_both;
        return getString(R.string.family_time_bar_content_status, getString(label));
    }

    private static FamilyProfileStore.TimeBarContent nextTimeBarContent(
            FamilyProfileStore.TimeBarContent content) {
        if (content == FamilyProfileStore.TimeBarContent.BOTH) return FamilyProfileStore.TimeBarContent.REMAINING;
        if (content == FamilyProfileStore.TimeBarContent.REMAINING) return FamilyProfileStore.TimeBarContent.USED;
        return FamilyProfileStore.TimeBarContent.BOTH;
    }

    private void updateFamilyOverlayPermissionStatus() {
        if (familyOverlayPermissionStatus == null) return;
        boolean granted = FamilyTimeOverlay.hasPermission(this);
        familyOverlayPermissionStatus.setText(granted ? R.string.family_time_bar_permission_granted
                : R.string.family_time_bar_permission_missing);
        familyOverlayPermissionStatus.setTextColor(granted ? 0xFFB8C3D1 : 0xFFFFD180);
    }

    private void openFamilyOverlaySettings() {
        try {
            Intent settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, R.string.family_time_bar_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private String familyReliabilityStatus(FamilyProfileStore.Snapshot snapshot, boolean usageAccess) {
        if (!snapshot.enabled) return getString(R.string.family_protection_disabled);
        if (familyStore.hasStorageError()) return getString(R.string.family_protection_storage_error);
        if (!usageAccess) return getString(R.string.family_protection_limited);
        if (snapshot.clockUncertain) return getString(R.string.family_protection_clock_review);
        if (FamilyMonitorPolicy.isSessionStale(snapshot.sessionPending, snapshot.sessionQueryWallMs,
                System.currentTimeMillis())) return getString(R.string.family_protection_monitor_stale);
        return getString(R.string.family_protection_ready);
    }

    private int familyReliabilityColor(FamilyProfileStore.Snapshot snapshot, boolean usageAccess) {
        return snapshot.enabled && !familyStore.hasStorageError() && usageAccess && !snapshot.clockUncertain
                && !FamilyMonitorPolicy.isSessionStale(snapshot.sessionPending, snapshot.sessionQueryWallMs,
                        System.currentTimeMillis())
                ? 0xFF9FE2BE : 0xFFFFD180;
    }

    private void chooseFamilyTimeExtension(Dialog parent, FamilyProfileStore.Profile profile, LocalDate day) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = familyPanel();
        panel.addView(familyText(R.string.family_add_time_title, 20, Color.WHITE));
        for (int minutes : new int[]{15, 30, 60}) {
            int label = minutes == 15 ? R.string.family_add_time_15
                    : minutes == 30 ? R.string.family_add_time_30 : R.string.family_add_time_60;
            addFamilyManagementAction(panel, label, () -> addFamilyTime(dialog, parent, profile, day, minutes));
        }
        addFamilyManagementAction(panel, R.string.cancel, dialog::dismiss);
        dialog.setContentView(panel);
        configureFamilyDialog(dialog, 0.68f);
        dialog.show();
        sizeFamilyDialog(dialog, 0.46f);
        if (panel.getChildCount() > 1) panel.getChildAt(1).requestFocus();
    }

    private void addFamilyTime(Dialog dialog, Dialog parent, FamilyProfileStore.Profile profile,
                               LocalDate day, int minutes) {
        if (!familyStore.addExtension(profile, day, minutes)) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        dialog.dismiss();
        parent.dismiss();
        Toast.makeText(this, getResources().getQuantityString(R.plurals.family_time_added_minutes,
                minutes, minutes), Toast.LENGTH_SHORT).show();
        showFamilyManagementPanel();
    }

    private void showFamilyDiagnostics(Dialog parent) {
        if (!familyEnabled() || familyStore.hasStorageError() || !FamilyUsageAccess.isGranted(this)) {
            Toast.makeText(this, R.string.family_diagnostic_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = familyPanel();
        panel.addView(familyText(R.string.family_diagnostic_title, 21, Color.WHITE));
        TextView help = familyText(R.string.family_diagnostic_help, 14, 0xFFD8DDE5);
        panel.addView(help, familyChoiceLp());
        addFamilyManagementAction(panel, R.string.family_diagnostic_child_2, () ->
                beginDiagnostic(FamilyTimePolicy.Profile.CHILD_TV, 2, dialog, parent));
        addFamilyManagementAction(panel, R.string.family_diagnostic_child_5, () ->
                beginDiagnostic(FamilyTimePolicy.Profile.CHILD_TV, 5, dialog, parent));
        addFamilyManagementAction(panel, R.string.family_diagnostic_xbox_2, () ->
                beginDiagnostic(FamilyTimePolicy.Profile.XBOX, 2, dialog, parent));
        addFamilyManagementAction(panel, R.string.family_diagnostic_xbox_5, () ->
                beginDiagnostic(FamilyTimePolicy.Profile.XBOX, 5, dialog, parent));
        addFamilyManagementAction(panel, R.string.cancel, dialog::dismiss);
        dialog.setContentView(panel);
        configureFamilyDialog(dialog, 0.7f);
        dialog.show();
        sizeFamilyDialog(dialog, 0.52f);
        if (panel.getChildCount() > 2) panel.getChildAt(2).requestFocus();
    }

    private void beginDiagnostic(FamilyTimePolicy.Profile profile, int minutes, Dialog dialog, Dialog parent) {
        if (!familyEnabled() || !FamilyUsageAccess.isGranted(this) || familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_diagnostic_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (profile == FamilyTimePolicy.Profile.XBOX) {
            FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(familyToday());
            if (!isConfiguredXboxInput(snapshot.xboxInputId)) {
                Toast.makeText(this, R.string.family_xbox_input_required, Toast.LENGTH_LONG).show();
                return;
            }
            diagnosticMinutesNextLaunch = minutes;
            setActiveFamilyProfile(profile, false);
            dialog.dismiss();
            parent.dismiss();
            if (!openInput(snapshot.xboxInputId)) {
                diagnosticMinutesNextLaunch = 0;
                setActiveFamilyProfile(null);
                Toast.makeText(this, R.string.family_diagnostic_start_failed, Toast.LENGTH_LONG).show();
                showFamilyManagementPanel();
            }
            return;
        }
        diagnosticMinutesNextLaunch = minutes;
        setActiveFamilyProfile(profile);
        dialog.dismiss();
        parent.dismiss();
        Toast.makeText(this, R.string.family_diagnostic_choose_app, Toast.LENGTH_LONG).show();
    }

    private void showFamilyStatistics() {
        LocalDate today = familyToday();
        Map<LocalDate, Long> child = familyStore.usageHistory(FamilyProfileStore.Profile.CHILD_TV, today, 7);
        Map<LocalDate, Long> xbox = familyStore.usageHistory(FamilyProfileStore.Profile.XBOX, today, 7);
        if (familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = familyPanel();
        panel.addView(familyText(R.string.family_statistics_title, 21, Color.WHITE));
        panel.addView(familyText(R.string.family_statistics_local, 13, 0xFFB8C3D1), familyChoiceLp());
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = dp(8);
        panel.addView(scroll, scrollLp);
        DateTimeFormatter labelFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault());
        for (int offset = 6; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            rows.addView(familyChoice(getString(R.string.family_statistics_day,
                    day.format(labelFormat), durationText(child.getOrDefault(day, 0L)),
                    durationText(xbox.getOrDefault(day, 0L))), true, () -> { }), familyChoiceLp());
        }
        addFamilyManagementAction(panel, R.string.close, dialog::dismiss);
        dialog.setContentView(panel);
        configureFamilyDialog(dialog, 0.68f);
        dialog.show();
        sizeFamilyDialog(dialog, 0.6f);
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    private void showFamilyScheduleChooser() {
        showFamilySchedulePage(null);
    }

    private void showFamilySchedulePage(FamilyProfileStore.Profile profile) {
        if (familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = familyPanel();
        View initialFocus = null;
        panel.addView(familyText(profile == null ? R.string.family_advanced_schedule
                : profile == FamilyProfileStore.Profile.CHILD_TV ? R.string.family_profile_child
                : R.string.family_profile_xbox, 21, Color.WHITE));
        if (profile == null) {
            addFamilyManagementAction(panel, R.string.family_profile_child, () -> {
                dialog.dismiss(); showFamilySchedulePage(FamilyProfileStore.Profile.CHILD_TV);
            });
            initialFocus = panel.getChildAt(panel.getChildCount() - 1);
            addFamilyManagementAction(panel, R.string.family_profile_xbox, () -> {
                dialog.dismiss(); showFamilySchedulePage(FamilyProfileStore.Profile.XBOX);
            });
        } else {
            TextView help = familyText(R.string.family_schedule_help, 13, 0xFFD8DDE5);
            panel.addView(help, familyChoiceLp());
            ScrollView scroll = new ScrollView(this);
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
            View firstRow = null;
            for (DayOfWeek day : DayOfWeek.values()) {
                FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(LocalDate.now(FAMILY_ZONE)
                        .with(java.time.temporal.TemporalAdjusters.nextOrSame(day)));
                FamilyTimePolicy.Schedule schedule = profile == FamilyProfileStore.Profile.XBOX
                        ? snapshot.xboxSchedule : snapshot.childSchedule;
                String label = day.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault());
                TextView row = familyChoice(scheduleLabel(label, schedule), true,
                        () -> editFamilySchedule(profile, day));
                rows.addView(row, familyChoiceLp());
                if (firstRow == null) firstRow = row;
            }
            initialFocus = firstRow;
        }
        addFamilyManagementAction(panel, R.string.close, () -> {
            dialog.dismiss();
            if (profile != null) showFamilyScheduleChooser();
            else showFamilyManagementPanel();
        });
        dialog.setContentView(panel);
        configureFamilyDialog(dialog, 0.68f);
        dialog.show();
        sizeFamilyDialog(dialog, profile == null ? 0.45f : 0.66f);
        if (initialFocus != null) initialFocus.requestFocus();
    }

    private String scheduleLabel(String day, FamilyTimePolicy.Schedule schedule) {
        return getString(R.string.family_schedule_day, day, formatMinute(schedule.startMinute),
                formatMinute(schedule.endMinute), (int) (schedule.budgetMs / FamilyTimePolicy.MINUTE_MS));
    }

    private String formatMinute(int minute) {
        return String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60);
    }

    private void editFamilySchedule(FamilyProfileStore.Profile profile, DayOfWeek day) {
        LocalDate referenceDate = LocalDate.now(FAMILY_ZONE).with(java.time.temporal.TemporalAdjusters.nextOrSame(day));
        FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(referenceDate);
        if (familyStore.hasStorageError()) {
            Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
            return;
        }
        FamilyTimePolicy.Schedule initial = profile == FamilyProfileStore.Profile.XBOX
                ? snapshot.xboxSchedule : snapshot.childSchedule;
        int[] values = {initial.startMinute, initial.endMinute,
                (int) (initial.budgetMs / FamilyTimePolicy.MINUTE_MS)};
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = familyPanel();
        String dayName = day.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault());
        panel.addView(familyText(getString(R.string.family_schedule_edit_title, dayName), 20, Color.WHITE));
        TextView start = familyChoice("", true, () -> { });
        TextView end = familyChoice("", true, () -> { });
        TextView limit = familyChoice("", true, () -> { });
        Runnable refresh = () -> {
            start.setText(getString(R.string.family_schedule_start, formatMinute(values[0])));
            end.setText(getString(R.string.family_schedule_end, formatMinute(values[1])));
            limit.setText(getString(R.string.family_schedule_limit, values[2]));
        };
        int[] starts = {0, 5 * 60, 6 * 60, 7 * 60, 8 * 60, 9 * 60, 10 * 60,
                12 * 60, 14 * 60, 16 * 60, 18 * 60, 20 * 60};
        int[] ends = {12 * 60, 14 * 60, 16 * 60, 18 * 60, 19 * 60, 20 * 60,
                21 * 60, 22 * 60, 23 * 60, 24 * 60};
        int[] limits = {15, 30, 45, 60, 75, 90, 120, 150, 180, 240, 300, 360, 420, 480};
        start.setOnClickListener(v -> {
            int[] eligible = Arrays.stream(starts).filter(value -> value < values[1]).toArray();
            values[0] = nextChoice(values[0], eligible);
            refresh.run();
        });
        end.setOnClickListener(v -> {
            int[] eligible = Arrays.stream(ends).filter(value -> value > values[0]).toArray();
            values[1] = nextChoice(values[1], eligible);
            refresh.run();
        });
        limit.setOnClickListener(v -> { values[2] = nextChoice(values[2], limits); refresh.run(); });
        refresh.run();
        panel.addView(start, familyChoiceLp());
        panel.addView(end, familyChoiceLp());
        panel.addView(limit, familyChoiceLp());
        addFamilyManagementAction(panel, R.string.family_schedule_restore_default, () -> {
            FamilyTimePolicy.Schedule defaults = FamilyTimePolicy.defaultSchedule(
                    profile == FamilyProfileStore.Profile.XBOX ? FamilyTimePolicy.Profile.XBOX
                            : FamilyTimePolicy.Profile.CHILD_TV, day);
            values[0] = defaults.startMinute;
            values[1] = defaults.endMinute;
            values[2] = (int) (defaults.budgetMs / FamilyTimePolicy.MINUTE_MS);
            refresh.run();
        });
        addFamilyManagementAction(panel, R.string.save, () -> {
            if (!familyStore.setSchedule(profile, day, values[0], values[1], values[2])) {
                Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            Toast.makeText(this, R.string.family_schedule_saved, Toast.LENGTH_SHORT).show();
            showFamilySchedulePage(profile);
        });
        addFamilyManagementAction(panel, R.string.cancel, dialog::dismiss);
        dialog.setContentView(panel);
        configureFamilyDialog(dialog, 0.68f);
        dialog.show();
        sizeFamilyDialog(dialog, 0.5f);
        start.requestFocus();
    }

    private static int nextChoice(int current, int[] choices) {
        if (choices == null || choices.length == 0) return current;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == current) return choices[(i + 1) % choices.length];
            if (i == choices.length - 1) return choices[0];
        }
        return choices[0];
    }

    private void showFamilyInputChooser() {
        TvInputManager manager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> hdmi = new ArrayList<>();
        if (manager != null) {
            for (TvInputInfo info : manager.getTvInputList()) {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
            }
        }
        if (hdmi.isEmpty()) {
            Toast.makeText(this, R.string.family_xbox_input_none, Toast.LENGTH_LONG).show();
            showFamilyManagementPanel();
            return;
        }
        Collections.sort(hdmi, (a, b) -> String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = familyPanel();
        panel.addView(familyText(R.string.family_xbox_input, 22, Color.WHITE));
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (TvInputInfo info : hdmi) {
            String label = String.valueOf(info.loadLabel(this));
            TextView choice = familyChoice(label, true, () -> {
                if (!familyStore.setXboxInputId(info.getId())) {
                    Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, R.string.family_xbox_input_saved, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                showFamilyManagementPanel();
            });
            rows.addView(choice, familyChoiceLp());
        }
        TextView cancel = familyChoice(getString(R.string.cancel), true, () -> {
            dialog.dismiss();
            showFamilyManagementPanel();
        });
        panel.addView(cancel, familyChoiceLp());
        dialog.setContentView(panel);
        configureFamilyDialog(dialog, 0.68f);
        dialog.show();
        sizeFamilyDialog(dialog, 0.62f);
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    private boolean performOpen(Intent i, boolean showError) {
        if (!familyAllowsIntent(i)) return false;
        return dispatchAuthorizedOpen(i, showError);
    }

    private boolean dispatchAuthorizedOpen(Intent i, boolean showError) {
        boolean familySession = familyEnabled();
        boolean tracking = familySession && beginFamilyUsageSession(i);
        if (familySession && !tracking) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean xboxInput = familyEnabled() && activeFamilyProfile == FamilyTimePolicy.Profile.XBOX
                && passthroughInputId(i.getData()) != null;
        long dispatchStarted = SystemClock.elapsedRealtime();
        if (xboxInput) xboxLauncherAttemptStartedMs = dispatchStarted;
        try {
            startActivity(i);
            familyLaunchInFlight = familyEnabled();
            if (xboxInput) {
                Log.i(FAMILY_XBOX_TIMING_TAG, "launcher_dispatch_ms="
                        + (SystemClock.elapsedRealtime() - dispatchStarted) + " accepted=true");
                long attempt = dispatchStarted;
                handler.postDelayed(() -> {
                    if (xboxLauncherAttemptStartedMs == attempt) xboxLauncherAttemptStartedMs = 0L;
                }, 15_000L);
            }
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (xboxInput) {
                xboxLauncherAttemptStartedMs = 0L;
                Log.i(FAMILY_XBOX_TIMING_TAG, "launcher_dispatch_ms="
                        + (SystemClock.elapsedRealtime() - dispatchStarted) + " accepted=false");
            }
            if (tracking) abortFamilyUsageSession();
            if (showError) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private boolean openInput(String inputId) {
        boolean xbox = familyEnabled() && activeFamilyProfile == FamilyTimePolicy.Profile.XBOX;
        long launcherStarted = SystemClock.elapsedRealtime();
        if (inputId == null || inputId.isEmpty()) return logXboxLauncherPath(launcherStarted, xbox, false);
        boolean familyAuthorizationChecked = familyEnabled();
        if (familyAuthorizationChecked
                && !familyAllowsTarget(FamilyAccessPolicy.Target.PASSTHROUGH_INPUT, inputId)) {
            return logXboxLauncherPath(launcherStarted, xbox, false);
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (open(view, false, familyAuthorizationChecked)) return logXboxLauncherPath(launcherStarted, xbox, true);
        if (inputAction.isEmpty()) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return logXboxLauncherPath(launcherStarted, xbox, false);
        }
        boolean opened = open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"),
                false, familyAuthorizationChecked);
        return logXboxLauncherPath(launcherStarted, xbox, opened);
    }

    private boolean logXboxLauncherPath(long started, boolean xbox, boolean accepted) {
        if (xbox) {
            Log.i(FAMILY_XBOX_TIMING_TAG, "launcher_path_ms="
                    + (SystemClock.elapsedRealtime() - started) + " request_accepted=" + accepted);
        }
        return accepted;
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        boolean profilesEnabled = familyEnabled();
        if (profilesEnabled && activeFamilyProfile == null) {
            showFamilyProfileSelector();
            return;
        }
        String xboxInputId = "";
        if (profilesEnabled) {
            FamilyProfileStore.Snapshot snapshot = familyStore.snapshot(familyToday());
            if (familyStore.hasStorageError()) {
                Toast.makeText(this, R.string.family_storage_error, Toast.LENGTH_LONG).show();
                return;
            }
            xboxInputId = snapshot.xboxInputId;
        }
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                if (profilesEnabled && activeFamilyProfile == FamilyTimePolicy.Profile.CHILD_TV) continue;
                if (profilesEnabled && activeFamilyProfile == FamilyTimePolicy.Profile.XBOX
                        && (!info.getId().equals(xboxInputId)
                        || info.getType() != TvInputInfo.TYPE_HDMI)) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()
                && (!profilesEnabled || activeFamilyProfile == FamilyTimePolicy.Profile.ADULT)) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null && (!profilesEnabled || activeFamilyProfile != FamilyTimePolicy.Profile.XBOX)) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        dropIn(popup, panel, anchor, Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- module cards ----------------------------------------------------------------------------

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (familyEnabled() && activeFamilyProfile == null) return;
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (blockNonAdultAppManagement()) return;
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
