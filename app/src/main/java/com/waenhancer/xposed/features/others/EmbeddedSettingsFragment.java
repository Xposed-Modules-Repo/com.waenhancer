package com.waenhancer.xposed.features.others;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;
import com.waenhancer.xposed.bridge.client.ProviderSharedPreferences;
import com.waenhancer.xposed.utils.ResId;

/**
 * The main fragment for WaEnhancer settings when running inside WhatsApp.
 */
public class EmbeddedSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        // Redirect preference storage to the module via ProviderSharedPreferences
        var localPrefs = requireContext().getSharedPreferences("wae_embedded_prefs", android.content.Context.MODE_PRIVATE);
        var providerPrefs = new ProviderSharedPreferences(requireContext(), localPrefs);
        
        getPreferenceManager().setSharedPreferencesName("wae_embedded_prefs");
        // We can't easily override the SharedPreferences object in PreferenceManager after it's created
        // without reflection, but we can set the name and then maybe hook the access if needed.
        
        // Better: ensure the PreferenceManager uses our custom SharedPreferences
        try {
            java.lang.reflect.Field field = androidx.preference.PreferenceManager.class.getDeclaredField("mSharedPreferences");
            field.setAccessible(true);
            field.set(getPreferenceManager(), providerPrefs);
        } catch (Exception e) {
            // Fallback to default behavior if reflection fails
        }

        setPreferencesFromResource(ResId.xml.fragment_general, rootKey);
    }
}
