package com.example.androiddeveloptest;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

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
    private float padAngle = 0f;                 // 两端各让出的角度（避开端帽）

    // ===== 选中状态 =====
    private int selectedIndex = 2;              // 默认选中中间（index 2）

    // ===== 光晕 =====
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float glowRadius;                   // 光晕半径（常量，由中间图标算出）
    private float glowX, glowY;                 // 光晕中心（可变）
    private float glowAngle;                    // 光晕在弧线上的角度（动画/拖动用）

    private static final int COLOR_SELECTED   = 0xFFFFC107;  // 琥珀黄
    private static final int COLOR_UNSELECTED = 0xFFFFFFFF;  // 白

    // 圆形画笔（临时替代图标，验证位置用）
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path arcClipPath = new Path();   // 用于裁剪的"实心"弧带

    // 缓存每个图标的位置，避免重复计算
    private final float[] itemX = new float[ITEM_COUNT];
    private final float[] itemY = new float[ITEM_COUNT];
    private final float[] itemAngle = new float[ITEM_COUNT];

    private ValueAnimator glowAnimator;


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
        arcPaint.setStyle(Paint.Style.STROKE);   // 描边模式
        arcPaint.setColor(0xFF16506B);           // 深蓝
        arcPaint.setStrokeCap(Paint.Cap.ROUND);  // ★ 两头变圆
        arcPaint.setStrokeJoin(Paint.Join.ROUND);
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
        float to = itemAngle[index];

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
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // 左右间距
        float chord = w * 0.77f;

        // 半径：控制"弯度"。这个 0.896 来自对你那张图的像素扫描（R/弦长 = 0.896）
        radius = chord * 0.896f;

        // 厚度：弧带有多粗，同样来自扫描（厚度/弦长 = 0.183）
        thickness = chord * 0.183f;
        arcPaint.setStrokeWidth(thickness);

        // 圆心：水平居中
        cx = w / 2f;

        // 圆心纵坐标：要向上偏移，弧才会"向上开口"
        // 注意这个值可能是负数，圆心跑到控件外面是正常的（下面解释）
        float bottomMargin = h * 0.16f;              // 底部间距
        float lowestCenter = h - bottomMargin - thickness / 2f;  // 中心线最低处
        cy = lowestCenter - radius;                  // ★ 倒推圆心

        // addArc 需要一个"圆的外接矩形"来定义这个圆
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 起始角：让弧对称于竖直中线（90° 是正下方）
        startAngle = 90f - SWEEP / 2f;

        // 重建路径（reset 是必须的，否则会不断往旧路径上叠加）
        arcPath.reset();
        arcPath.addArc(arcRect, startAngle, SWEEP);

        // ===== 构造用于裁剪的"实心弧带"路径 =====
        // 外弧半径 = radius + thickness/2
        // 内弧半径 = radius - thickness/2
        float rOuter = radius + thickness / 2f;
        float rInner = radius - thickness / 2f;

        RectF outerRect = new RectF(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter);
        RectF innerRect = new RectF(cx - rInner, cy - rInner, cx + rInner, cy + rInner);

        arcClipPath.reset();
        // ① 外弧（从左端顺时针扫到右端）
        arcClipPath.addArc(outerRect, startAngle, SWEEP);

        // ② 右端帽：从"外弧终点"起，沿径向向外→扫 180° 到"内弧起点"
        // 端帽圆心就是中心线的右端点
        float endRad = (float) Math.toRadians(startAngle + SWEEP);
        float endCx = cx + radius * (float) Math.cos(endRad);
        float endCy = cy + radius * (float) Math.sin(endRad);
        // 端帽的起始角 = 从圆心指向"外弧终点"的方向（即向外径向）
        // 外弧终点角度就是 (startAngle + SWEEP)，因为它在同一条射线上
        float endCapStart = startAngle + SWEEP;
        arcClipPath.arcTo(
                new RectF(endCx - thickness / 2f, endCy - thickness / 2f,
                        endCx + thickness / 2f, endCy + thickness / 2f),
                endCapStart, 180f, false);

        // ③ 内弧（从右端反向扫回左端）
        arcClipPath.addArc(innerRect, startAngle + SWEEP, -SWEEP);

        // ④ 左端帽：同样的道理
        float startRad = (float) Math.toRadians(startAngle);
        float startCx = cx + radius * (float) Math.cos(startRad);
        float startCy = cy + radius * (float) Math.sin(startRad);
        float startCapStart = startAngle + 180f;   // ★ 从内侧起，扫到外侧
        arcClipPath.arcTo(
                new RectF(startCx - thickness / 2f, startCy - thickness / 2f,
                        startCx + thickness / 2f, startCy + thickness / 2f),
                startCapStart, 180f, false);

        arcClipPath.close();

        // ===== 计算 5 个图标的位置 =====
        // 目标：让点尽量靠向弧带两端，同时避开端帽
        // 做法：先算出端帽占据的角度，再按它留出边距（自适应，与厚度联动）
        float capAngle = (float) Math.toDegrees(
                Math.atan((thickness / 2f) / radius));   // 端帽占据的角度

        // 1.2f 是"安全系数"：大于 1 表示留的边距比端帽略宽一点
        float spreadSweep = SWEEP - capAngle * 1.2f;

        float spreadStart = 90f - spreadSweep / 2f;      // 对称分布

        for (int i = 0; i < ITEM_COUNT; i++) {
            float a = spreadStart + spreadSweep * i / (ITEM_COUNT - 1);
            itemAngle[i] = a;

            float[] p = pointOnArc(a);
            itemX[i] = p[0];
            itemY[i] = p[1];
        }


        // ===== 光晕半径：中间图标中心 → 控件顶部中点 (w/2, 0) =====
        int mid = ITEM_COUNT / 2;                    // 5 → 2
        float dx0 = itemX[mid] - w / 2f;
        float dy0 = itemY[mid] - 0f;
        glowRadius = (float) Math.hypot(dx0, dy0)* 0.6f;//光晕扩散距离

        // 光晕中心初始在中间图标上
        glowAngle = itemAngle[mid];
        updateGlowPosition();
    }


    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. 弧带（最底层）
        canvas.drawPath(arcPath, arcPaint);

        // 2. 光晕（在弧带之上，图标之下）
        drawGlow(canvas);

        // 3. 图标（最上层）
        float dotRadius = thickness * 0.22f;
        for (int i = 0; i < ITEM_COUNT; i++) {
            dotPaint.setColor(i == selectedIndex ? COLOR_SELECTED : COLOR_UNSELECTED);
            canvas.drawCircle(itemX[i], itemY[i], dotRadius, dotPaint);
        }
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


}