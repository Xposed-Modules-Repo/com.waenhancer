package com.waenhancer.xposed.features.others;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;

import de.robv.android.xposed.XSharedPreferences;

public class Spy extends Feature {

    public Spy(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {

    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Spy Tool";
    }
}
