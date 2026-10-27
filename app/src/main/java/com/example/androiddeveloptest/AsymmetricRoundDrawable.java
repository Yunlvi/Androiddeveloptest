package com.example.androiddeveloptest;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 四角可独立设置圆角的纯色背景。
 *
 * <p>系统自带的 {@code GradientDrawable} 只能四角统一圆角，
 * 而弹窗面板要求"上方小圆角、下方大圆角"，因此自行绘制。
 *
 * <p>支持"底部挖槽"：用放大后的按钮形状（圆角矩形）做蒙版，
 * 从面板上 DIFFERENCE 掉，使嵌放的按钮周围留出等距透明间隔。
 */
public class AsymmetricRoundDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rectF = new RectF();
    private final float[] radii = new float[8];

    /**
     * @param color         填充色
     * @param topLeftDp     左上圆角
     * @param topRightDp    右上圆角
     * @param bottomRightDp 右下圆角
     * @param bottomLeftDp  左下圆角
     * @param density       屏幕密度，用于 dp 转 px
     */
    public AsymmetricRoundDrawable(int color,
                                   float topLeftDp, float topRightDp,
                                   float bottomRightDp, float bottomLeftDp,
                                   float density) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);

        float tl = topLeftDp * density;
        float tr = topRightDp * density;
        float br = bottomRightDp * density;
        float bl = bottomLeftDp * density;

        radii[0] = tl;
        radii[1] = tl;
        radii[2] = tr;
        radii[3] = tr;
        radii[4] = br;
        radii[5] = br;
        radii[6] = bl;
        radii[7] = bl;
    }

    // ==================================================================
    // 底部挖槽：用"放大后的按钮形状"做蒙版
    // ==================================================================

    /** 挖槽宽度（px）。0 表示不挖。 */
    private float notchWidth = 0f;
    /** 挖槽高度（px）。 */
    private float notchHeight = 0f;
    /** 挖槽圆角半径（px）＝ 按钮圆角 + 间隔，保证与按钮轮廓等距。 */
    private float notchRadius = 0f;
    /** 挖槽顶边相对面板底边向上嵌入的深度（px）。 */
    private float notchTopOverlap = 0f;
    /**
     * 多挖槽时每个槽相对面板中心线的水平偏移（px）。
     *
     * <p>null 或空数组表示只在正中挖一个槽。
     * 例如双按钮时传 {-offset, +offset}，挖出两个独立的孔。
     */
    private float[] notchCenters = null;

    /**
     * 在面板底部挖一个"放大版按钮形状"的透明缺口（居中）。
     *
     * <p>挖槽圆角 = 按钮圆角 + gap，四周向外扩 gap，
     * 因此挖出的边缘与按钮轮廓处处等距，间隔自然均匀。
     *
     * @param buttonWidth  按钮宽度（px）
     * @param buttonHeight 按钮高度（px）
     * @param buttonRadius 按钮圆角半径（px）
     * @param gap          按钮与挖槽边缘的间隔（px）
     * @param topOverlap   挖槽顶边在面板底边之上的深度（px）＝ 按钮嵌入深度 + gap
     */
    public void setBottomNotchByButton(float buttonWidth, float buttonHeight,
                                       float buttonRadius, float gap,
                                       float topOverlap) {
        setBottomNotchByButton(buttonWidth, buttonHeight, buttonRadius,
                gap, topOverlap, null);
    }

    /**
     * 在面板底部挖多个独立缺口（每个按钮一个孔）。
     *
     * @param centers 各槽中心相对面板中心的水平偏移（px）；
     *                null 表示居中单槽
     */
    public void setBottomNotchByButton(float buttonWidth, float buttonHeight,
                                       float buttonRadius, float gap,
                                       float topOverlap, float[] centers) {
        this.notchWidth = buttonWidth + gap * 2;
        this.notchHeight = buttonHeight + gap * 2;
        this.notchRadius = buttonRadius + gap;
        this.notchTopOverlap = topOverlap;
        this.notchCenters = centers;
        invalidateSelf();
    }

    /** 构建挖槽路径（支持多个独立缺口的并集）。 */
    private Path buildNotchPath(Rect b) {
        Path p = new Path();
        float baseCx = b.centerX();
        float top = b.bottom - notchTopOverlap;

        if (notchCenters == null || notchCenters.length == 0) {
            // 单槽：居中
            RectF nr = new RectF(baseCx - notchWidth / 2f, top,
                    baseCx + notchWidth / 2f, top + notchHeight);
            p.addRoundRect(nr, notchRadius, notchRadius, Path.Direction.CW);
        } else {
            // 多槽：各自偏移
            for (float off : notchCenters) {
                float cx = baseCx + off;
                RectF nr = new RectF(cx - notchWidth / 2f, top,
                        cx + notchWidth / 2f, top + notchHeight);
                p.addRoundRect(nr, notchRadius, notchRadius, Path.Direction.CW);
            }
        }
        return p;
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        rectF.set(b);
        path.reset();
        path.addRoundRect(rectF, radii, Path.Direction.CW);

        if (notchWidth > 0 && notchHeight > 0) {
            Path result = new Path();
            result.op(path, buildNotchPath(b), Path.Op.DIFFERENCE);
            path.set(result);
        }

        canvas.drawPath(path, paint);
    }

    /**
     * 把挖槽后的轮廓同步为 View 的 outline，
     * 否则 elevation 阴影仍按完整矩形渲染，挖槽区域会出现"隐形阴影"。
     */
    public void applyAsOutline(View target) {
        target.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                Rect b = getBounds();
                if (b.isEmpty() || notchWidth <= 0) {
                    outline.setRoundRect(b, radii[4]);
                    return;
                }
                android.graphics.Path p = new android.graphics.Path();
                android.graphics.RectF rf = new android.graphics.RectF(b);
                p.addRoundRect(rf, radii, android.graphics.Path.Direction.CW);
                android.graphics.Path result = new android.graphics.Path();
                result.op(p, buildNotchPath(b), android.graphics.Path.Op.DIFFERENCE);
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    outline.setPath(result);
                } else {
                    // API 30 以下凹形不支持阴影轮廓，退化为圆角矩形
                    outline.setRoundRect(b, radii[4]);
                }
            }
        });
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
