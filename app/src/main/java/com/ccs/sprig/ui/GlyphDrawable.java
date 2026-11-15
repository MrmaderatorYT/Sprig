package com.ccs.sprig.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Small, resolution-independent icons with a shared visual weight. */
final class GlyphDrawable extends Drawable {
    enum Kind { SHIELD, TRAFFIC, PAUSE, SEND, TOOLS, SETTINGS, PLAY, STOP, SEARCH, EXPORT, COPY, TRASH, CERTIFICATE, CLOSE, ARROW, CHECK, GLOBE, FILTER }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Kind kind;
    private final int size;

    GlyphDrawable(Kind kind, int color, int size) {
        this.kind = kind;
        this.size = size;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
        Path p = new Path();
        switch (kind) {
            case SHIELD:
                p.moveTo(12, 3); p.lineTo(20, 6); p.lineTo(20, 12); p.cubicTo(20, 17, 16, 20, 12, 22); p.cubicTo(8, 20, 4, 17, 4, 12); p.lineTo(4, 6); p.close();
                canvas.drawPath(p, paint); line(canvas, 8.5f, 12, 11, 14.5f); line(canvas, 11, 14.5f, 16, 9.5f); break;
            case TRAFFIC:
                line(canvas, 4, 7, 20, 7); line(canvas, 16, 3, 20, 7); line(canvas, 20, 7, 16, 11);
                line(canvas, 20, 17, 4, 17); line(canvas, 8, 13, 4, 17); line(canvas, 4, 17, 8, 21); break;
            case PAUSE:
                canvas.drawRoundRect(6, 4, 9, 20, 1, 1, paint); canvas.drawRoundRect(15, 4, 18, 20, 1, 1, paint); break;
            case SEND:
                p.moveTo(3, 4); p.lineTo(22, 12); p.lineTo(3, 20); p.lineTo(6, 12); p.close(); canvas.drawPath(p, paint); line(canvas, 6, 12, 15, 12); break;
            case TOOLS:
                canvas.drawRoundRect(3, 7, 21, 21, 3, 3, paint); canvas.drawRoundRect(8, 3, 16, 7, 2, 2, paint); line(canvas, 3, 12, 21, 12); line(canvas, 9, 10, 9, 14); line(canvas, 15, 10, 15, 14); break;
            case SETTINGS:
                canvas.drawCircle(12, 12, 3.5f, paint);
                for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; line(canvas, (float)(12 + 7 * Math.cos(a)), (float)(12 + 7 * Math.sin(a)), (float)(12 + 10 * Math.cos(a)), (float)(12 + 10 * Math.sin(a))); }
                canvas.drawCircle(12, 12, 7, paint); break;
            case PLAY:
                p.moveTo(8, 4); p.lineTo(20, 12); p.lineTo(8, 20); p.close(); canvas.drawPath(p, paint); break;
            case STOP: canvas.drawRoundRect(5, 5, 19, 19, 2, 2, paint); break;
            case SEARCH: canvas.drawCircle(10, 10, 6.5f, paint); line(canvas, 15, 15, 21, 21); break;
            case EXPORT:
                p.moveTo(4, 14); p.lineTo(4, 20); p.lineTo(20, 20); p.lineTo(20, 14); canvas.drawPath(p, paint);
                line(canvas, 12, 16, 12, 3); line(canvas, 7, 8, 12, 3); line(canvas, 12, 3, 17, 8); break;
            case COPY: canvas.drawRoundRect(8, 8, 21, 21, 2, 2, paint); p.moveTo(16, 5); p.lineTo(16, 3); p.lineTo(3, 3); p.lineTo(3, 16); p.lineTo(5, 16); canvas.drawPath(p, paint); break;
            case TRASH: line(canvas, 4, 6, 20, 6); line(canvas, 9, 3, 15, 3); p.moveTo(6, 6); p.lineTo(7, 21); p.lineTo(17, 21); p.lineTo(18, 6); canvas.drawPath(p, paint); line(canvas, 10, 10, 10, 17); line(canvas, 14, 10, 14, 17); break;
            case CERTIFICATE:
                p.moveTo(15, 3); p.lineTo(4, 3); p.lineTo(4, 21); p.lineTo(20, 21); p.lineTo(20, 8); p.lineTo(15, 3); p.lineTo(15, 8); p.lineTo(20, 8); canvas.drawPath(p, paint);
                canvas.drawCircle(11, 13, 2.5f, paint); line(canvas, 9, 15, 8, 19); line(canvas, 13, 15, 14, 19); break;
            case CLOSE: line(canvas, 5, 5, 19, 19); line(canvas, 19, 5, 5, 19); break;
            case ARROW: line(canvas, 4, 12, 20, 12); line(canvas, 14, 6, 20, 12); line(canvas, 20, 12, 14, 18); break;
            case CHECK: line(canvas, 4, 12, 9, 17); line(canvas, 9, 17, 20, 6); break;
            case GLOBE: canvas.drawCircle(12, 12, 9, paint); canvas.drawOval(8, 3, 16, 21, paint); line(canvas, 3, 12, 21, 12); line(canvas, 5, 7, 19, 7); line(canvas, 5, 17, 19, 17); break;
            case FILTER: line(canvas, 3, 5, 21, 5); line(canvas, 6, 12, 18, 12); line(canvas, 9, 19, 15, 19); break;
        }
        canvas.restore();
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) { c.drawLine(x1, y1, x2, y2, paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
}
