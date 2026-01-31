package com.suzuki.bletest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * A custom placeholder view for the map that simulates a dark themed map with a
 * route.
 */
public class PlaceholderMapView extends View {

    private Paint gridPaint;
    private Paint routePaint;
    private Paint backgroundPaint;
    private Path routePath;

    public PlaceholderMapView(Context context) {
        super(context);
        init();
    }

    public PlaceholderMapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        backgroundPaint = new Paint();
        backgroundPaint.setColor(Color.parseColor("#1C2333"));
        backgroundPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#2A3447"));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);

        routePaint = new Paint();
        routePaint.setColor(Color.parseColor("#3399FF"));
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(8f);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setAntiAlias(true);

        routePath = new Path();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        // Draw background
        canvas.drawRect(0, 0, width, height, backgroundPaint);

        // Draw grid (simulating blocks/streets)
        float gridSize = 100f;
        for (float x = 0; x <= width; x += gridSize) {
            canvas.drawLine(x, 0, x, height, gridPaint);
        }
        for (float y = 0; y <= height; y += gridSize) {
            canvas.drawLine(0, y, width, y, gridPaint);
        }

        // Draw a simulated route
        routePath.reset();
        routePath.moveTo(width * 0.2f, height * 0.8f);
        routePath.lineTo(width * 0.4f, height * 0.6f);
        routePath.lineTo(width * 0.35f, height * 0.4f);
        routePath.lineTo(width * 0.6f, height * 0.3f);
        routePath.lineTo(width * 0.7f, height * 0.1f);

        canvas.drawPath(routePath, routePaint);
    }
}
