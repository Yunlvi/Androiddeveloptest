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
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.os.Handler;

import androidx.annotation.Nullable;


public class CurvedNavBar extends View {

    // ===== 画笔与路径（提成成员变量，不要放 onDraw 里 new）=====
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arcPath = new Path();
    private final RectF arcRect = new RectF();

    // ===== 几何参数，在 onSizeChanged 里算 =====
    private float cx, cy;        // 圆心
    private float radius;        // 中心线半径
    private float thickness;     // 弧带厚度
    private float startAngle;    // 起始角
    private static final float SWEEP = 67.8f;   // 张角（扫描实测值）

    // ===== 导航项 =====
    private static final int ITEM_COUNT = 5;     // 5 个图标

    // ===== 选中状态 =====
    private int selectedIndex = 2;              // 默认选中中间（index 2）

    // ===== 光晕 =====
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float glowRadius;                   // 光晕半径（常量，由中间图标算出）
    private float glowX, glowY;                 // 光晕中心（可变）
    private float glowAngle;                    // 光晕在弧线上的角度（动画/拖动用）

    private static final int COLOR_SELECTED   = 0xFFFFC107;  // 琥珀黄
    private static final int COLOR_UNSELECTED = 0xFFFFFFFF;  // 白

    // 颜色滤镜（预先创建，避免 onDraw 里反复 new）
    private PorterDuffColorFilter filterSelected;
    private PorterDuffColorFilter filterUnselected;

    // 整体垂直微调（负值上移，正值下移），单位 px
    private float verticalAdjust;                       // 只声明，不给初值

    private final Path arcClipPath = new Path();   // 用于裁剪的"实心"弧带

    // ===== 导航项 =====
    private final NavItem[] items = new NavItem[ITEM_COUNT];

    // 图标与文字的画笔
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator glowAnimator;

    // ===== 按压反馈 =====
    private int pressedIndex = -1;              // 当前按下的 index（-1 = 无）
    private float pressScale = 1f;              // 缩放系数
    private ValueAnimator scaleAnimator;        // 缩放动画

    // ===== 长按拖动 =====
    private static final long LONG_PRESS_MS = 400;   // 长按阈值
    private boolean isDragging = false;              // 是否处于拖动模式
    private float dragStartX;                        // 按下时的 x
    private float dragStartAngle;                    // 按下时的光晕角度
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * 选中项变化回调
     */
    public interface OnItemSelectedListener {
        /** 选中项确定变化（点击后 / 拖动进入新区间） */
        void onItemSelected(int index);

        /** 拖动中的连续进度（0 ~ ITEM_COUNT-1），用于页面跟手 */
        void onProgressChanged(float progress);
    }

    private OnItemSelectedListener listener;

    public void setOnItemSelectedListener(OnItemSelectedListener l) {
        this.listener = l;
    }

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            isDragging = true;
            dragStartX = lastDownX;
            dragStartAngle = glowAngle;

            // 直接复位，不用动画（长按瞬间恢复，避免"先缩再弹"的突兀）
            if (scaleAnimator != null && scaleAnimator.isRunning()) {
                scaleAnimator.cancel();
            }
            pressedIndex = -1;
            pressScale = 1f;
            invalidate();

            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();

            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    };

    private float lastDownX;                         // 记录按下位置（给 runnable 用）

    /**
     * 单个导航项：图标 + 文字
     * 封装了它自己的位置、旋转、绘制逻辑
     */
    private static class NavItem {
        float angle;                    // 在弧线上的角度
        float centerX, centerY;         // 图标中心坐标
        int iconRes;                    // 图标资源 id
        String label;                   // 文字

        Bitmap icon;                    // 加载后的位图
        final RectF iconRect = new RectF();   // 图标绘制矩形（相对中心，未旋转）
        final RectF tmpRect = new RectF();    // 绘制时复用

        boolean selected;               // 是否选中



        NavItem(int iconRes, String label) {
            this.iconRes = iconRes;
            this.label = label;
        }
        /**
         * 绘制这个导航项（图标 + 文字）
         *
         * @param canvas    画布
         * @param iconPaint 图标画笔（已设置好 colorFilter）
         * @param textPaint 文字画笔
         * @param textGap   文字与图标之间的间距
         */
        void draw(Canvas canvas, Paint iconPaint, Paint textPaint,
                  float textGap, float yOffset, float scale) {
            if (icon == null) return;

            // ===== 1. 计算旋转角 =====
            float rotation = angle + 90f;      // 垂直于弧线

            // ===== 2. 回正纠正：旋转角落在 (90, 270) 内时多转 180° =====
            float r = rotation % 360f;
            if (r < 0) r += 360f;
            boolean flipped = (r > 90f && r < 270f);
            float finalRotate = flipped ? rotation + 180f : rotation;

            // ===== 3. 变换画布：平移 → 旋转 → 缩放 =====
            canvas.save();
            canvas.translate(centerX, centerY + yOffset);
            canvas.rotate(finalRotate);
            canvas.scale(scale, scale);        // ★ 图标和文字一起缩放

            // ===== 4. 画图标（居中在原点）=====
            tmpRect.set(iconRect);
            canvas.drawBitmap(icon, null, tmpRect, iconPaint);

            // ===== 5. 画文字（在图标下方）=====
            if (label != null && !label.isEmpty()) {
                float textY = iconRect.bottom + textGap;
                canvas.drawText(label, 0f, textY, textPaint);
            }

            canvas.restore();
        }
    }


    // ===== 构造方法：三个都要写 =====
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
        arcPaint.setColor(0xFF16506B);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeJoin(Paint.Join.ROUND);

        // 颜色滤镜：SRC_IN 保留透明度，只换颜色
        filterSelected = new PorterDuffColorFilter(COLOR_SELECTED, PorterDuff.Mode.SRC_IN);
        filterUnselected = new PorterDuffColorFilter(COLOR_UNSELECTED, PorterDuff.Mode.SRC_IN);

        // 文字画笔
        textPaint.setColor(COLOR_UNSELECTED);
        textPaint.setTextAlign(Paint.Align.CENTER);   // 以中心对齐绘制
        textPaint.setTextSize(30f);                    // 先用固定值，后面按尺寸调整

        // 创建 5 个导航项（顺序 = 从左到右）
        items[0] = new NavItem(R.drawable.ic_tools,      "工具");
        items[1] = new NavItem(R.drawable.ic_statistics, "统计");
        items[2] = new NavItem(R.drawable.ic_home,       "主页");
        items[3] = new NavItem(R.drawable.ic_bill,       "账单");
        items[4] = new NavItem(R.drawable.ic_person,     "我的");

        // 加载图标
        for (NavItem item : items) {
            item.icon = BitmapFactory.decodeResource(getResources(), item.iconRes);
        }
    }

    /**
     * 把一个角度转换成弧线上的坐标
     * 这是"极坐标 → 直角坐标"的换算，图标和光晕都用它
     *
     * @param angleDeg 角度（度），0°=右，90°=下，顺时针为正
     * @return 长度为 2 的数组：[x, y]
     */
    private float[] pointOnArc(float angleDeg) {
        double rad = Math.toRadians(angleDeg);
        float x = cx + radius * (float) Math.cos(rad);
        float y = cy + radius * (float) Math.sin(rad);
        return new float[]{x, y};
    }

    private void updateGlowPosition() {
        float[] p = pointOnArc(glowAngle);
        glowX = p[0];
        glowY = p[1];
    }

    /**
     * 把光晕动画移动到第 index 个图标
     */
    public void animateGlowTo(int index) {
        if (index < 0 || index >= ITEM_COUNT) return;

        // 取消正在跑的动画，防止连续点击时打架
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

        glowAnimator.setDuration(300);
        glowAnimator.setInterpolator(new DecelerateInterpolator());
        glowAnimator.addUpdateListener(animation -> {
            glowAngle = (float) animation.getAnimatedValue();
            updateGlowPosition();
            invalidate();
        });
        glowAnimator.start();

        // ★ 通知外部：页面滑到目标页（吸附时也需要归位）
        if (listener != null) {
            listener.onItemSelected(index);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // ==================== 1. 弧带几何 ====================
        // 左右间距：弦长 = 宽度的 77%
        float chord = w * 0.77f;

        // 半径：控制弯度（R/弦长 = 0.896，来自原图像素扫描）
        radius = chord * 0.896f;

        // 厚度：弧带粗细（厚度/弦长 = 0.183）
        thickness = chord * 0.183f;
        arcPaint.setStrokeWidth(thickness);

        // 圆心水平居中
        cx = w / 2f;


        // 圆心纵坐标：由"中心线最低点"倒推，保证弧带底部不超出控件
        float bottomMargin = h * 0.16f;
        float lowestCenter = h - bottomMargin - thickness / 2f;
        cy = lowestCenter - radius;

        // 弧带的外接矩形（定义这个圆）
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 起始角：让弧对称于竖直中线（90° 是正下方）
        startAngle = 90f - SWEEP / 2f;

        // 重建描边路径
        arcPath.reset();
        arcPath.addArc(arcRect, startAngle, SWEEP);

        // ==================== 2. 裁剪用的"实心弧带"路径 ====================
        float rOuter = radius + thickness / 2f;
        float rInner = radius - thickness / 2f;

        RectF outerRect = new RectF(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter);
        RectF innerRect = new RectF(cx - rInner, cy - rInner, cx + rInner, cy + rInner);

        arcClipPath.reset();

        // ① 外弧
        arcClipPath.addArc(outerRect, startAngle, SWEEP);

        // ② 右端帽（从外弧终点扫 180° 到内弧起点）
        float endRad = (float) Math.toRadians(startAngle + SWEEP);
        float endCx = cx + radius * (float) Math.cos(endRad);
        float endCy = cy + radius * (float) Math.sin(endRad);
        arcClipPath.arcTo(
                new RectF(endCx - thickness / 2f, endCy - thickness / 2f,
                        endCx + thickness / 2f, endCy + thickness / 2f),
                startAngle + SWEEP, 180f, false);

        // ③ 内弧（反向扫回）
        arcClipPath.addArc(innerRect, startAngle + SWEEP, -SWEEP);

        // ④ 左端帽（从内侧起，扫到外侧）
        float startRad = (float) Math.toRadians(startAngle);
        float startCx = cx + radius * (float) Math.cos(startRad);
        float startCy = cy + radius * (float) Math.sin(startRad);
        arcClipPath.arcTo(
                new RectF(startCx - thickness / 2f, startCy - thickness / 2f,
                        startCx + thickness / 2f, startCy + thickness / 2f),
                startAngle + 180f, 180f, false);

        arcClipPath.close();

        // ==================== 3. 图标分布区间 ====================
        // 端帽占据的角度
        float capAngle = (float) Math.toDegrees(
                Math.atan((thickness / 2f) / radius));

        // 留出 1.2 倍端帽角度作为边距，避免图标压到端帽上
        float spreadSweep = SWEEP - capAngle * 1.2f;
        float spreadStart = 90f - spreadSweep / 2f;

        // ==================== 4. 逐个更新导航项 ====================
        float iconSize = thickness * 0.63f;      // 图标边长
        float textSize = thickness * 0.16f;      // 文字大小
        textPaint.setTextSize(textSize);

        // 图标矩形半边长（相对中心，负数表示向左/上）
        float half = iconSize / 2f;

        for (int i = 0; i < ITEM_COUNT; i++) {
            NavItem item = items[i];

            // 角度：5 个等分点（i/(n-1) 让首尾正好在区间两端）
            float a = spreadStart + spreadSweep * (ITEM_COUNT - 1 - i) / (ITEM_COUNT - 1);
            item.angle = a;

            // 坐标
            float[] p = pointOnArc(a);
            item.centerX = p[0];
            item.centerY = p[1];

            // 图标矩形：以 item 中心为原点
            item.iconRect.set(-half, -half, half, half);

            // 选中状态
            item.selected = (i == selectedIndex);
        }

        // ==================== 5. 光晕 ====================
        // 半径：中间图标中心 → 控件顶部中点 (w/2, 0)
        int mid = ITEM_COUNT / 2;
        float dx0 = items[mid].centerX - w / 2f;
        float dy0 = items[mid].centerY;
        glowRadius = (float) Math.hypot(dx0, dy0) * 0.6f;

        // 光晕中心初始在中间图标上
        glowAngle = items[mid].angle;
        updateGlowPosition();

        verticalAdjust = -thickness * 0.08f;   // 负值 = 上移
    }


    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. 弧带（最底层）
        canvas.drawPath(arcPath, arcPaint);

        // 2. 光晕（在弧带之上，图标之下）
        drawGlow(canvas);

        // 3. 所有导航项（图标 + 文字，各自处理旋转）
        float textGap = thickness * 0.14f;
        for (int i = 0; i < ITEM_COUNT; i++) {
            NavItem item = items[i];
            iconPaint.setColorFilter(item.selected ? filterSelected : filterUnselected);
            textPaint.setColor(item.selected ? COLOR_SELECTED : COLOR_UNSELECTED);

            // 只有被按下的那个才缩放
            float scale = (i == pressedIndex) ? pressScale : 1f;
            item.draw(canvas, iconPaint, textPaint, textGap, verticalAdjust, scale);
        }
        iconPaint.setColorFilter(null);
    }

    /**
     * 判断某个角度落在第几个导航项（最近邻）
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
     * 把光晕角度换算成页面进度（0 ~ ITEM_COUNT-1）
     *
     * 注意：items[0] 角度最大（最左），items[4] 角度最小（最右）
     * 而 progress 0 = 最左页，所以要做反向映射
     */
    private float angleToProgress(float angleDeg) {
        float maxAngle = items[0].angle;                 // 最左（角度最大）
        float minAngle = items[ITEM_COUNT - 1].angle;    // 最右（角度最小）

        if (maxAngle - minAngle <= 0.001f) return 0f;    // 防除零

        // 归一化到 [0, 1]
        float t = (maxAngle - angleDeg) / (maxAngle - minAngle);
        t = Math.max(0f, Math.min(1f, t));

        // 映射到 [0, ITEM_COUNT-1]
        return t * (ITEM_COUNT - 1);
    }

    /**
     * 按下/回弹的缩放动画
     *
     * @param down true = 缩小（按下），false = 弹回（松手）
     */
    private void animatePress(boolean down) {
        if (scaleAnimator != null && scaleAnimator.isRunning()) {
            scaleAnimator.cancel();
        }

        float from = pressScale;
        float to = down ? 0.85f : 1f;

        scaleAnimator = ValueAnimator.ofFloat(from, to);
        scaleAnimator.setDuration(down ? 120 : 200);
        scaleAnimator.setInterpolator(down
                ? new DecelerateInterpolator()
                : new OvershootInterpolator(1.3f));

        scaleAnimator.addUpdateListener(a -> {
            pressScale = (float) a.getAnimatedValue();
            invalidate();
        });
        scaleAnimator.start();
    }
    private void drawGlow(Canvas canvas) {
        RadialGradient shader = new RadialGradient(
                glowX, glowY, glowRadius,
                new int[]{
                        0xFFFFF59D,   // 中心：淡黄
                        0x8FFFEB3B,   // 中段：明黄，56% 不透明
                        0x00FFEB3B    // 边缘：全透明
                },
                new float[]{0f, 0.35f, 1f},
                Shader.TileMode.CLAMP
        );
        glowPaint.setShader(shader);

        // ★ 裁剪到弧带形状内部：save → clipPath → draw → restore
        canvas.save();
        canvas.clipPath(arcClipPath);
        canvas.drawCircle(glowX, glowY, glowRadius, glowPaint);
        canvas.restore();       // 少了这行，图标也会被裁掉
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN: {
                lastDownX = event.getX();

                float dx = event.getX() - cx;
                float dy = event.getY() - cy;
                float r = (float) Math.hypot(dx, dy);
                float halfThick = thickness / 2f;

                // 点在弧带外 → 不消费，但长按仍可能生效（后面通过 Activity 转发）
                if (r < radius - halfThick || r > radius + halfThick) {
                    return false;
                }

                float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
                pressedIndex = angleToIndex(angle);

                animatePress(true);                          // 缩小反馈
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);   // 启动长按计时
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (!isDragging) return true;

                // Δx → Δθ
                float dx = event.getX() - dragStartX;
                float deltaDeg = (float) Math.toDegrees( -dx / radius);
                float newAngle = dragStartAngle + deltaDeg;

                // 钳制在图标分布区间内
                float minAngle = items[ITEM_COUNT - 1].angle;   // 角度最小 = 最右
                float maxAngle = items[0].angle;                // 角度最大 = 最左
                newAngle = Math.max(minAngle, Math.min(maxAngle, newAngle));

                glowAngle = newAngle;
                updateGlowPosition();

                // 判定当前落在哪个 item，变了就切
                int idx = angleToIndex(glowAngle);
                if (idx != selectedIndex) {
                    applySelection(idx, false);
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPressRunnable);

                if (isDragging) {
                    // ★ 拖动结束：吸附到最近的图标
                    isDragging = false;
                    snapToNearest();
                } else if (pressedIndex >= 0) {
                    animatePress(false);
                    animateGlowTo(pressedIndex);      // ★ 无条件调用
                }
                pressedIndex = -1;
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPressRunnable);
                boolean wasDragging = isDragging;
                isDragging = false;
                if (wasDragging) {
                    snapToNearest();          // ★ 中途取消也吸附
                } else {
                    animatePress(false);
                }
                pressedIndex = -1;
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    /**
     * 拖动松手时，吸附到离光晕最近的图标
     */
    private void snapToNearest() {
        int nearest = angleToIndex(glowAngle);

        // 复用点击的动画：滑过去 + 更新选中态 + 触发回调
        animateGlowTo(nearest);
    }

    /**
     * 立即选中某个 item（无动画）
     * 拖动时用这个，因为光晕位置已经由手指决定了
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

    // ===== 全局触摸（由 Activity 转发）=====
    private boolean globalTracking = false;   // Activity 是否已通知按下

    /**
     * Activity 在 ACTION_DOWN 时调用，开始监听长按
     */
    public void onGlobalDown(float x, float y) {
        lastDownX = x;
        globalTracking = true;

        if (!isDragging) {
            handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
        }
    }

    /**
     * Activity 在每个事件时调用，返回 true 表示导航栏接管
     */
    public boolean onGlobalTouch(MotionEvent ev) {
        if (!isDragging) {
            // 还没进入拖动模式 → 不接管
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(longPressRunnable);
                globalTracking = false;
            }
            return false;
        }

        // 已进入拖动模式 → 接管所有事件
        handleDragEvent(ev);
        return true;
    }

    /**
     * 是否处于全局拖动中
     */
    public boolean isGlobalDragging() {
        return isDragging;
    }

    /**
     * 取消全局监听（长按判定失败时）
     */
    public void cancelGlobalTracking() {
        handler.removeCallbacks(longPressRunnable);
        globalTracking = false;
    }

    /**
     * 处理拖动中的事件（MOVE / UP / CANCEL）
     */
    private void handleDragEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - dragStartX;
                float deltaDeg = (float) Math.toDegrees(-dx / radius);
                float newAngle = dragStartAngle + deltaDeg;

                float minAngle = items[ITEM_COUNT - 1].angle;
                float maxAngle = items[0].angle;
                newAngle = Math.max(minAngle, Math.min(maxAngle, newAngle));

                glowAngle = newAngle;
                updateGlowPosition();

                int idx = angleToIndex(glowAngle);
                if (idx != selectedIndex) {
                    applySelection(idx, false);
                }
                // ★ 通知外部：页面跟手
                if (listener != null) {
                    listener.onProgressChanged(angleToProgress(glowAngle));
                }
                invalidate();
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                isDragging = false;
                globalTracking = false;
                snapToNearest();
                break;
            }
        }
    }


}