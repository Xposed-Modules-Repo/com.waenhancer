package com.waenhancer.xposed.features.others;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.XposedBridge;

/**
 * Builds a WhatsApp-native settings screen by resolving all colors, drawables,
 * and dimensions from the host WhatsApp app's resources.
 */
public class SettingsViewBuilder {

    // ===== Resource Resolution Helpers =====

    /**
     * Resolve a color resource from the host (WhatsApp) application.
     * Falls back to the provided default if not found.
     */
    private static int getHostColor(Context context, String colorName, int fallback) {
        try {
            Resources res = context.getResources();
            String pkg = context.getPackageName();
            int id = res.getIdentifier(colorName, "color", pkg);
            if (id != 0) {
                return res.getColor(id, context.getTheme());
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    /**
     * Resolve a drawable resource from the host (WhatsApp) application.
     */
    private static Drawable getHostDrawable(Context context, String drawableName) {
        try {
            Resources res = context.getResources();
            String pkg = context.getPackageName();
            int id = res.getIdentifier(drawableName, "drawable", pkg);
            if (id != 0) {
                return res.getDrawable(id, context.getTheme());
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Resolve a dimension resource from the host (WhatsApp) application.
     */
    private static int getHostDimen(Context context, String dimenName, int fallbackDp) {
        try {
            Resources res = context.getResources();
            String pkg = context.getPackageName();
            int id = res.getIdentifier(dimenName, "dimen", pkg);
            if (id != 0) {
                return (int) res.getDimension(id);
            }
        } catch (Throwable ignored) {}
        return dp(fallbackDp);
    }

    /**
     * Resolve a color from Android framework theme attributes.
     */
    private static int resolveThemeAttr(Context context, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            try {
                return context.getResources().getColor(tv.resourceId, context.getTheme());
            } catch (Throwable ignored) {}
        }
        return fallback;
    }

    // ===== Build Methods =====

    /**
     * Build the complete full-screen view including toolbar and scrollable content.
     * All colors are resolved from the host WhatsApp app's resources.
     */
    public static View buildFullScreen(Activity activity, Dialog dialog) {
        boolean isDark = DesignUtils.isNightMode();

        // Resolve colors from WhatsApp's own resources
        int colorPrimary = getHostColor(activity, "colorPrimary",
                resolveThemeAttr(activity, android.R.attr.colorPrimary,
                        isDark ? 0xff1f2c34 : 0xff008069));

        int colorPrimaryDark = getHostColor(activity, "colorPrimaryDark",
                resolveThemeAttr(activity, android.R.attr.colorPrimaryDark,
                        isDark ? 0xff0b141a : 0xff00695c));

        int windowBg = getHostColor(activity, "windowBackground",
                resolveThemeAttr(activity, android.R.attr.windowBackground,
                        isDark ? 0xff0b141a : 0xffffffff));

        int textPrimary = getHostColor(activity, "primary_text",
                resolveThemeAttr(activity, android.R.attr.textColorPrimary,
                        isDark ? 0xffe9edef : 0xff111b21));

        int textSecondary = getHostColor(activity, "secondary_text",
                resolveThemeAttr(activity, android.R.attr.textColorSecondary,
                        isDark ? 0xff8696a0 : 0xff667781));

        int toolbarTextColor = getHostColor(activity, "toolbar_primary_text_color",
                0xffffffff);

        int dividerColor = getHostColor(activity, "divider",
                isDark ? 0xff233138 : 0xffe9edef);

        int actionBarHeight = getHostDimen(activity, "action_bar_size", 56);

        // Root layout
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(windowBg);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ===== Toolbar =====
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(colorPrimary);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, actionBarHeight));
        toolbar.setPadding(dp(4), 0, dp(16), 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            toolbar.setElevation(dp(4));
        }

        // Back button — try to load WhatsApp's own back arrow, fallback to system
        ImageView backBtn = new ImageView(activity);
        Drawable backArrow = getHostDrawable(activity, "abc_ic_ab_back_material");
        if (backArrow == null) {
            backArrow = getHostDrawable(activity, "ic_arrow_back_white");
        }
        if (backArrow == null) {
            backArrow = getHostDrawable(activity, "ic_ab_back_white");
        }
        if (backArrow == null) {
            // Ultimate fallback: Android system back arrow
            try {
                TypedValue tv = new TypedValue();
                activity.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, tv, true);
                backArrow = activity.getResources().getDrawable(tv.resourceId, activity.getTheme());
            } catch (Throwable ignored) {}
        }
        if (backArrow != null) {
            backArrow.setTint(toolbarTextColor);
            backBtn.setImageDrawable(backArrow);
        } else {
            // Text fallback if absolutely nothing works
            backBtn.setImageDrawable(new ColorDrawable(0x00000000));
        }
        backBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        backBtn.setLayoutParams(backParams);
        backBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        backBtn.setClickable(true);
        backBtn.setFocusable(true);

        // Ripple background
        TypedValue rippleValue = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleValue, true);
        try {
            backBtn.setBackgroundResource(rippleValue.resourceId);
        } catch (Throwable ignored) {}

        backBtn.setOnClickListener(v -> {
            if (dialog != null) dialog.dismiss();
        });

        // Title text
        TextView title = new TextView(activity);
        title.setText("WaEnhancer");
        title.setTextColor(toolbarTextColor);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.NORMAL);
        title.setPadding(dp(4), 0, 0, 0);

        toolbar.addView(backBtn);
        toolbar.addView(title);
        root.addView(toolbar);

        // ===== Content =====
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scrollView.setFillViewport(true);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(24));

        // Settings items — no emojis, plain text icons with WhatsApp-resolved colors
        addSettingsItem(container, "Privacy",
                "Online, blue ticks, typing, read receipts",
                getHostColor(activity, "whatsapp_green", 0xff25d366),
                "P", textPrimary, textSecondary);

        addSettingsItem(container, "Customization",
                "Colors, themes, tabs, toolbar",
                getHostColor(activity, "settings_purple", 0xff5f67ec),
                "C", textPrimary, textSecondary);

        addSettingsItem(container, "Home Screen",
                "Menu options, buttons, ghost mode",
                getHostColor(activity, "settings_blue", 0xff00a5cf),
                "H", textPrimary, textSecondary);

        addSettingsItem(container, "Media",
                "Photo quality, video, downloads",
                getHostColor(activity, "settings_orange", 0xfff47e30),
                "M", textPrimary, textSecondary);

        addSettingsItem(container, "Chat",
                "Anti-revoke, bubble styles, chat limits",
                getHostColor(activity, "settings_teal", 0xff00897b),
                "T", textPrimary, textSecondary);

        addSettingsItem(container, "Others",
                "Additional tweaks and features",
                getHostColor(activity, "settings_red", 0xffe53935),
                "O", textPrimary, textSecondary);

        // Divider
        View divider = new View(activity);
        divider.setBackgroundColor(dividerColor);
        LinearLayout.LayoutParams divLP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLP.setMargins(dp(72), dp(8), 0, dp(8));
        divider.setLayoutParams(divLP);
        container.addView(divider);

        // Footer
        TextView footer = new TextView(activity);
        footer.setText("WaEnhancer");
        footer.setTextSize(12);
        footer.setTextColor(textSecondary);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(16), dp(16), dp(16), dp(16));
        footer.setAlpha(0.6f);
        container.addView(footer);

        scrollView.addView(container);
        root.addView(scrollView);

        return root;
    }

    /**
     * Single settings row — WhatsApp preference style.
     * Uses a single-letter icon on a colored circle instead of emojis.
     */
    private static void addSettingsItem(LinearLayout container, String titleText, String summary,
                                        int iconBgColor, String iconLetter,
                                        int primaryColor, int secondaryColor) {
        Context context = container.getContext();

        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setPadding(dp(16), dp(14), dp(16), dp(14));
        item.setClickable(true);
        item.setFocusable(true);
        item.setBackground(DesignUtils.getSelectableItemBackground(context));

        // Circular icon with letter
        LinearLayout iconHolder = new LinearLayout(context);
        iconHolder.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(iconBgColor);
        iconHolder.setBackground(circle);
        LinearLayout.LayoutParams iconLP = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconLP.setMarginEnd(dp(16));
        iconHolder.setLayoutParams(iconLP);

        TextView iconText = new TextView(context);
        iconText.setText(iconLetter);
        iconText.setTextSize(18);
        iconText.setTextColor(0xffffffff);
        iconText.setTypeface(null, Typeface.BOLD);
        iconText.setGravity(Gravity.CENTER);
        iconHolder.addView(iconText);

        // Text column
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(context);
        titleView.setText(titleText);
        titleView.setTextSize(16);
        titleView.setTextColor(primaryColor);

        TextView summaryView = new TextView(context);
        summaryView.setText(summary);
        summaryView.setTextSize(13);
        summaryView.setTextColor(secondaryColor);
        summaryView.setPadding(0, dp(2), 0, 0);

        textCol.addView(titleView);
        textCol.addView(summaryView);

        item.addView(iconHolder);
        item.addView(textCol);
        container.addView(item);
    }

    // ===== Legacy compat =====

    public static View build(Activity activity) {
        return buildFullScreen(activity, null);
    }

    public static View buildNativeContent(Activity activity) {
        return buildFullScreen(activity, null);
    }

    // ===== Utility =====

    private static int dp(float value) {
        return Utils.dipToPixels(value);
    }
}
