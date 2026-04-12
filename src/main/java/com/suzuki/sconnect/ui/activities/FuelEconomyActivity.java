package com.suzuki.sconnect.ui.activities;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ui.fragments.FuelChartFragment;

/**
 * P5 — Fuel Economy Activity
 * 
 * Main host for visual fuel consumption analytics.
 * Uses a ViewPager2 to switch between Daily, Weekly, and Monthly charts.
 */
public class FuelEconomyActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private TabLayout tabLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fuel_economy);

        Toolbar toolbar = findViewById(R.id.toolbar_fuel);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Fuel Economy");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        viewPager = findViewById(R.id.pager_fuel);
        tabLayout = findViewById(R.id.tabs_fuel);

        FuelPagerAdapter adapter = new FuelPagerAdapter(this);
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText("DAILY"); break;
                case 1: tab.setText("WEEKLY"); break;
                case 2: tab.setText("MONTHLY"); break;
            }
        }).attach();
    }

    /**
     * Adapter for FuelChartFragments.
     */
    private static class FuelPagerAdapter extends FragmentStateAdapter {

        public FuelPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0: return FuelChartFragment.newInstance(FuelChartFragment.Period.DAILY);
                case 1: return FuelChartFragment.newInstance(FuelChartFragment.Period.WEEKLY);
                case 2: return FuelChartFragment.newInstance(FuelChartFragment.Period.MONTHLY);
                default: return FuelChartFragment.newInstance(FuelChartFragment.Period.DAILY);
            }
        }

        @Override
        public int getItemCount() {
            return 3;
        }
    }
}
