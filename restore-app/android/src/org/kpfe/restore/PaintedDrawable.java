package org.kpfe.restore;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Native paths from the approved charcoal/paint preview; adds no padding or hit regions. */
public final class PaintedDrawable extends Drawable {
    private static final int PANEL = 0, BUTTON = 1, TITLE = 2, FRAME = 3;
    private static final int YELLOW = 0xffc7d869;

    private static final Path PANEL_SHAPE = polygon(0,3, 3,1, 28,0, 63,1, 97,0, 100,3,
            99.4f,41, 100,73, 98.5f,98, 66,100, 35,99, 1,100, 0,96, .7f,61);
    private static final Path BUTTON_SHAPE = polygon(1,4, 24,0, 65,3, 99,0, 100,14,
            99.4f,75, 98.8f,97, 68,100, 36,97, 0,100, .7f,67, 0,18);

    private static final Path TITLE_TOP = polygon(56,11, 481,7, 469,15, 509,14,
            496,24, 23,30, 38,19, 11,20);
    private static final Path TITLE_BODY = polygon(29,21, 510,16, 498,23, 527,24,
            507,32, 517,39, 492,41, 501,49, 49,54, 61,46, 23,47, 35,38, 10,35);
    private static final Path TITLE_BOTTOM = polygon(61,50, 473,45, 458,52, 488,51, 453,59, 40,59);
    private static final Path TITLE_GLINT = lines(new float[] {77,57, 445,52});
    private static final Path TITLE_DABS = lines(new float[] {18,29, 8,31},
            new float[] {519,49, 532,47}, new float[] {39,13, 49,11});
    private static final Path TITLE_DOTS = circles(531,31,2, 26,52,1.5f);
    private static final Path TITLE_SHADOW = titleSilhouette();

    private static final Path FRAME_CORNERS = lines(new float[] {9,67, 10,35, 177,32},
            new float[] {642,33, 808,35, 810,66}, new float[] {809,347, 807,372, 642,374},
            new float[] {179,373, 10,371, 8,345});
    private static final Path FRAME_SIDES = lines(new float[] {13,73, 9,185, 11,278, 9,334},
            new float[] {810,74, 808,178, 810,276, 809,335}, new float[] {190,373, 345,374, 458,372, 630,374});
    private static final Path FRAME_FINE = lines(new float[] {24,29, 151,28}, new float[] {669,30, 792,32},
            new float[] {13,378, 129,379}, new float[] {689,377, 794,376});
    private static final Path FRAME_MARKS = lines(new float[] {6,85, 7,120}, new float[] {813,319, 812,349},
            new float[] {164,378, 175,379}, new float[] {642,29, 654,30});
    private static final Path FRAME_PAINT = framePaint();
    private static final Path FRAME_DOTS = circles(20,23,2, 799,25,1.6f, 796,383,1.3f, 21,384,1.4f);

    private final int kind;
    private final int fillColor;
    private final float density = Resources.getSystem().getDisplayMetrics().density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint composite = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contour = new Path();
    private final Shader titleGradient = new LinearGradient(10, 0, 527, 0,
            new int[] {0xf0b5c55c, YELLOW, 0xf0becf61}, new float[] {0, .45f, 1}, Shader.TileMode.CLAMP);
    private int drawableAlpha = 255;
    private ColorFilter colorFilter;

    private PaintedDrawable(int kind, int fillColor) { this.kind = kind; this.fillColor = fillColor; }

    public static Drawable panel(int color) { return new PaintedDrawable(PANEL, color); }
    public static Drawable button(int color) { return new PaintedDrawable(BUTTON, color); }
    public static Drawable title() { return new PaintedDrawable(TITLE, 0); }
    public static Drawable frame() { return new PaintedDrawable(FRAME, 0); }

    @Override protected void onBoundsChange(Rect bounds) {
        if (kind != PANEL && kind != BUTTON) return;
        Matrix scale = new Matrix();
        scale.setScale(bounds.width() / 100f, bounds.height() / 100f);
        (kind == PANEL ? PANEL_SHAPE : BUTTON_SHAPE).transform(scale, contour);
        // CSS rounded corners are clipped by the percentage polygon in the preview.
        float[] corners = kind == PANEL ? new float[] {4,4, 12,12, 6,6, 10,10}
                : new float[] {3,3, 8,8, 4,4, 7,7};
        for (int i = 0; i < corners.length; i++) corners[i] *= density;
        Path rounded = new Path();
        rounded.addRoundRect(new RectF(0, 0, bounds.width(), bounds.height()), corners, Path.Direction.CW);
        contour.op(rounded, Path.Op.INTERSECT);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (drawableAlpha == 0 || bounds.isEmpty()) return;
        int save = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        // Apply alpha/filter once to the complete composition, including overlapping brush strokes.
        if (drawableAlpha != 255 || colorFilter != null) {
            composite.setAlpha(drawableAlpha);
            composite.setColorFilter(colorFilter);
            float margin = kind == TITLE ? 24 * density : 0;
            canvas.saveLayer(-margin, -margin, bounds.width() + margin, bounds.height() + margin, composite);
        }
        if (kind == PANEL || kind == BUTTON) drawSurface(canvas);
        else if (kind == TITLE) {
            canvas.scale(bounds.width() / 540f, bounds.height() / 64f);
            drawTitle(canvas);
        } else {
            canvas.scale(bounds.width() / 819.2f, bounds.height() / 460.8f);
            canvas.translate(0, -18);
            canvas.scale(1, 1.236f);
            drawFrame(canvas);
        }
        canvas.restoreToCount(save);
    }

    private void drawSurface(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(contour);
        fill(canvas, contour, fillColor);
        // A double-width stroke clipped to the shape leaves exactly one dp inside.
        if (kind == PANEL || (fillColor & 0x00ffffff) != (YELLOW & 0x00ffffff))
            stroke(canvas, contour, kind == PANEL ? 0x55c7d869 : 0x50c7d869, 2 * density);
        canvas.restoreToCount(save);
    }

    private void drawTitle(Canvas canvas) {
        // A software layer on the title view gives the most consistent blur across Android GPUs.
        // The caller may leave child clipping off to preserve the shadow below the 64-unit artwork.
        int depth = canvas.save();
        canvas.translate(0, 2);
        prepare(0xff66732e, Paint.Style.FILL);
        paint.setShadowLayer(8, 0, 7, 0x80000000);
        canvas.drawPath(TITLE_SHADOW, paint);
        canvas.restoreToCount(depth);
        fill(canvas, TITLE_TOP, 0xa6c7d869);
        prepare(0xffffffff, Paint.Style.FILL);
        paint.setShader(titleGradient);
        canvas.drawPath(TITLE_BODY, paint);
        fill(canvas, TITLE_BOTTOM, 0xccacbd51);
        stroke(canvas, TITLE_GLINT, 0x4de3edb1, 1);
        stroke(canvas, TITLE_DABS, YELLOW, 3);
        fill(canvas, TITLE_DOTS, 0xa6c7d869);
    }

    private void drawFrame(Canvas canvas) {
        stroke(canvas, FRAME_CORNERS, 0xd9c7d869, 3);
        stroke(canvas, FRAME_SIDES, 0x99c7d869, 1.6f);
        stroke(canvas, FRAME_FINE, 0x73c7d869, 1);
        stroke(canvas, FRAME_MARKS, 0x80c7d869, 2);
        fill(canvas, FRAME_PAINT, 0xbfc7d869);
        fill(canvas, FRAME_DOTS, 0xbfc7d869);
    }

    private void prepare(int color, Paint.Style style) {
        paint.clearShadowLayer();
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(style);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void fill(Canvas canvas, Path path, int color) {
        prepare(color, Paint.Style.FILL);
        canvas.drawPath(path, paint);
    }

    private void stroke(Canvas canvas, Path path, int color, float width) {
        prepare(color, Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) {
        int next = Math.max(0, Math.min(255, alpha));
        if (drawableAlpha != next) { drawableAlpha = next; invalidateSelf(); }
    }
    @Override public int getAlpha() { return drawableAlpha; }
    @Override public void setColorFilter(ColorFilter filter) { colorFilter = filter; invalidateSelf(); }
    @Override public ColorFilter getColorFilter() { return colorFilter; }
    @SuppressWarnings("deprecation")
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    private static Path polygon(float... coordinates) {
        Path path = lines(coordinates);
        path.close();
        return path;
    }

    private static Path lines(float[]... segments) {
        Path path = new Path();
        for (float[] points : segments) {
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        }
        return path;
    }

    private static Path circles(float... values) {
        Path path = new Path();
        for (int i = 0; i < values.length; i += 3) path.addCircle(values[i], values[i + 1], values[i + 2], Path.Direction.CW);
        return path;
    }

    private static Path titleSilhouette() {
        Path path = new Path(TITLE_TOP);
        path.op(TITLE_BODY, Path.Op.UNION);
        path.op(TITLE_BOTTOM, Path.Op.UNION);
        path.op(TITLE_DOTS, Path.Op.UNION);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(3); stroke.setStrokeCap(Paint.Cap.ROUND);
        Path dabs = new Path(); stroke.getFillPath(TITLE_DABS, dabs);
        path.op(dabs, Path.Op.UNION);
        return path;
    }

    private static Path framePaint() {
        Path path = polygon(7,33, 35,30, 32,34, 16,38, 11,63, 6,68);
        path.addPath(polygon(783,31, 810,32, 814,56, 809,69, 806,40, 780,35));
        path.addPath(polygon(5,354, 13,348, 15,367, 44,371, 37,376, 7,375));
        path.addPath(polygon(805,350, 812,344, 813,373, 783,379, 770,375, 802,369));
        return path;
    }
}
