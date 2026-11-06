package com.smartpetfeeder.util;

import android.content.Context;
import android.graphics.Color;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.smartpetfeeder.model.DashboardData;

import java.util.ArrayList;
import java.util.List;

public class ChartHelper {

    private static final int COLOR_DISPENSED   = Color.parseColor("#8D6E63");
    private static final int COLOR_CONSUMED    = Color.parseColor("#FF8F00");
    private static final int COLOR_CONSUMED_FILL = Color.parseColor("#33FF8F00");
    private static final int COLOR_SCHEDULED = Color.parseColor("#BCAAA4");
    private static final int COLOR_COMPLETED = Color.parseColor("#4CAF50");
    private static final int COLOR_GRID      = Color.parseColor("#E8DDD9");
    private static final int COLOR_TEXT      = Color.parseColor("#8D6E63");
    private static final int COLOR_NO_DATA   = Color.parseColor("#6D4C41");

    private final Context context;

    public ChartHelper(Context context) {
        this.context = context;
    }

    public void setupLineChart(LineChart lineChart) {
        lineChart.getDescription().setEnabled(false);
        lineChart.setDrawGridBackground(false);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(false);
        lineChart.setPinchZoom(false);
        lineChart.setDoubleTapToZoomEnabled(false);
        lineChart.setExtraOffsets(8f, 12f, 8f, 12f);
        lineChart.setNoDataText("No data available from feeder");
        lineChart.setNoDataTextColor(COLOR_NO_DATA);

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(true);
        xAxis.setAxisLineColor(COLOR_GRID);
        xAxis.setTextColor(COLOR_TEXT);
        xAxis.setTextSize(10f);
        xAxis.setGranularity(1f);
        xAxis.setLabelCount(5);

        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(COLOR_GRID);
        leftAxis.setGridLineWidth(0.8f);
        leftAxis.setDrawAxisLine(false);
        leftAxis.setTextColor(COLOR_TEXT);
        leftAxis.setTextSize(10f);
        leftAxis.setAxisMinimum(0f);

        lineChart.getAxisRight().setEnabled(false);

        Legend legend = lineChart.getLegend();
        legend.setEnabled(true);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        legend.setTextColor(COLOR_TEXT);
        legend.setTextSize(10f);
        legend.setForm(Legend.LegendForm.CIRCLE);
        legend.setFormSize(8f);
        legend.setXEntrySpace(16f);

        lineChart.animateX(800, Easing.EaseInOutQuart);
    }

    public void setupBarChart(BarChart barChart) {
        barChart.getDescription().setEnabled(false);
        barChart.setDrawGridBackground(false);
        barChart.setTouchEnabled(false);
        barChart.setDragEnabled(false);
        barChart.setScaleEnabled(false);
        barChart.setPinchZoom(false);
        barChart.setDoubleTapToZoomEnabled(false);
        barChart.setExtraOffsets(8f, 12f, 8f, 12f);
        barChart.setNoDataText("No data available from feeder");
        barChart.setNoDataTextColor(COLOR_NO_DATA);

        XAxis xAxis = barChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(true);
        xAxis.setAxisLineColor(COLOR_GRID);
        xAxis.setTextColor(COLOR_TEXT);
        xAxis.setTextSize(9f);
        xAxis.setGranularity(1f);
        xAxis.setCenterAxisLabels(true);

        YAxis leftAxis = barChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(COLOR_GRID);
        leftAxis.setGridLineWidth(0.8f);
        leftAxis.setDrawAxisLine(false);
        leftAxis.setTextColor(COLOR_TEXT);
        leftAxis.setTextSize(10f);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setGranularity(1f);

        barChart.getAxisRight().setEnabled(false);

        Legend legend = barChart.getLegend();
        legend.setEnabled(true);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        legend.setTextColor(COLOR_TEXT);
        legend.setTextSize(10f);
        legend.setForm(Legend.LegendForm.SQUARE);
        legend.setFormSize(8f);
        legend.setXEntrySpace(16f);

        barChart.animateY(800, Easing.EaseInOutQuart);
    }

    public void populateLineChart(LineChart lineChart,
                                  List<DashboardData.FoodTrendData> data) {
        if (data == null || data.isEmpty()) {
            lineChart.clear();
            lineChart.invalidate();
            return;
        }

        List<Entry> dispensedEntries = new ArrayList<>();
        List<Entry> consumedEntries  = new ArrayList<>();
        List<String> labels          = new ArrayList<>();

        for (int i = 0; i < data.size(); i++) {
            DashboardData.FoodTrendData item = data.get(i);
            dispensedEntries.add(new Entry(i, (float) item.getDispensedWeight()));
            consumedEntries.add(new Entry(i, (float) item.getConsumedWeight()));
            labels.add(item.getLabel());
        }

        LineDataSet dispensedSet = new LineDataSet(dispensedEntries, "Dispensed (g)");
        dispensedSet.setColor(COLOR_DISPENSED);
        dispensedSet.setLineWidth(2f);
        dispensedSet.setDrawCircles(true);
        dispensedSet.setCircleColor(COLOR_DISPENSED);
        dispensedSet.setCircleRadius(4f);
        dispensedSet.setCircleHoleRadius(2f);
        dispensedSet.setDrawCircleHole(true);
        dispensedSet.setCircleHoleColor(Color.WHITE);
        dispensedSet.setDrawValues(false);
        dispensedSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dispensedSet.setDrawFilled(false);

        LineDataSet consumedSet = new LineDataSet(consumedEntries, "Consumed (g)");
        consumedSet.setColor(COLOR_CONSUMED);
        consumedSet.setLineWidth(2.5f);
        consumedSet.setDrawCircles(true);
        consumedSet.setCircleColor(COLOR_CONSUMED);
        consumedSet.setCircleRadius(4f);
        consumedSet.setCircleHoleRadius(2f);
        consumedSet.setDrawCircleHole(true);
        consumedSet.setCircleHoleColor(Color.WHITE);
        consumedSet.setDrawValues(false);
        consumedSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        consumedSet.setDrawFilled(true);
        consumedSet.setFillColor(COLOR_CONSUMED_FILL);
        consumedSet.setFillAlpha(80);

        LineData lineData = new LineData(dispensedSet, consumedSet);
        lineChart.setData(lineData);
        lineChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        lineChart.getXAxis().setLabelCount(Math.min(labels.size(), 7));
        lineChart.invalidate();
    }

    public void populateBarChart(BarChart barChart,
                                 List<DashboardData.FeedingFrequencyData> data) {
        if (data == null || data.isEmpty()) {
            barChart.clear();
            barChart.invalidate();
            return;
        }

        final float GROUP_SPACE = 0.22f;
        final float BAR_SPACE   = 0.04f;
        final float BAR_WIDTH   = 0.35f;

        List<BarEntry> scheduledEntries = new ArrayList<>();
        List<BarEntry> completedEntries = new ArrayList<>();
        List<String>   labels           = new ArrayList<>();

        for (int i = 0; i < data.size(); i++) {
            DashboardData.FeedingFrequencyData item = data.get(i);
            scheduledEntries.add(new BarEntry(i, (float) item.getScheduledCount()));
            completedEntries.add(new BarEntry(i, (float) item.getCompletedCount()));
            labels.add(item.getLabel());
        }

        BarDataSet scheduledSet = new BarDataSet(scheduledEntries, "Scheduled");
        scheduledSet.setColor(COLOR_SCHEDULED);
        scheduledSet.setDrawValues(false);
        scheduledSet.setHighlightEnabled(false);

        BarDataSet completedSet = new BarDataSet(completedEntries, "Completed");
        completedSet.setColor(COLOR_COMPLETED);
        completedSet.setDrawValues(false);
        completedSet.setHighlightEnabled(false);

        BarData barData = new BarData(scheduledSet, completedSet);
        barData.setBarWidth(BAR_WIDTH);

        barChart.setData(barData);
        barChart.groupBars(0f, GROUP_SPACE, BAR_SPACE);

        barChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        barChart.getXAxis().setLabelCount(labels.size());
        barChart.getXAxis().setAxisMinimum(0f);
        barChart.getXAxis().setAxisMaximum(
                barData.getGroupWidth(GROUP_SPACE, BAR_SPACE) * data.size());

        barChart.invalidate();
    }

    public static class WeightValueFormatter extends ValueFormatter {
        @Override public String getFormattedValue(float value) {
            return String.format("%.0fg", value);
        }
    }

    public static class CountValueFormatter extends ValueFormatter {
        @Override public String getFormattedValue(float value) {
            return String.format("%.0f", value);
        }
    }
}
