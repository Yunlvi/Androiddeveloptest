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
    /** 单按钮时的按钮宽度。 */
    private static final float CLOSE_BUTTON_WIDTH_DP = 140f;
    /** 双按钮时每个按钮的宽度，与单按钮保持一致。 */
    private static final float DOUBLE_BUTTON_WIDTH_DP = 132f;
    /** 双按钮之间的间距。 */
    private static final float BUTTON_GAP_DP = 28f;
    /** 关闭按钮圆角半径（高度的一半 = 胶囊形）。 */
    private static final float CLOSE_BUTTON_RADIUS_DP = 25f;
    /** 按钮与挖槽边缘的透明间隔（单按钮）。 */
    private static final float NOTCH_GAP_DP = 4f;
    /** 双按钮时用更小的间隔，避免两孔整体显得比单按钮更大。 */
    private static final float DOUBLE_NOTCH_GAP_DP = 4f;
    /** 面板右边外扩量（px），用于对齐橙条右边缘。
     *  关闭按钮必须使用相同的值，否则与挖槽错位。 */
    private static final int PANEL_RIGHT_EXTEND_PX = -7;

    private final TextView tvTitle;
    /** 底部按钮容器，内含取消与确认按钮。 */
    private final LinearLayout btnBar;

    /** 面板背景，切换按钮数量时需要重设挖槽宽度。 */
    private AsymmetricRoundDrawable panelBg;
    /** 底部胶囊按钮内的图标与文案。 */
    private final android.widget.ImageView tvButtonIcon;
    private final TextView tvButtonLabel;
    private final FrameLayout panel;
    private final LinearLayout contentContainer;

    /** 关闭回调，由 PopupManager 注入。 */
    private Runnable onCloseListener;

    /** 触摸拦截层（被锁定时盖在弹窗上，消费全部触摸）。 */
    private View touchBlocker;

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
        btnBar = findViewById(R.id.btnBar);
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

        // 面板底部中央挖缺口。默认单按钮，宽度按单个按钮计算；
        // 若外部切换为双按钮模式，会重新计算挖槽
        this.panelBg = panelBg;
        applyNotchFor(1);
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

    /** 图标已固定为勾，此处保留接口以便外部按需调整颜色。 */
    void setButtonIcon(int tintColor) {
        if (tvButtonIcon != null) {
            tvButtonIcon.setColorFilter(tintColor);
        }
    }

    /**
     * 切换为双按钮模式：左侧「取消」、右侧「确认」。
     *
     * <p>两个按钮并排居中，挖槽宽度同步扩大为容纳两个按钮。
     *
     * @param confirmText 右侧按钮文案，如"确认"
     * @param onConfirm   右侧按钮点击
     * @param onCancel    左侧按钮点击（通常为关闭弹窗，由调用方传入）
     */
    void setDoubleButtons(String confirmText, Runnable onConfirm, Runnable onCancel) {
        View btnCancel = findViewById(R.id.btnCancel);
        View btnClose = findViewById(R.id.btnClose);
        if (btnCancel == null || btnClose == null || btnBar == null) return;

        // 按钮变窄，并排摆放
        LinearLayout.LayoutParams closeLp =
                (LinearLayout.LayoutParams) btnClose.getLayoutParams();
        closeLp.width = dp((int) DOUBLE_BUTTON_WIDTH_DP);
        btnClose.setLayoutParams(closeLp);

        LinearLayout.LayoutParams cancelLp =
                (LinearLayout.LayoutParams) btnCancel.getLayoutParams();
        cancelLp.width = dp((int) DOUBLE_BUTTON_WIDTH_DP);
        cancelLp.setMarginEnd(dp((int) BUTTON_GAP_DP));
        btnCancel.setLayoutParams(cancelLp);
        btnCancel.setVisibility(View.VISIBLE);

        // 挖槽扩宽以容纳两个按钮
        applyNotchFor(2);

        // 右侧按钮承担确认，改用橙黄配色呼应顶部装饰条
        btnClose.setBackgroundResource(R.drawable.bg_popup_confirm);
        // 图标与文字在橙黄底上统一用白色
        setButtonIcon(0xFFFFFFFF);
        if (tvButtonLabel != null) {
            tvButtonLabel.setTextColor(0xFFFFFFFF);
            tvButtonLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        setButtonText(confirmText == null ? "\u786e\u8ba4" : confirmText);
        this.onCloseListener = onConfirm;

        // 左侧按钮：取消
        btnCancel.setOnClickListener(v -> {
            if (onCancel != null) {
                onCancel.run();
            }
        });
    }

    /**
     * 按按钮数量重算底部挖槽。
     *
     * @param count 1 = 单按钮，2 = 双按钮（含中间间距）
     */
    private void applyNotchFor(int count) {
        if (panelBg == null) return;

        float density = getResources().getDisplayMetrics().density;
        float btnW = count == 2 ? DOUBLE_BUTTON_WIDTH_DP : CLOSE_BUTTON_WIDTH_DP;

        // 双按钮：挖两个独立的孔，各对齐一个按钮的中心；
        // 使用更小的 gap，避免两个孔整体显得比单按钮更"胖"
        float gapDp = count == 2 ? DOUBLE_NOTCH_GAP_DP : NOTCH_GAP_DP;

        float[] centers = null;
        if (count == 2) {
            float halfStep = (btnW + BUTTON_GAP_DP) / 2f;
            centers = new float[]{-halfStep * density, halfStep * density};
        }

        panelBg.setBottomNotchByButton(
                btnW * density,
                CLOSE_BUTTON_HALF_DP * 2 * density,
                CLOSE_BUTTON_RADIUS_DP * density,
                gapDp * density,
                (CLOSE_BUTTON_HALF_DP + gapDp) * density,
                centers);
        panel.invalidate();
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
}
