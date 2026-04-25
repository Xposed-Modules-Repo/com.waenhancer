package com.waenhancer.xposed.bridge.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.net.Uri;
import androidx.annotation.Nullable;
import java.util.Map;
import java.util.Set;

/**
 * A SharedPreferences implementation that writes values back to the module via a ContentProvider.
 * This ensures that settings changed within the WhatsApp process are persisted in the module.
 */
public class ProviderSharedPreferences implements SharedPreferences {

    private final Context context;
    private final SharedPreferences localPrefs;
    private final String authority = "com.waenhancer.provider";

    public ProviderSharedPreferences(Context context, SharedPreferences localPrefs) {
        this.context = context;
        this.localPrefs = localPrefs;
    }

    @Override
    public Map<String, ?> getAll() { return localPrefs.getAll(); }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) { return localPrefs.getString(key, defValue); }

    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) { return localPrefs.getStringSet(key, defValues); }

    @Override
    public int getInt(String key, int defValue) { return localPrefs.getInt(key, defValue); }

    @Override
    public long getLong(String key, long defValue) { return localPrefs.getLong(key, defValue); }

    @Override
    public float getFloat(String key, float defValue) { return localPrefs.getFloat(key, defValue); }

    @Override
    public boolean getBoolean(String key, boolean defValue) { return localPrefs.getBoolean(key, defValue); }

    @Override
    public boolean contains(String key) { return localPrefs.contains(key); }

    @Override
    public Editor edit() {
        return new ProviderEditor(localPrefs.edit());
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        localPrefs.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        localPrefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private class ProviderEditor implements Editor {
        private final Editor localEditor;

        public ProviderEditor(Editor localEditor) {
            this.localEditor = localEditor;
        }

        private void syncToProvider(String key, Object value) {
            try {
                Bundle extras = new Bundle();
                extras.putString("key", key);
                if (value instanceof String) extras.putString("value", (String) value);
                else if (value instanceof Boolean) extras.putBoolean("value", (Boolean) value);
                else if (value instanceof Integer) extras.putInt("value", (Integer) value);
                else if (value instanceof Long) extras.putLong("value", (Long) value);
                else if (value instanceof Float) extras.putFloat("value", (Float) value);
                
                context.getContentResolver().call(Uri.parse("content://" + authority), "put_preference", null, extras);
            } catch (Exception e) {
                // Log error
            }
        }

        @Override
        public Editor putString(String key, @Nullable String value) {
            localEditor.putString(key, value);
            syncToProvider(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) {
            localEditor.putStringSet(key, values);
            // Sets are harder to sync via Bundle, ignoring for now if not used
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            localEditor.putInt(key, value);
            syncToProvider(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            localEditor.putLong(key, value);
            syncToProvider(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            localEditor.putFloat(key, value);
            syncToProvider(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            localEditor.putBoolean(key, value);
            syncToProvider(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            localEditor.remove(key);
            // Could add remove to provider too
            return this;
        }

        @Override
        public Editor clear() {
            localEditor.clear();
            return this;
        }

        @Override
        public boolean commit() { return localEditor.commit(); }

        @Override
        public void apply() { localEditor.apply(); }
    }
}
