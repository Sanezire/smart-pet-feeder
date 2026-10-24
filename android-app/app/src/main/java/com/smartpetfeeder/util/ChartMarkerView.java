package com.smartpetfeeder.util;

import android.content.Context;
import android.widget.TextView;

import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;
import com.smartpetfeeder.R;

import java.util.Locale;

public class ChartMarkerView extends MarkerView {

    private final TextView label;

    public ChartMarkerView(Context context) {
        super(context, R.layout.chart_marker);
        label = findViewById(R.id.tvChartMarker);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        label.setText(String.format(Locale.US, "%.0f g", e.getY()));
        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 12f);
    }
}
