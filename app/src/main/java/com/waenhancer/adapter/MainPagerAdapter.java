package com.waenhancer.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.waenhancer.ui.fragments.CustomizationFragment;
import com.waenhancer.ui.fragments.GeneralFragment;
import com.waenhancer.ui.fragments.HomeFragment;
import com.waenhancer.ui.fragments.MediaFragment;
import com.waenhancer.ui.fragments.PrivacyFragment;

public class MainPagerAdapter extends FragmentStateAdapter {

    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return switch (position) {
            case 0 -> new GeneralFragment();
            case 1 -> new PrivacyFragment();
            case 3 -> new MediaFragment();
            case 4 -> new CustomizationFragment();
            default -> new HomeFragment();
        };
    }

    @Override
    public int getItemCount() {
        return 5;
    }
}