package com.waenhancer.xposed.features.others;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.Utils;

/**
 * An activity that runs inside the WhatsApp process to host WaEnhancer settings.
 * v11: View-Based and Dynamically Themed.
 */
public class EmbeddedSettingsActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        int bgColor = DesignUtils.getThemeBackgroundColor(this);
        int headerColor = DesignUtils.getThemeHeaderColor(this);
        int textColorPrimary = DesignUtils.getThemeTextColorPrimary(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        
        // Custom Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(Utils.dipToPixels(16), Utils.dipToPixels(16), 
                         Utils.dipToPixels(16), Utils.dipToPixels(16));
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(headerColor);
        
        TextView backButton = new TextView(this);
        backButton.setText("←");
        backButton.setTextSize(24);
        backButton.setTextColor(textColorPrimary);
        backButton.setPadding(0, 0, Utils.dipToPixels(16), 0);
        backButton.setOnClickListener(v -> finish());
        
        TextView titleView = new TextView(this);
        titleView.setText("WaEnhancer Settings");
        titleView.setTextSize(20);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(textColorPrimary);
        
        header.addView(backButton);
        header.addView(titleView);
        root.addView(header);

        // Settings Content
        View settingsView = SettingsViewBuilder.build(this);
        root.addView(settingsView);

        setContentView(root);
    }
}
