package com.lcodexs.coursealarm;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.LruCache;

import com.astraisland.sdk.IslandImage;

/** Small, transparent glyphs. The renderer supplies the capsule background and progress ring. */
final class CourseAstraGlyphs {
    private static final LruCache<Long, IslandImage> NUMBERS = new LruCache<>(16);
    static final IslandImage TIMETABLE = draw((canvas, paint) -> {
        canvas.drawRoundRect(3f, 4.5f, 21f, 21f, 2.8f, 2.8f, paint);
        canvas.drawLine(3, 9, 21, 9, paint);
        canvas.drawLine(8, 2.5f, 8, 6.5f, paint);
        canvas.drawLine(16, 2.5f, 16, 6.5f, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int x : new int[]{8, 12, 16}) {
            canvas.drawCircle(x, 13, 0.95f, paint);
            canvas.drawCircle(x, 17, 0.95f, paint);
        }
    });
    private static final IslandImage BOOK = draw((canvas, paint) -> {
        Path pages = new Path();
        pages.moveTo(12, 6);
        pages.cubicTo(9, 3.8f, 5, 3.5f, 2.5f, 4.5f);
        pages.lineTo(2.5f, 18.1f);
        pages.cubicTo(5.8f, 16.9f, 9.5f, 17.8f, 12, 20);
        pages.cubicTo(14.5f, 17.8f, 18.2f, 16.9f, 21.5f, 18.1f);
        pages.lineTo(21.5f, 4.5f);
        pages.cubicTo(19, 3.5f, 15, 3.8f, 12, 6);
        pages.close();
        canvas.drawPath(pages, paint);
        canvas.drawLine(12, 6, 12, 20, paint);
    });
    private static final IslandImage CHECK = draw((canvas, paint) -> {
        Path tick = new Path();
        tick.moveTo(5, 12.3f);
        tick.lineTo(10, 17);
        tick.lineTo(19, 7.5f);
        paint.setStrokeWidth(2.5f);
        canvas.drawPath(tick, paint);
    });

    private CourseAstraGlyphs() { }

    static IslandImage badge(CourseAstraStyle style, long start, long end, long now) {
        return switch (style.glyph(start, end, now)) {
            case TIMETABLE -> TIMETABLE;
            case BOOK -> BOOK;
            case CHECK -> CHECK;
            case MINUTES -> number(CourseOccurrence.minutesUntil(now < start ? start : end, now));
        };
    }

    private static IslandImage number(long minutes) {
        IslandImage existing = NUMBERS.get(minutes);
        if (existing != null) return existing;
        IslandImage digit = draw((canvas, paint) -> {
            String text = Long.toString(minutes);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(17);
            float width = paint.measureText(text);
            if (width > 22) paint.setTextSize(17 * 22 / width);
            canvas.drawText(text, 12, 12 - (paint.ascent() + paint.descent()) / 2, paint);
        });
        NUMBERS.put(minutes, digit);
        return digit;
    }

    private interface Draw { void onCanvas(Canvas canvas, Paint paint); }

    private static IslandImage draw(Draw glyph) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(4, 4);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        glyph.onCanvas(canvas, paint);
        return IslandImage.glyph(bitmap);
    }
}
