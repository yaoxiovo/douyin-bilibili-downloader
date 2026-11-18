package com.yaoxiovo.hub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class JournalArtwork extends View {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    JournalArtwork(Context context) {
        super(context);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 132f, getHeight() / 125f);
        canvas.save();
        canvas.translate((getWidth() - 132 * scale) / 2, (getHeight() - 125 * scale) / 2);
        canvas.scale(scale, scale);

        canvas.save();
        canvas.rotate(11, 68, 60);
        fill(0xffc5d2b7);
        canvas.drawRoundRect(24, 16, 119, 113, 8, 8, ink);
        fill(0xffb2c3a5);
        for (int y = 27; y < 110; y += 10) canvas.drawRect(31, y, 111, y + 1, ink);
        canvas.restore();

        canvas.save();
        canvas.rotate(-9, 56, 65);
        fill(0x25754734);
        canvas.drawRoundRect(13, 20, 108, 117, 6, 6, ink);
        fill(0xfffffbef);
        canvas.drawRoundRect(10, 16, 105, 112, 6, 6, ink);
        fill(0xffedd8bc);
        canvas.drawRect(17, 24, 98, 91, ink);
        fill(0xffbb7057);
        canvas.drawCircle(77, 42, 10, ink);
        polygon(canvas, 0xffa49d71, 17,72, 41,54, 58,71, 75,57, 98,76, 98,91, 17,91);
        polygon(canvas, 0xff688274, 17,83, 39,68, 61,86, 82,73, 98,82, 98,91, 17,91);
        fill(0xffdbc5a8);
        canvas.drawRoundRect(22, 99, 56, 101, 1, 1, ink);
        canvas.restore();

        canvas.save();
        canvas.rotate(12, 64, 15);
        fill(0xbbe5af7d);
        canvas.drawRoundRect(43, 9, 86, 24, 2, 2, ink);
        fill(0x55fff3cd);
        for (int x = 47; x < 85; x += 6) canvas.drawRect(x, 10, x + 2, 23, ink);
        canvas.restore();

        fill(0xffa05140);
        canvas.drawCircle(113, 96, 13, ink);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(1);
        ink.setColor(0xfff8e7d2);
        canvas.drawCircle(113, 96, 10, ink);
        ink.setStrokeWidth(1.4f);
        canvas.drawLine(113, 91, 113, 101, ink);
        canvas.drawLine(108, 96, 118, 96, ink);

        canvas.restore();
    }

    private void fill(int color) {
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(color);
    }

    private void polygon(Canvas canvas, int color, float... coordinates) {
        path.reset();
        path.moveTo(coordinates[0], coordinates[1]);
        for (int index = 2; index < coordinates.length; index += 2) {
            path.lineTo(coordinates[index], coordinates[index + 1]);
        }
        path.close();
        fill(color);
        canvas.drawPath(path, ink);
    }
}
