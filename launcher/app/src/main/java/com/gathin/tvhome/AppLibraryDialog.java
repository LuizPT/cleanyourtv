package com.gathin.tvhome;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compact, D-pad navigable app library and dock ordering dialog. */
final class AppLibraryDialog {
    private static final class App {
        final String pkg;
        final String label;
        final ComponentName component;
        final Drawable icon;

        App(String pkg, String label, ComponentName component, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }

    private final MainActivity host;
    private final boolean orderOnly;
    private final Set<String> hidden;
    private final Set<String> protectedApps;
    private final List<App> apps;
    private final List<String> order;
    private final Dialog dialog;
    private final LinearLayout rows;

    static void show(MainActivity host, boolean orderOnly, List<String> order,
                     Set<String> hidden, Set<String> protectedApps) {
        new AppLibraryDialog(host, orderOnly, order, hidden, protectedApps).show();
    }

    private AppLibraryDialog(MainActivity host, boolean orderOnly, List<String> order,
                             Set<String> hidden, Set<String> protectedApps) {
        this.host = host;
        this.orderOnly = orderOnly;
        this.hidden = new HashSet<>(hidden);
        this.protectedApps = new HashSet<>(protectedApps);
        this.order = new ArrayList<>(order);
        this.apps = discover(host);
        dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = dp(20);
        panel.setPadding(pad, pad, pad, dp(12));

        TextView title = new TextView(host);
        title.setText(orderOnly ? R.string.organize_apps : R.string.applications);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setFontFeatureSettings("kern");
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(host);
        hint.setText(orderOnly ? R.string.order_help : R.string.library_help);
        hint.setTextColor(0xFFD8DDE5);
        hint.setTextSize(14);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(4);
        panel.addView(hint, hintLp);

        ScrollView scroll = new ScrollView(host);
        rows = new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollLp.topMargin = dp(12);
        scrollLp.bottomMargin = dp(8);
        panel.addView(scroll, scrollLp);

        LinearLayout footer = new LinearLayout(host);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.addView(button(host.getString(orderOnly ? R.string.applications : R.string.organize_apps), () -> switchMode(!orderOnly)), buttonLp());
        footer.addView(button(host.getString(R.string.close), dialog::dismiss), buttonLp());
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> dialog.dismiss());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.65f);
        }
        render();
    }

    private void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            int width = (int) (host.getResources().getDisplayMetrics().widthPixels * 0.84f);
            int height = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.82f);
            window.setLayout(width, height);
        }
        rows.post(() -> {
            View first = rows.getChildAt(0);
            if (first != null) first.requestFocus();
        });
    }

    private List<App> discover(MainActivity activity) {
        PackageManager pm = activity.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
        Map<String, App> unique = new HashMap<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName()) || unique.containsKey(pkg)) continue;
            String label = Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
            unique.put(pkg, new App(pkg, label,
                    new ComponentName(pkg, ri.activityInfo.name), ri.loadIcon(pm)));
        }
        List<App> result = new ArrayList<>(unique.values());
        Collections.sort(result, Comparator.comparing(a -> a.label.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    private void render() {
        rows.removeAllViews();
        List<App> listed = new ArrayList<>();
        if (orderOnly) {
            Map<String, App> byPackage = new HashMap<>();
            for (App app : apps) byPackage.put(app.pkg, app);
            for (String pkg : order) {
                App app = byPackage.get(pkg);
                if (app != null && !hidden.contains(pkg)) listed.add(app);
            }
        } else listed.addAll(apps);

        if (listed.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText(orderOnly ? R.string.no_pinned_apps : R.string.no_apps_found);
            empty.setTextColor(0xFFE1E5EB);
            empty.setTextSize(16);
            empty.setPadding(dp(8), dp(20), dp(8), dp(20));
            empty.setFocusable(true);
            rows.addView(empty);
            return;
        }

        for (App app : listed) {
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setBackgroundResource(R.drawable.focus_row_bg);
            row.setFocusable(true);
            row.setClickable(true);
            row.setContentDescription(host.getString(orderOnly ? R.string.move_app_title : R.string.open_app, app.label));

            ImageView icon = new ImageView(host);
            icon.setImageDrawable(app.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(38), dp(38));
            iconLp.rightMargin = dp(12);
            row.addView(icon, iconLp);

            LinearLayout textBlock = new LinearLayout(host);
            textBlock.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(host);
            name.setText(app.label);
            name.setTextColor(Color.WHITE);
            name.setTextSize(17);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textBlock.addView(name, new LinearLayout.LayoutParams(-1, -2));
            TextView state = new TextView(host);
            state.setText(statusFor(app));
            state.setTextColor(0xFFD8DDE5);
            state.setTextSize(12);
            LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-1, -2);
            stateLp.topMargin = dp(2);
            textBlock.addView(state, stateLp);
            row.addView(textBlock, new LinearLayout.LayoutParams(0, -2, 1f));

            row.setOnClickListener(v -> {
                if (orderOnly) host.showMoveOptionsFromLibrary(app.pkg);
                else host.openFromLibrary(app.pkg, app.component, app.label);
            });
            row.setOnLongClickListener(v -> {
                host.showLibraryContextMenu(app.pkg, app.label);
                return true;
            });
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.bottomMargin = dp(5);
            rows.addView(row, rowLp);
        }
    }

    private String statusFor(App app) {
        List<AppActionPolicy.Status> states = AppActionPolicy.statuses(hidden.contains(app.pkg), protectedApps.contains(app.pkg));
        List<String> labels = new ArrayList<>();
        for (AppActionPolicy.Status state : states) {
            if (state == AppActionPolicy.Status.HIDDEN) labels.add(host.getString(R.string.app_state_hidden));
            else if (state == AppActionPolicy.Status.ON_HOME) labels.add(host.getString(R.string.app_state_on_home));
            else labels.add(host.getString(R.string.app_state_protected));
        }
        return android.text.TextUtils.join("  ·  ", labels);
    }

    private void switchMode(boolean order) {
        List<String> saved = new ArrayList<>(this.order);
        dialog.dismiss();
        AppLibraryDialog.show(host, order, saved, hidden, protectedApps);
    }

    private TextView button(String text, Runnable action) {
        TextView button = new TextView(host);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackgroundResource(R.drawable.focus_button_bg);
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        return lp;
    }

    private int dp(float value) {
        return Math.round(value * host.getResources().getDisplayMetrics().density);
    }
}
