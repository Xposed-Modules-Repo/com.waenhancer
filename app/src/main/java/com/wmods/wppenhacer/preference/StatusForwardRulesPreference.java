package com.wmods.wppenhacer.preference;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import com.wmods.wppenhacer.activities.StatusForwardRulesActivity;

import org.json.JSONArray;

public class StatusForwardRulesPreference extends Preference
        implements Preference.OnPreferenceClickListener {

    public StatusForwardRulesPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnPreferenceClickListener(this);
        updateSummary(context);
    }

    public StatusForwardRulesPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOnPreferenceClickListener(this);
        updateSummary(context);
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        Intent intent = new Intent(getContext(), StatusForwardRulesActivity.class);
        getContext().startActivity(intent);
        return true;
    }

    private void updateSummary(Context context) {
        String json = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(StatusForwardRulesActivity.PREF_KEY, "[]");
        int count = 0;
        try {
            count = new JSONArray(json).length();
        } catch (Exception ignored) {
        }
        setSummary(count == 0
                ? context.getString(com.wmods.wppenhacer.R.string.rules_summary_empty)
                : context.getString(com.wmods.wppenhacer.R.string.rules_summary_count, count));
    }

    /**
     * Call from the Activity/Fragment after returning from the rules screen to
     * refresh summary.
     */
    public void refresh() {
        updateSummary(getContext());
        notifyChanged();
    }
}
