package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;

/** Read-only time display owned by the temporary family-session foreground service. */
final class FamilyTimeOverlay {
    private final Context context;
    private final WindowManager windowManager;
    private LinearLayout card;
    private TextView profileText;
    private TextView usedText;
    private TextView remainingText;
    private TextView noticeText;
    private ProgressTrack progressTrack;
    private WindowManager.LayoutParams layoutParams;
    private FamilyProfileStore.TimeBarCorner attachedCorner;
    private boolean attached;

    FamilyTimeOverlay(Context context) {
        this.context = context.getApplicationContext();
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    static boolean hasPermission(Context context) {
        try {
            return Settings.canDrawOverlays(context);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    boolean update(FamilyProfileStore.Snapshot snapshot, LocalDate day, boolean foregroundApp,
                   boolean screenOn, String notice) {
        boolean permission = hasPermission(context);
        boolean enabled = snapshot.timeBarVisible;
        FamilyProfileStore.Profile profile = snapshot.sessionProfile;
        if (!FamilyTimeBarPolicy.shouldDisplay(enabled, permission, screenOn,
                foregroundApp && snapshot.sessionPending, profile) || windowManager == null || day == null) {
            hide();
            return false;
        }

        if (!attached) {
            createCard(snapshot.timeBarCorner);
            try {
                windowManager.addView(card, layoutParams);
                attached = true;
            } catch (RuntimeException deniedOrBlockedBySystem) {
                hide();
                return false;
            }
        } else if (attachedCorner != snapshot.timeBarCorner) {
            attachedCorner = snapshot.timeBarCorner;
            layoutParams.gravity = gravity(attachedCorner);
            try {
                windowManager.updateViewLayout(card, layoutParams);
            } catch (RuntimeException viewNoLongerAllowed) {
                hide();
                return false;
            }
        }

        boolean xbox = profile == FamilyProfileStore.Profile.XBOX;
        long usedMs = xbox ? snapshot.xboxUsedMs : snapshot.childUsedMs;
        long remainingMs = snapshot.remainingMs(profile, day);
        long extraMs = xbox ? snapshot.xboxExtensionMs : snapshot.childExtensionMs;
        FamilyTimePolicy.Profile policyProfile = xbox
                ? FamilyTimePolicy.Profile.XBOX : FamilyTimePolicy.Profile.CHILD_TV;
        long standardMs = FamilyTimePolicy.dailyBudgetMs(policyProfile, day.getDayOfWeek());
        long totalMs = standardMs > Long.MAX_VALUE - extraMs ? Long.MAX_VALUE : standardMs + extraMs;

        profileText.setText(context.getString(xbox ? R.string.family_profile_xbox : R.string.family_profile_child));
        FamilyTimeBarPolicy.Colour colour = FamilyTimeBarPolicy.colourForRemaining(remainingMs);
        int tint = FamilyTimeBarPolicy.argbForRemaining(remainingMs);
        usedText.setText(context.getString(R.string.family_time_bar_used,
                FamilyTimeBarPolicy.displayMinutes(usedMs), FamilyTimeBarPolicy.displayMinutes(totalMs)));
        remainingText.setText(context.getString(R.string.family_time_bar_remaining,
                FamilyTimeBarPolicy.displayMinutes(remainingMs)));
        FamilyProfileStore.TimeBarContent content = snapshot.timeBarContent;
        usedText.setVisibility(content == FamilyProfileStore.TimeBarContent.REMAINING ? View.GONE : View.VISIBLE);
        remainingText.setVisibility(content == FamilyProfileStore.TimeBarContent.USED ? View.GONE : View.VISIBLE);
        progressTrack.setProgress(FamilyTimeBarPolicy.progressPercent(usedMs, totalMs), tint);
        if (notice == null || notice.isEmpty()) {
            noticeText.setVisibility(View.GONE);
        } else {
            noticeText.setText(notice);
            noticeText.setTextColor(tintForNotice(colour));
            noticeText.setVisibility(View.VISIBLE);
        }
        StringBuilder description = new StringBuilder(profileText.getText());
        if (usedText.getVisibility() == View.VISIBLE) description.append(", ").append(usedText.getText());
        if (remainingText.getVisibility() == View.VISIBLE) description.append(", ").append(remainingText.getText());
        if (notice != null && !notice.isEmpty()) description.append(", ").append(notice);
        card.setContentDescription(description);
        return attached;
    }

    void hide() {
        if (attached && windowManager != null && card != null) {
            try {
                windowManager.removeViewImmediate(card);
            } catch (RuntimeException alreadyRemoved) {
                // Settings can revoke overlay access while a session is active.
            }
        }
        attached = false;
        card = null;
        profileText = null;
        usedText = null;
        remainingText = null;
        noticeText = null;
        progressTrack = null;
        layoutParams = null;
        attachedCorner = null;
    }

    private void createCard(FamilyProfileStore.TimeBarCorner corner) {
        float density = context.getResources().getDisplayMetrics().density;
        int pad = dp(density, 18);
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, dp(density, 13), pad, pad);
        card.setBackground(roundRect(0xEB18212D, dp(density, 15)));
        card.setFocusable(false);
        card.setClickable(false);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        profileText = label(14, 0xFFC4CFDC, false);
        profileText.setText(context.getString(R.string.family_profile_child));
        header.addView(profileText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = label(12, 0xFFA9B7C8, true);
        title.setText(R.string.family_time_bar_title);
        header.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout values = new LinearLayout(context);
        values.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams valuesLp = new LinearLayout.LayoutParams(-1, -2);
        valuesLp.topMargin = dp(density, 7);
        usedText = label(21, 0xFFFFFFFF, true);
        values.addView(usedText, new LinearLayout.LayoutParams(0, -2, 1f));
        remainingText = label(16, 0xFFE6EDF5, false);
        values.addView(remainingText, new LinearLayout.LayoutParams(-2, -2));
        card.addView(values, valuesLp);

        progressTrack = new ProgressTrack(context);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(density, 9));
        progressLp.topMargin = dp(density, 11);
        card.addView(progressTrack, progressLp);

        noticeText = label(14, FamilyTimeBarPolicy.YELLOW_ARGB, true);
        LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(-1, -2);
        noticeLp.topMargin = dp(density, 8);
        card.addView(noticeText, noticeLp);
        noticeText.setVisibility(View.GONE);

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(density, 420), Math.max(dp(density, 280), screenWidth - dp(density, 56)));
        layoutParams = createLayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, corner);
        layoutParams.x = dp(density, 30);
        layoutParams.y = dp(density, 30);
        attachedCorner = corner;
    }

    static WindowManager.LayoutParams createLayoutParams(int width, int height,
                                                          FamilyProfileStore.TimeBarCorner corner) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = gravity(corner == null ? FamilyProfileStore.TimeBarCorner.LEFT : corner);
        // One translucent window below Android's opacity limit preserves pass-through on newer Android.
        params.alpha = 0.75f;
        return params;
    }

    private TextView label(float sizeSp, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setTypeface(android.graphics.Typeface.create("sans-serif", bold
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        text.setSingleLine(true);
        text.setFocusable(false);
        text.setClickable(false);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return text;
    }

    private static int gravity(FamilyProfileStore.TimeBarCorner corner) {
        // Resolve START/END against LTR to make the setting mean the physical screen corner in every locale.
        int horizontal = corner == FamilyProfileStore.TimeBarCorner.RIGHT ? Gravity.END : Gravity.START;
        return Gravity.BOTTOM | Gravity.getAbsoluteGravity(horizontal, View.LAYOUT_DIRECTION_LTR);
    }

    private static int dp(float density, int value) {
        return Math.round(density * value);
    }

    private static GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(1, 0x3FFFFFFF);
        return drawable;
    }

    private int tintForNotice(FamilyTimeBarPolicy.Colour colour) {
        return colour == FamilyTimeBarPolicy.Colour.RED
                ? FamilyTimeBarPolicy.RED_ARGB : FamilyTimeBarPolicy.YELLOW_ARGB;
    }

    private static final class ProgressTrack extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private int progress;
        private int tint = FamilyTimeBarPolicy.GREEN_ARGB;
        private float radius;

        ProgressTrack(Context context) {
            super(context);
            radius = 5f * context.getResources().getDisplayMetrics().density;
            setFocusable(false);
            setClickable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setProgress(int percent, int color) {
            progress = Math.max(0, Math.min(100, percent));
            tint = color;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float height = getHeight();
            float width = getWidth();
            bounds.set(0f, 0f, width, height);
            paint.setColor(0x665B6877);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            if (progress > 0) {
                bounds.right = width * progress / 100f;
                paint.setColor(tint);
                canvas.drawRoundRect(bounds, radius, radius, paint);
            }
        }
    }
}
