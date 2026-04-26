package com.waenhancer.preference;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.Set;

public class SafeSharedPreferences implements SharedPreferences {

    private final SharedPreferences delegate;

    public SafeSharedPreferences(SharedPreferences delegate) {
        this.delegate = delegate;
    }

    @Override
    public Map<String, ?> getAll() {
        return delegate.getAll();
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        try {
            return delegate.getString(key, defValue);
        } catch (Exception e) {
            try {
                Object val = delegate.getAll().get(key);
                if (val instanceof Boolean) {
                    return ((Boolean) val) ? "1" : "0";
                }
                return val != null ? String.valueOf(val) : defValue;
            } catch (Exception ignored) {}
            return defValue;
        }
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        try {
            return delegate.getStringSet(key, defValues);
        } catch (Exception e) {
            return defValues;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        try {
            return delegate.getInt(key, defValue);
        } catch (Exception e) {
            try {
                Object val = delegate.getAll().get(key);
                if (val instanceof String) return Integer.parseInt((String) val);
                if (val instanceof Float) return ((Float) val).intValue();
                if (val instanceof Long) return ((Long) val).intValue();
            } catch (Exception ignored) {}
            return defValue;
        }
    }

    @Override
    public long getLong(String key, long defValue) {
        try {
            return delegate.getLong(key, defValue);
        } catch (Exception e) {
            try {
                Object val = delegate.getAll().get(key);
                if (val instanceof String) return Long.parseLong((String) val);
                if (val instanceof Integer) return ((Integer) val).longValue();
                if (val instanceof Float) return ((Float) val).longValue();
            } catch (Exception ignored) {}
            return defValue;
        }
    }

    @Override
    public float getFloat(String key, float defValue) {
        try {
            return delegate.getFloat(key, defValue);
        } catch (Exception e) {
            try {
                Object val = delegate.getAll().get(key);
                if (val instanceof Integer) return ((Integer) val).floatValue();
                if (val instanceof String) return Float.parseFloat((String) val);
                if (val instanceof Long) return ((Long) val).floatValue();
                if (val instanceof Double) return ((Double) val).floatValue();
            } catch (Exception ignored) {}
            return defValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        try {
            return delegate.getBoolean(key, defValue);
        } catch (Exception e) {
            try {
                Object val = delegate.getAll().get(key);
                if (val instanceof String) {
                    String s = (String) val;
                    return "1".equals(s) || "true".equalsIgnoreCase(s);
                }
                if (val instanceof Integer) return ((Integer) val) != 0;
            } catch (Exception ignored) {}
            return defValue;
        }
    }

    @Override
    public boolean contains(String key) {
        return delegate.contains(key);
    }

    @Override
    public Editor edit() {
        return delegate.edit();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        delegate.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        delegate.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
