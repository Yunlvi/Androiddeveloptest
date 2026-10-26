package com.example.androiddeveloptest;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * 单个详情弹窗。
 *
 * <p>结构自上而下：橙色装饰条 → 6dp 透明间隔 → 白色内容面板。
 * 关闭按钮由布局的 {@code gravity=bottom} + 根布局 {@code paddingBottom=25dp} 定位，
 * 使其中心正好落在白色面板底边上，形成"上半嵌入、下半探出"的挖孔效果。
 */
public class PopupView extends FrameLayout {

    /** 面板上方圆角（直角）。 */
    private static final float PANEL_CORNER_TOP_DP = 0f;
    /** 面板左下圆角（与橙条右上斜切圆角对角呼应）。 */
    private static final float PANEL_CORNER_BOTTOM_LEFT_DP = 24f;
    /** 面板右下圆角（直角）。 */
    private static final float PANEL_CORNER_BOTTOM_RIGHT_DP = 0f;
    /** 关闭按钮高度的一半，用于定位到面板底边。 */
    private static final float CLOSE_BUTTON_HALF_DP = 25f;
    /** 关闭按钮宽度。 */
    private static final float CLOSE_BUTTON_WIDTH_DP = 140f;
    /** 关闭按钮圆角半径（高度的一半 = 胶囊形）。 */
    private static final float CLOSE_BUTTON_RADIUS_DP = 25f;
    /** 按钮与挖槽边缘的透明间隔。 */
    private static final float NOTCH_GAP_DP = 8f;
    /** 面板右边外扩量（px），用于对齐橙条右边缘。
     *  关闭按钮必须使用相同的值，否则与挖槽错位。 */
    private static final int PANEL_RIGHT_EXTEND_PX = -7;

    private final TextView tvTitle;
    /** 底部胶囊按钮内的图标与文案。 */
    private final TextView tvButtonIcon;
    private final TextView tvButtonLabel;
    private final FrameLayout panel;
    private final LinearLayout contentContainer;

    /** 关闭回调，由 PopupManager 注入。 */
    private Runnable onCloseListener;

    /** 触摸拦截层（被锁定时盖在弹窗上，消费全部触摸）。 */
    private View touchBlocker;

    /** 相对初始位置（贴底）的垂直偏移（px，正值向上）。 */
    private float stackOffset;

    /** 首次测量后的稳定高度（px）。getHeight() 在某些时机会返回异常值，用此缓存。 */
    private int cachedHeight = -1;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // 关闭按钮用负 marginBottom 探出面板底边，
        // 测量时该负值会让高度少掉探出量，这里加回来：
        // 真实视觉高度 = 测量高度 + 25dp（探出的一半）
        int h = getMeasuredHeight() + (int) (25 * getResources().getDisplayMetrics().density);
        if (cachedHeight <= 0 && h > 0 && h < 2000) {
            cachedHeight = h;
        }
    }

    /** 稳定高度：优先用缓存，异常时退回 getHeight()。 */
    int getStableHeight() {
        return cachedHeight > 0 ? cachedHeight : getHeight();
    }

    /** 获取触摸拦截层。 */
    View getTouchBlocker() {
        return touchBlocker;
    }

    /** 设置触摸拦截层。 */
    void setTouchBlocker(View blocker) {
        this.touchBlocker = blocker;
    }

    /** 获取/设置堆叠偏移（正值向上）。 */
    float getStackOffset() {
        return stackOffset;
    }

    void setStackOffset(float offset) {
        this.stackOffset = offset;
    }

    public PopupView(@NonNull Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.popup_base, this, true);

        // 关闭按钮的下半探出面板、落进根布局的 paddingBottom 区域，
        // 必须关闭裁切，否则探出部分会被截断
        setClipChildren(false);
        setClipToPadding(false);

        tvTitle = findViewById(R.id.tvTitle);
        tvButtonIcon = findViewById(R.id.btnCloseIcon);
        tvButtonLabel = findViewById(R.id.btnCloseLabel);
        panel = findViewById(R.id.panel);
        contentContainer = findViewById(R.id.contentContainer);
        View btnClose = findViewById(R.id.btnClose);

        float density = getResources().getDisplayMetrics().density;

        // 关闭按钮探出面板底边（负 marginBottom），必须关闭面板裁切
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        // 面板四角独立圆角：右上、右下为小圆角，左下为大圆角（对角呼应橙条右上）
        AsymmetricRoundDrawable panelBg = new AsymmetricRoundDrawable(
                Color.WHITE,
                PANEL_CORNER_TOP_DP, PANEL_CORNER_TOP_DP,
                PANEL_CORNER_BOTTOM_RIGHT_DP, PANEL_CORNER_BOTTOM_LEFT_DP,
                density);

        // 面板底部中央挖"放大版按钮形状"的缺口：
        // 挖槽 = 按钮轮廓向外扩 NOTCH_GAP_DP，圆角同步放大，轮廓处处等距
        panelBg.setBottomNotchByButton(
                CLOSE_BUTTON_WIDTH_DP * density,
                CLOSE_BUTTON_HALF_DP * 2 * density,
                CLOSE_BUTTON_RADIUS_DP * density,
                NOTCH_GAP_DP * density,
                (CLOSE_BUTTON_HALF_DP + NOTCH_GAP_DP) * density);

        panel.setBackground(panelBg);

        // 面板右边外扩：用面板去对齐橙条右边缘
        // 注意：LayoutParams 的类型由父容器决定（这里是 LinearLayout），
        // 不能强转，必须用父容器对应类型的参数
        LinearLayout.LayoutParams panelLp =
                (LinearLayout.LayoutParams) panel.getLayoutParams();
        panelLp.rightMargin = PANEL_RIGHT_EXTEND_PX;
        panel.setLayoutParams(panelLp);

        // 内容底部预留挖槽嵌入深度 + 余量，防止内容"掉进"透明孔里
        int contentBottomPadding = (int) ((CLOSE_BUTTON_HALF_DP + NOTCH_GAP_DP + 8) * density);
        contentContainer.setPadding(
                contentContainer.getPaddingLeft(),
                contentContainer.getPaddingTop(),
                contentContainer.getPaddingRight(),
                contentBottomPadding);

        // 同步阴影轮廓，挖槽区域不渲染阴影
        panelBg.applyAsOutline(panel);
        // API 30 以下 outline 无法表达凹形（挖槽），阴影会按完整矩形渲染，
        // 在挖槽区域产生一条横线残影 → 关闭 elevation，浮起感用描边代替
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            panel.setElevation(dp(3));
        } else {
            panel.setElevation(0);
        }

        btnClose.setOnClickListener(v -> {
            if (onCloseListener != null) {
                onCloseListener.run();
            }
        });
    }

    /** 设置关闭按钮的回调。 */
    void setOnCloseListener(Runnable listener) {
        this.onCloseListener = listener;
    }

    /**
     * 设置底部胶囊按钮的文案。
     *
     * <p>用于"用户填完只会点确认"的场景：把「关闭」改成「确定」，
     * 点击即执行自定义动作，而不是单纯关窗。
     */
    void setButtonText(String text) {
        if (tvButtonLabel != null) {
            tvButtonLabel.setText(text);
        }
    }

    /** 设置底部胶囊按钮左侧的图标（如 × 或 ✓）。 */
    void setButtonIcon(String icon) {
        if (tvButtonIcon != null) {
            tvButtonIcon.setText(icon);
        }
    }

    /** 设置标题。 */
    public void setTitle(String title) {
        tvTitle.setText(title);
    }

    /** 往内容面板里添加内容视图。 */
    public void setContent(View content) {
        contentContainer.removeAllViews();
        if (content != null) {
            contentContainer.addView(content);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** 内容容器，供外部继续往里加子视图。 */
    public LinearLayout getContentContainer() {
        return contentContainer;
    }
}
