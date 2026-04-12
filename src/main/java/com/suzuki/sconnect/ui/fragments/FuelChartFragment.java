package com.suzuki.sconnect.ui.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.suzuki.sconnect.R;
import com.suzuki.sconnect.data.model.DailyFuelRecord;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.realm.Realm;
import io.realm.RealmResults;

/**
 * P5 — Fuel Chart Fragment
 * 
 * Reusable fragment for DAILY, WEEKLY, and MONTHLY charts.
 */
public class FuelChartFragment extends Fragment {

    public enum Period { DAILY, WEEKLY, MONTHLY }

    private Period period = Period.DAILY;
    private BarChart barChart;
    private TextView tvAvgMileage, tvTotalDistance, tvTotalFuel, tvPeriodLabel;
    private LinearLayout layoutEmpty;
    private Realm realm;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public static FuelChartFragment newInstance(Period period) {
        FuelChartFragment fragment = new FuelChartFragment();
        Bundle args = new Bundle();
        args.putSerializable("period", period);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            period = (Period) getArguments().getSerializable("period");
        }
        realm = Realm.getDefaultInstance();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_fuel_chart, container, false);
        
        barChart = view.findViewById(R.id.fuel_bar_chart);
        tvAvgMileage = view.findViewById(R.id.tv_avg_mileage);
        tvTotalDistance = view.findViewById(R.id.tv_total_distance);
        tvTotalFuel = view.findViewById(R.id.tv_total_fuel);
        tvPeriodLabel = view.findViewById(R.id.tv_period_label);
        layoutEmpty = view.findViewById(R.id.layout_empty_charts);

        setupChart();
        loadData();

        return view;
    }

    private void setupChart() {
        barChart.getDescription().setEnabled(false);
        barChart.setDrawGridBackground(false);
        barChart.setDrawBarShadow(false);
        barChart.setHighlightFullBarEnabled(false);
        barChart.setPinchZoom(false);
        barChart.setDoubleTapToZoomEnabled(false);
        barChart.setScaleEnabled(false);
        barChart.getLegend().setEnabled(false);

        XAxis xAxis = barChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(Color.parseColor("#8B949E"));
        xAxis.setGranularity(1f);

        YAxis leftAxis = barChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#30363D"));
        leftAxis.setTextColor(Color.parseColor("#8B949E"));
        leftAxis.setAxisMinimum(0f);

        barChart.getAxisRight().setEnabled(false);
    }

    private void loadData() {
        RealmResults<DailyFuelRecord> allRecords = realm.where(DailyFuelRecord.class).findAll();
        if (allRecords.isEmpty()) {
            showEmptyState();
            return;
        }

        List<ChartDataPoint> points = new ArrayList<>();
        float totalDist = 0, totalFuel = 0;

        switch (period) {
            case DAILY:
                points = getDailyPoints(allRecords);
                tvPeriodLabel.setText("AVERAGE FUEL ECONOMY (LAST 7 DAYS)");
                break;
            case WEEKLY:
                points = getWeeklyPoints(allRecords);
                tvPeriodLabel.setText("AVERAGE FUEL ECONOMY (LAST 4 WEEKS)");
                break;
            case MONTHLY:
                points = getMonthlyPoints(allRecords);
                tvPeriodLabel.setText("AVERAGE FUEL ECONOMY (LAST 6 MONTHS)");
                break;
        }

        if (points.isEmpty()) {
            showEmptyState();
            return;
        }

        layoutEmpty.setVisibility(View.GONE);
        barChart.setVisibility(View.VISIBLE);

        for (ChartDataPoint p : points) {
            totalDist += p.distance;
            totalFuel += p.fuel;
        }

        float avgMileage = totalFuel > 0 ? totalDist / totalFuel : 0;
        tvAvgMileage.setText(String.format(Locale.US, "%.1f km/L", avgMileage));
        tvTotalDistance.setText(String.format(Locale.US, "%.0f km", totalDist));
        tvTotalFuel.setText(String.format(Locale.US, "%.1f L", totalFuel));

        displayChart(points);
    }

    private List<ChartDataPoint> getDailyPoints(RealmResults<DailyFuelRecord> records) {
        List<ChartDataPoint> points = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        
        for (int i = 0; i < 7; i++) {
            String dateStr = dateFormat.format(cal.getTime());
            DailyFuelRecord record = records.where().equalTo("date", dateStr).findFirst();
            
            float dist = record != null ? record.getTotalDistanceKm() : 0;
            float fuel = record != null ? record.getTotalFuelLitres() : 0;
            float mileage = (fuel > 0) ? dist / fuel : 0;

            String label = new SimpleDateFormat("E", Locale.US).format(cal.getTime());
            points.add(new ChartDataPoint(label, mileage, dist, fuel));
            
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        Collections.reverse(points);
        return points;
    }

    private List<ChartDataPoint> getWeeklyPoints(RealmResults<DailyFuelRecord> records) {
        List<ChartDataPoint> points = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        
        for (int w = 0; w < 4; w++) {
            float weeklyDist = 0, weeklyFuel = 0;
            for (int d = 0; d < 7; d++) {
                String dateStr = dateFormat.format(cal.getTime());
                DailyFuelRecord record = records.where().equalTo("date", dateStr).findFirst();
                if (record != null) {
                    weeklyDist += record.getTotalDistanceKm();
                    weeklyFuel += record.getTotalFuelLitres();
                }
                cal.add(Calendar.DAY_OF_YEAR, -1);
            }
            float mileage = (weeklyFuel > 0) ? weeklyDist / weeklyFuel : 0;
            points.add(new ChartDataPoint("W" + (4 - w), mileage, weeklyDist, weeklyFuel));
        }
        Collections.reverse(points);
        return points;
    }

    private List<ChartDataPoint> getMonthlyPoints(RealmResults<DailyFuelRecord> records) {
        Map<String, ChartDataPoint> monthlyMap = new TreeMap<>(Collections.reverseOrder());
        Calendar cal = Calendar.getInstance();

        for (DailyFuelRecord record : records) {
            try {
                Date date = dateFormat.parse(record.getDate());
                String monthKey = new SimpleDateFormat("MMM", Locale.US).format(date);
                
                ChartDataPoint point = monthlyMap.get(monthKey);
                if (point == null) {
                    point = new ChartDataPoint(monthKey, 0, 0, 0);
                    monthlyMap.put(monthKey, point);
                }
                point.distance += record.getTotalDistanceKm();
                point.fuel += record.getTotalFuelLitres();
            } catch (ParseException ignored) {}
        }

        List<ChartDataPoint> points = new ArrayList<>(monthlyMap.values());
        for (ChartDataPoint p : points) {
            p.mileage = p.fuel > 0 ? p.distance / p.fuel : 0;
        }
        
        // Limit to last 6 months
        if (points.size() > 6) points = points.subList(0, 6);
        Collections.reverse(points);
        return points;
    }

    private void displayChart(List<ChartDataPoint> points) {
        List<BarEntry> entries = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        for (int i = 0; i < points.size(); i++) {
            entries.add(new BarEntry(i, points.get(i).mileage));
            labels.add(points.get(i).label);
        }

        BarDataSet dataSet = new BarDataSet(entries, "Mileage");
        dataSet.setColor(Color.parseColor("#0056B3"));
        dataSet.setDrawValues(true);
        dataSet.setValueTextColor(Color.WHITE);
        dataSet.setValueTextSize(10f);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.6f);

        barChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int index = (int) value;
                if (index >= 0 && index < labels.size()) return labels.get(index);
                return "";
            }
        });

        barChart.setData(barData);
        barChart.animateY(1000);
        barChart.invalidate();
    }

    private void showEmptyState() {
        layoutEmpty.setVisibility(View.VISIBLE);
        barChart.setVisibility(View.GONE);
        tvAvgMileage.setText("0.0 km/L");
        tvTotalDistance.setText("0 km");
        tvTotalFuel.setText("0.0 L");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (realm != null && !realm.isClosed()) realm.close();
    }

    private static class ChartDataPoint {
        String label;
        float mileage;
        float distance;
        float fuel;

        ChartDataPoint(String label, float mileage, float distance, float fuel) {
            this.label = label;
            this.mileage = mileage;
            this.distance = distance;
            this.fuel = fuel;
        }
    }
}
