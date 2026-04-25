package com.waenhancer.xposed.features.others;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Injects a "WaEnhancer Settings" tile into WhatsApp's settings RecyclerView.
 * Clicking it opens the full-screen settings dialog via MenuHome.showWaeSettingsDialog().
 */
public class SettingsInjector extends Feature {

    private final android.os.Handler hunterHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public SettingsInjector(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        var entryPoint = getSafeString("open_wae", "1");
        if (!"2".equals(entryPoint)) return;

        // Hook RecyclerView attachment to inject tile into WA's Settings list
        XposedHelpers.findAndHookMethod(android.view.View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                View view = (View) param.thisObject;
                if (view.getClass().getName().contains("RecyclerView")) {
                    hunterHandler.post(() -> checkAndInject((ViewGroup) view));
                }
            }
        });
    }

    private void checkAndInject(ViewGroup recyclerView) {
        Activity activity = Utils.getActivityFromView(recyclerView);
        if (activity == null) return;

        if (hasSettingsMarkers(recyclerView)) {
            if (!isAlreadyInjected(recyclerView)) {
                XposedBridge.log("[WaEnhancer] Injecting tile into " + activity.getClass().getSimpleName());
                injectIntoRecyclerView(recyclerView, activity);
            }
        }
    }

    private boolean isAlreadyInjected(ViewGroup recyclerView) {
        try {
            for (int i = 0; i < Math.min(3, recyclerView.getChildCount()); i++) {
                View child = recyclerView.getChildAt(i);
                if (child instanceof ViewGroup && findTextRecursive((ViewGroup) child, "WaEnhancer Settings"))
                    return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean findTextRecursive(ViewGroup group, String text) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().equals(text)) return true;
            if (child instanceof ViewGroup && findTextRecursive((ViewGroup) child, text)) return true;
        }
        return false;
    }

    private boolean hasSettingsMarkers(ViewGroup group) {
        int[] count = {0};
        String[] markers = {"Account", "Privacy", "Storage", "Help", "Settings"};
        return findMarkersRecursive(group, markers, count);
    }

    private boolean findMarkersRecursive(ViewGroup group, String[] markers, int[] count) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString();
                for (String marker : markers) {
                    if (text.equals(marker)) {
                        count[0]++;
                        if (count[0] >= 2) return true;
                    }
                }
            } else if (child instanceof ViewGroup) {
                if (findMarkersRecursive((ViewGroup) child, markers, count)) return true;
            }
        }
        return false;
    }

    private boolean injectIntoRecyclerView(ViewGroup recyclerView, Activity activity) {
        try {
            Object adapter = XposedHelpers.callMethod(recyclerView, "getAdapter");
            if (adapter == null) return false;

            java.lang.reflect.Method addHeaderMethod = null;
            try {
                addHeaderMethod = Unobfuscator.loadViewAddSearchBarMethod(classLoader);
            } catch (Exception ignored) {}

            if (addHeaderMethod != null && addHeaderMethod.getDeclaringClass().isAssignableFrom(adapter.getClass())) {
                View customRow = createCustomRow(activity);
                XposedHelpers.callMethod(adapter, addHeaderMethod.getName(), customRow);
                return true;
            }
        } catch (Exception e) {
            XposedBridge.log("[WaEnhancer] Tile injection error: " + e.getMessage());
        }
        return false;
    }

    private View createCustomRow(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(Utils.dipToPixels(16), Utils.dipToPixels(12),
                Utils.dipToPixels(16), Utils.dipToPixels(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackground(DesignUtils.getSelectableItemBackground(activity));

        android.widget.ImageView icon = new android.widget.ImageView(activity);
        var iconDraw = DesignUtils.getDrawableByName("ic_settings");
        iconDraw.setTint(0xff8696a0);
        icon.setImageDrawable(iconDraw);
        var iconParams = new LinearLayout.LayoutParams(Utils.dipToPixels(24), Utils.dipToPixels(24));
        iconParams.rightMargin = Utils.dipToPixels(32);
        icon.setLayoutParams(iconParams);

        TextView textView = new TextView(activity);
        textView.setText("WaEnhancer Settings");
        textView.setTextSize(16);
        textView.setTextColor(DesignUtils.getPrimaryTextColor());

        row.addView(icon);
        row.addView(textView);
        row.setOnClickListener(v -> MenuHome.showWaeSettingsDialog(activity));

        return row;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Settings Injector";
    }
}
