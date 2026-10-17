package com.example.androiddeveloptest;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;

/**
 * 碗形弧线底部导航栏。
 *
 * <p>结构说明：
 * <ul>
 *   <li>弧带：用 STROKE + ROUND cap 画一条圆弧，天然得到两头圆的效果</li>
 *   <li>导航项：沿弧线等角度排布，每个项含图标与文字，整体垂直于弧线并做 180° 回正</li>
 *   <li>光晕：径向渐变，裁剪到弧带形状内部，只在弧带内可见</li>
 *   <li>交互：点击切换（带缩放反馈）、长按拖动（Δx 映射为 Δθ）、松手吸附</li>
 * </ul>
 *
 * <p>坐标系约定：0° 指向右（3 点钟），90° 指向下（6 点钟），顺时针为正。
 */
public class CurvedNavBar extends View {

    // ==================================================================
    // 常量
    // ==================================================================

    /** 弧带张角（度）。来自原图的像素扫描结果。 */
    private static final float SWEEP = 67.8f;

    /** 导航项数量。 */
    private static final int ITEM_COUNT = 5;

    /** 长按判定阈值（毫秒）。 */
    private static final long LONG_PRESS_MS = 400;

    /** 选中项颜色（明黄）。 */
    private static final int COLOR_SELECTED = 0xFFFFC107;

    /** 未选中项颜色（白）。 */
    private static final int COLOR_UNSELECTED = 0xFFFFFFFF;

    /** 弧带底色（深蓝）。 */
    private static final int COLOR_ARC = 0xFF16506B;

    // ---- 几何比例（相对弦长，均来自原图像素扫描）----
    private static final float RATIO_CHORD = 0.77f;      // 弦长 / 控件宽度
    private static final float RATIO_RADIUS = 0.896f;    // 半径 / 弦长
    private static final float RATIO_THICKNESS = 0.183f; // 厚度 / 弦长

    // ---- 视觉比例（相对弧带厚度）----
    private static final float RATIO_ICON_SIZE = 0.63f;  // 图标边长 / 厚度
    private static final float RATIO_TEXT_SIZE = 0.16f;  // 文字大小 / 厚度
    private static final float RATIO_TEXT_GAP = 0.14f;   // 文字间距 / 厚度
    private static final float RATIO_VERTICAL = -0.08f;  // 垂直微调 / 厚度（负值上移）
    private static final float RATIO_GLOW = 0.6f;        // 光晕半径 / 基准距离

    // ---- 动画参数 ----
    private static final long GLOW_DURATION_MS = 300;
    private static final long PRESS_DOWN_MS = 120;
    private static final long PRESS_UP_MS = 200;
    private static final float PRESS_SCALE_MIN = 0.85f;
    private static final float PRESS_OVERSHOOT = 1.3f;

    // ==================================================================
    // 画笔与路径（复用，避免在 onDraw 里创建对象）
    // ==================================================================

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path arcPath = new Path();
    private final Path arcClipPath = new Path();
    private final RectF arcRect = new RectF();

    private PorterDuffColorFilter filterSelected;
    private PorterDuffColorFilter filterUnselected;

    // ==================================================================
    // 几何参数（在 onSizeChanged 中计算）
    // ==================================================================

    /** 圆心坐标。注意 cy 可能为负，即圆心在控件上方之外。 */
    private float cx, cy;
    /** 弧带中心线的半径。 */
    private float radius;
    /** 弧带厚度（即 strokeWidth）。 */
    private float thickness;
    /** 弧带起始角。 */
    private float startAngle;

    /** 图标+文字整体沿 y 方向的微调量（负值上移）。 */
    private float verticalAdjust;

    // ==================================================================
    // 光晕状态
    // ==================================================================

    private float glowRadius;
    private float glowX, glowY;
    /** 光晕当前所在角度，是动画与拖动共同驱动的唯一状态量。 */
    private float glowAngle;
    private ValueAnimator glowAnimator;

    // ==================================================================
    // 导航项与选中状态
    // ==================================================================

    private final NavItem[] items = new NavItem[ITEM_COUNT];
    private int selectedIndex = ITEM_COUNT / 2;

    // ==================================================================
    // 按压反馈
    // ==================================================================

    private int pressedIndex = -1;
    private float pressScale = 1f;
    private ValueAnimator scaleAnimator;

    // ==================================================================
    // 长按拖动
    // ==================================================================

    private boolean isDragging = false;
    private float dragStartX;
    private float dragStartAngle;
    private float lastDownX;
    /** Activity 是否已通过 onGlobalDown 通知按下。 */
    private boolean globalTracking = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            isDragging = true;
            dragStartX = lastDownX;
            dragStartAngle = glowAngle;

            // 长按成立时立刻退出按压态，避免拖动过程中图标一直缩着
            if (scaleAnimator != null && scaleAnimator.isRunning()) {
                scaleAnimator.cancel();
            }
            pressedIndex = -1;
            pressScale = 1f;
            invalidate();

            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    };

    // ==================================================================
    // 对外回调
    // ==================================================================

    /**
     * 导航栏交互回调。
     */
    public interface OnItemSelectedListener {

        /**
         * 选中项确定变化后触发（点击、拖动松手吸附）。
         *
         * @param index 目标导航项下标
         */
        void onItemSelected(int index);

        /**
         * 拖动过程中的连续进度，用于让页面跟手平移。
         *
         * @param progress 取值 0 ~ ITEM_COUNT-1，0 对应最左页
         */
        void onProgressChanged(float progress);
    }

    private OnItemSelectedListener listener;

    /**
     * 设置交互回调。
     */
    public void setOnItemSelectedListener(OnItemSelectedListener l) {
        this.listener = l;
    }

    // ==================================================================
    // 构造
    // ==================================================================

    public CurvedNavBar(Context context) {
        this(context, null);
    }

    public CurvedNavBar(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CurvedNavBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setColor(COLOR_ARC);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeJoin(Paint.Join.ROUND);

        // PorterDuff.Mode.SRC_IN：保留图标本身的透明度，只替换颜色
        filterSelected = new PorterDuffColorFilter(COLOR_SELECTED, PorterDuff.Mode.SRC_IN);
        filterUnselected = new PorterDuffColorFilter(COLOR_UNSELECTED, PorterDuff.Mode.SRC_IN);

        textPaint.setColor(COLOR_UNSELECTED);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 下标顺序即弧线上从左到右的顺序
        items[0] = new NavItem(R.drawable.ic_tools, "工具");
        items[1] = new NavItem(R.drawable.ic_statistics, "统计");
        items[2] = new NavItem(R.drawable.ic_home, "主页");
        items[3] = new NavItem(R.drawable.ic_bill, "账单");
        items[4] = new NavItem(R.drawable.ic_person, "我的");

        for (NavItem item : items) {
            item.icon = BitmapFactory.decodeResource(getResources(), item.iconRes);
        }
    }

    // ==================================================================
    // 测量与布局
    // ==================================================================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        computeArcGeometry(w, h);
        buildArcPaths();
        computeItemLayout(w, h);
        computeGlow(w);
    }

    /**
     * 计算圆心、半径、厚度等基础几何量。
     */
    private void computeArcGeometry(int w, int h) {
        float chord = w * RATIO_CHORD;
        radius = chord * RATIO_RADIUS;
        thickness = chord * RATIO_THICKNESS;
        arcPaint.setStrokeWidth(thickness);

        cx = w / 2f;

        // 由"中心线最低点"反推圆心，保证弧带底部不超出控件
        float bottomMargin = h * 0.16f;
        float lowestCenter = h - bottomMargin - thickness / 2f;
        cy = lowestCenter - radius;

        startAngle = 90f - SWEEP / 2f;
    }

    /**
     * 构建两条路径：
     * <ul>
     *   <li>arcPath —— 中心线的圆弧，用于描边绘制</li>
     *   <li>arcClipPath —— 带厚度的实心弧带，用于裁剪光晕</li>
     * </ul>
     */
    private void buildArcPaths() {
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        arcPath.reset();
        arcPath.addArc(arcRect, startAngle, SWEEP);

        float rOuter = radius + thickness / 2f;
        float rInner = radius - thickness / 2f;

        RectF outerRect = new RectF(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter);
        RectF innerRect = new RectF(cx - rInner, cy - rInner, cx + rInner, cy + rInner);

        arcClipPath.reset();

        // ① 外弧：起点 -> 终点
        arcClipPath.addArc(outerRect, startAngle, SWEEP);

        // ② 右端帽：从外弧终点沿径向扫 180° 到内弧起点
        float endRad = (float) Math.toRadians(startAngle + SWEEP);
        float endCx = cx + radius * (float) Math.cos(endRad);
        float endCy = cy + radius * (float) Math.sin(endRad);
        arcClipPath.arcTo(
                new RectF(endCx - thickness / 2f, endCy - thickness / 2f,
                        endCx + thickness / 2f, endCy + thickness / 2f),
                startAngle + SWEEP, 180f, false);

        // ③ 内弧：终点 -> 起点（反向）
        arcClipPath.addArc(innerRect, startAngle + SWEEP, -SWEEP);

        // ④ 左端帽：从内侧起扫 180° 回到外弧起点
        float startRad = (float) Math.toRadians(startAngle);
        float startCx = cx + radius * (float) Math.cos(startRad);
        float startCy = cy + radius * (float) Math.sin(startRad);
        arcClipPath.arcTo(
                new RectF(startCx - thickness / 2f, startCy - thickness / 2f,
                        startCx + thickness / 2f, startCy + thickness / 2f),
                startAngle + 180f, 180f, false);

        arcClipPath.close();
    }

    /**
     * 计算各导航项的角度与坐标。
     */
    private void computeItemLayout(int w, int h) {
        // 端帽占据的角度，用它留出边距，避免首尾图标压在端帽上
        float capAngle = (float) Math.toDegrees(Math.atan((thickness / 2f) / radius));
        float spreadSweep = SWEEP - capAngle * 1.2f;
        float spreadStart = 90f - spreadSweep / 2f;

        float iconSize = thickness * RATIO_ICON_SIZE;
        textPaint.setTextSize(thickness * RATIO_TEXT_SIZE);
        float half = iconSize / 2f;

        for (int i = 0; i < ITEM_COUNT; i++) {
            NavItem item = items[i];

            // 注意下标反序：cos 在钝角区为负，角度越大 x 越小（越靠左）
            float angle = spreadStart + spreadSweep * (ITEM_COUNT - 1 - i) / (ITEM_COUNT - 1);
            item.angle = angle;

            float[] p = pointOnArc(angle);
            item.centerX = p[0];
            item.centerY = p[1];

            item.iconRect.set(-half, -half, half, half);
            item.selected = (i == selectedIndex);
        }

        verticalAdjust = thickness * RATIO_VERTICAL;
    }

    /**
     * 计算光晕半径。基准为"中间图标中心到控件顶部中点"的距离。
     */
    private void computeGlow(int w) {
        int mid = ITEM_COUNT / 2;
        float dx = items[mid].centerX - w / 2f;
        float dy = items[mid].centerY;
        glowRadius = (float) Math.hypot(dx, dy) * RATIO_GLOW;

        glowAngle = items[mid].angle;
        updateGlowPosition();
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. 弧带
        canvas.drawPath(arcPath, arcPaint);

        // 2. 光晕（在弧带之上、图标之下）
        drawGlow(canvas);

        // 3. 导航项
        drawItems(canvas);
    }

    private void drawGlow(Canvas canvas) {
        RadialGradient shader = new RadialGradient(
                glowX, glowY, glowRadius,
                new int[]{
                        0xFFFFF59D,   // 中心：淡黄
                        0x8FFFEB3B,   // 中段：明黄
                        0x00FFEB3B    // 边缘：透明
                },
                new float[]{0f, 0.35f, 1f},
                Shader.TileMode.CLAMP);
        glowPaint.setShader(shader);

        // 裁剪到弧带内部，否则光晕会溢出到弧带外的空白区域
        canvas.save();
        canvas.clipPath(arcClipPath);
        canvas.drawCircle(glowX, glowY, glowRadius, glowPaint);
        canvas.restore();
    }

    private void drawItems(Canvas canvas) {
        float textGap = thickness * RATIO_TEXT_GAP;

        for (int i = 0; i < ITEM_COUNT; i++) {
            NavItem item = items[i];

            iconPaint.setColorFilter(item.selected ? filterSelected : filterUnselected);
            textPaint.setColor(item.selected ? COLOR_SELECTED : COLOR_UNSELECTED);

            float scale = (i == pressedIndex) ? pressScale : 1f;
            item.draw(canvas, iconPaint, textPaint, textGap, verticalAdjust, scale);
        }

        iconPaint.setColorFilter(null);
    }

    // ==================================================================
    // 几何换算
    // ==================================================================

    /**
     * 极坐标转直角坐标。
     *
     * @param angleDeg 角度（度）
     * @return 长度为 2 的数组：[x, y]
     */
    private float[] pointOnArc(float angleDeg) {
        double rad = Math.toRadians(angleDeg);
        return new float[]{
                cx + radius * (float) Math.cos(rad),
                cy + radius * (float) Math.sin(rad)
        };
    }

    /**
     * 根据 glowAngle 重算光晕中心坐标。
     */
    private void updateGlowPosition() {
        float[] p = pointOnArc(glowAngle);
        glowX = p[0];
        glowY = p[1];
    }

    /**
     * 找离给定角度最近的导航项下标。
     */
    private int angleToIndex(float angleDeg) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < ITEM_COUNT; i++) {
            float d = Math.abs(angleDeg - items[i].angle);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * 把光晕角度换算成页面进度。
     *
     * <p>items[0] 角度最大（最左）、items[ITEM_COUNT-1] 角度最小（最右），
     * 而 progress 0 对应最左页，因此需要反向映射。
     *
     * @return 0 ~ ITEM_COUNT-1 的连续值
     */
    private float angleToProgress(float angleDeg) {
        float maxAngle = items[0].angle;
        float minAngle = items[ITEM_COUNT - 1].angle;

        float span = maxAngle - minAngle;
        if (span <= 0.001f) return 0f;

        float t = (maxAngle - angleDeg) / span;
        t = Math.max(0f, Math.min(1f, t));
        return t * (ITEM_COUNT - 1);
    }

    // ==================================================================
    // 选中与动画
    // ==================================================================

    /**
     * 把光晕动画移动到指定导航项，并通知外部切页。
     */
    public void animateGlowTo(int index) {
        if (index < 0 || index >= ITEM_COUNT) return;

        if (glowAnimator != null && glowAnimator.isRunning()) {
            glowAnimator.cancel();
        }

        float from = glowAngle;
        float to = items[index].angle;

        for (int i = 0; i < ITEM_COUNT; i++) {
            items[i].selected = (i == index);
        }
        selectedIndex = index;

        glowAnimator = ValueAnimator.ofFloat(from, to);
        glowAnimator.setDuration(GLOW_DURATION_MS);
        glowAnimator.setInterpolator(new DecelerateInterpolator());
        glowAnimator.addUpdateListener(animation -> {
            glowAngle = (float) animation.getAnimatedValue();
            updateGlowPosition();
            invalidate();
        });
        glowAnimator.start();

        // 吸附时 index 可能没变，但页面仍需归位，所以无条件通知
        if (listener != null) {
            listener.onItemSelected(index);
        }
    }

    /**
     * 立即选中（无动画）。拖动过程中使用，页面位置由 onProgressChanged 驱动。
     *
     * @param notify 是否通知外部切页。拖动时应传 false，避免和跟手动画冲突。
     */
    private void applySelection(int index, boolean notify) {
        if (index < 0 || index >= ITEM_COUNT) return;

        for (int i = 0; i < ITEM_COUNT; i++) {
            items[i].selected = (i == index);
        }
        selectedIndex = index;

        if (notify && listener != null) {
            listener.onItemSelected(index);
        }
    }

    /**
     * 吸附到离光晕最近的导航项。
     */
    private void snapToNearest() {
        animateGlowTo(angleToIndex(glowAngle));
    }

    /**
     * 按下 / 回弹的缩放动画。
     *
     * @param down true = 缩小（按下），false = 弹回（松手）
     */
    private void animatePress(boolean down) {
        if (scaleAnimator != null && scaleAnimator.isRunning()) {
            scaleAnimator.cancel();
        }

        float from = pressScale;
        float to = down ? PRESS_SCALE_MIN : 1f;

        scaleAnimator = ValueAnimator.ofFloat(from, to);
        scaleAnimator.setDuration(down ? PRESS_DOWN_MS : PRESS_UP_MS);
        scaleAnimator.setInterpolator(down
                ? new DecelerateInterpolator()
                : new OvershootInterpolator(PRESS_OVERSHOOT));

        scaleAnimator.addUpdateListener(a -> {
            pressScale = (float) a.getAnimatedValue();
            invalidate();
        });
        scaleAnimator.start();
    }

    // ==================================================================
    // 触摸处理：控件内
    // ==================================================================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
                return handleTouchDown(event);

            case MotionEvent.ACTION_MOVE:
                if (!isDragging) return true;
                handleDragEvent(event);
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                if (isDragging) {
                    handleDragEvent(event);
                } else if (pressedIndex >= 0) {
                    animatePress(false);
                    animateGlowTo(pressedIndex);
                }
                pressedIndex = -1;
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                if (isDragging) {
                    handleDragEvent(event);
                } else {
                    animatePress(false);
                }
                pressedIndex = -1;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean handleTouchDown(MotionEvent event) {
        lastDownX = event.getX();

        float dx = event.getX() - cx;
        float dy = event.getY() - cy;
        float r = (float) Math.hypot(dx, dy);
        float halfThick = thickness / 2f;

        // 点在弧带外：不消费事件，交给下层的页面处理
        if (r < radius - halfThick || r > radius + halfThick) {
            return false;
        }

        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        pressedIndex = angleToIndex(angle);

        animatePress(true);
        handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
        return true;
    }

    /**
     * 处理拖动中的事件。控件内触摸与 Activity 转发的全局触摸共用此逻辑。
     */
    private void handleDragEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {

            case MotionEvent.ACTION_MOVE:
                updateDragAngle(ev.getX());
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                globalTracking = false;
                snapToNearest();
                break;
        }
    }

    /**
     * 根据手指横坐标更新光晕角度。
     *
     * <p>换算依据：同半径圆弧的弧长正比于角度，故 Δθ = Δx / R。
     * 角度增大对应向左（cos 在钝角区为负），因此 Δx 需取负号。
     */
    private void updateDragAngle(float fingerX) {
        float dx = fingerX - dragStartX;
        float deltaDeg = (float) Math.toDegrees(-dx / radius);
        float newAngle = dragStartAngle + deltaDeg;

        float minAngle = items[ITEM_COUNT - 1].angle;
        float maxAngle = items[0].angle;
        newAngle = Math.max(minAngle, Math.min(maxAngle, newAngle));

        glowAngle = newAngle;
        updateGlowPosition();

        // 变色但不触发切页动画，页面由 onProgressChanged 跟手
        int idx = angleToIndex(glowAngle);
        if (idx != selectedIndex) {
            applySelection(idx, false);
        }

        if (listener != null) {
            listener.onProgressChanged(angleToProgress(glowAngle));
        }

        invalidate();
    }

    // ==================================================================
    // 触摸处理：全局（由 Activity 转发）
    // ==================================================================

    /**
     * Activity 在 ACTION_DOWN 时调用，开始监听长按。
     */
    public void onGlobalDown(float x, float y) {
        lastDownX = x;
        globalTracking = true;

        if (!isDragging) {
            handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
        }
    }

    /**
     * Activity 在每个触摸事件时调用。
     *
     * @return true 表示导航栏已接管该事件，Activity 应停止向下分发
     */
    public boolean onGlobalTouch(MotionEvent ev) {
        int action = ev.getActionMasked();

        if (!isDragging) {
            // 尚未进入拖动模式：不接管，事件继续正常分发
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(longPressRunnable);
                globalTracking = false;
            }
            return false;
        }

        handleDragEvent(ev);
        return true;
    }

    /**
     * 是否正在全局拖动中。
     */
    public boolean isGlobalDragging() {
        return isDragging;
    }

    // ==================================================================
    // 内部类
    // ==================================================================

    /**
     * 单个导航项：图标 + 文字。
     *
     * <p>自身持有角度、坐标、图标与文字，并负责自己的绘制与旋转逻辑。
     */
    private static class NavItem {

        /** 在弧线上的角度。 */
        float angle;
        /** 图标中心坐标。 */
        float centerX, centerY;

        final int iconRes;
        final String label;
        Bitmap icon;

        /** 图标绘制矩形，以自身中心为原点。 */
        final RectF iconRect = new RectF();
        private final RectF tmpRect = new RectF();

        boolean selected;

        NavItem(int iconRes, String label) {
            this.iconRes = iconRes;
            this.label = label;
        }

        /**
         * 绘制本项。
         *
         * @param canvas    画布
         * @param iconPaint 图标画笔（已设置好 colorFilter）
         * @param textPaint 文字画笔（已设置好颜色与字号）
         * @param textGap   文字与图标底部的间距
         * @param yOffset   整体垂直微调
         * @param scale     缩放系数（按压反馈用，图标与文字一起缩放）
         */
        void draw(Canvas canvas, Paint iconPaint, Paint textPaint,
                  float textGap, float yOffset, float scale) {
            if (icon == null) return;

            // 旋转到"垂直于弧线"：图标默认朝上（270°），需补 90°
            float rotation = angle + 90f;

            // 回正纠偏：旋转角落在 (90°, 270°) 时再转 180°，
            // 否则文字会倒立（弧带中段的角度正好落在该区间）
            float normalized = rotation % 360f;
            if (normalized < 0) normalized += 360f;
            boolean flipped = (normalized > 90f && normalized < 270f);
            float finalRotate = flipped ? rotation + 180f : rotation;

            // 变换顺序必须是：平移 -> 旋转 -> 缩放
            // 若先缩放再平移，平移量会被一起缩放，位置会错
            canvas.save();
            canvas.translate(centerX, centerY + yOffset);
            canvas.rotate(finalRotate);
            canvas.scale(scale, scale);

            tmpRect.set(iconRect);
            canvas.drawBitmap(icon, null, tmpRect, iconPaint);

            if (label != null && !label.isEmpty()) {
                canvas.drawText(label, 0f, iconRect.bottom + textGap, textPaint);
            }

            canvas.restore();
        }
    }
}
