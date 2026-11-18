package com.yaoxiovo.hub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Collections;
import java.util.List;

final class DailyTrend extends View {
    private final List<Long> values;
    private final long max;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();

    DailyTrend(Context context, String title, List<Long> values) {
        super(context);
        this.values = values;
        max = Math.max(Collections.max(values), 1);
        setContentDescription(title + "，最高 " + Collections.max(values) + "，最低 " + Collections.min(values));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float inset = 5 * getResources().getDisplayMetrics().density;
        final float span = Math.max(getWidth() - 2 * inset, 0);
        final float height = Math.max(getHeight() - 2 * inset, 0);
        final float step = span / Math.max(1, values.size() - 1);
        ink.setStyle(Paint.Style.STROKE);
        ink.setColor(0xffe6d4ba);
        ink.setStrokeWidth(1);
        canvas.drawLine(inset, inset + height, inset + span, inset + height, ink);
        canvas.drawLine(inset, inset + height / 2, inset + span, inset + height / 2, ink);
        ink.setColor(0xff883c33);
        ink.setStrokeWidth(inset / 2);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        line.reset();
        for (int index = 0; index < values.size(); index++) {
            float y = inset + height * (1 - values.get(index).floatValue() / max);
            float x = inset + step * index;
            if (index == 0) line.moveTo(x, y);
            else line.lineTo(x, y);
            canvas.drawPoint(x, y, ink);
        }
        canvas.drawPath(line, ink);
    }
}
