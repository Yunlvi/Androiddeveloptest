package com.example.androiddeveloptest;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 可嵌套的弹窗栈管理器。
 *
 * <p>层级规则：
 * <ul>
 *   <li>只有最上层弹窗可交互，下层完全吞掉触摸</li>
 *   <li>每压入一层，已存在的弹窗上移"新弹窗高度"并乘以 0.6 透明度</li>
 *   <li>上移不钳制，允许推出屏幕外</li>
 *   <li>遮罩只有一层，栈空时渐隐</li>
 * </ul>
 */
public class PopupManager {

    /** 每降一层的透明度系数。 */
    private static final float ALPHA_STEP = 0.6f;

    /** 弹窗宽度占屏幕比例。 */
    private static final float WIDTH_RATIO = 0.88f;

    /** 动画时长（毫秒）。 */
    private static final long DURATION = 260;

    /** 新弹窗底部距屏幕底部的距离。 */
    private static final int BOTTOM_MARGIN_DP = 24;

    /** 系统导航栏高度（px），运行时从 WindowInsets 获取，避免与手势条重叠。 */
    private int navBarInset = 0;

    /** 上一次 show 的时间戳，用于快速连点防抖。 */
    private long lastShowTime = 0;

    /** 旧弹窗上移时，在"新弹窗高度"之外额外让出的距离（含按钮探出补偿）。 */
    private static final int EXTRA_SHIFT_DP = 20;

    private final Activity activity;
    private final FrameLayout popupLayer;
    private final View mask;

    private final List<PopupView> stack = new ArrayList<>();
    private final float density;

    public PopupManager(@NonNull Activity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;

        Context ctx = activity;
        // 使用 decorView 而非 android.R.id.content：
        // content 内部还有一层布局，直接往里加视图容易与原有布局产生层级冲突。
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

        FrameLayout root = new FrameLayout(ctx);
        root.setClickable(false);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // 遮罩层：点击关闭最上层
        mask = new View(ctx);
        mask.setBackgroundColor(0x66000000);
        mask.setVisibility(View.GONE);
        mask.setOnClickListener(v -> dismissTop());
        root.addView(mask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 弹窗层：不拦截空白区域的触摸，交给下层
        popupLayer = new FrameLayout(ctx);
        popupLayer.setClipChildren(false);
        popupLayer.setClipToPadding(false);
        popupLayer.setClickable(false);
        popupLayer.setFocusable(false);
        root.addView(popupLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        decor.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 动态获取系统导航栏高度：弹窗贴底时避开手势条/导航栏
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            navBarInset = bars.bottom;
            return insets;
        });
    }

    // ==================================================================
    // 对外 API
    // ==================================================================

    /** 是否还有弹窗。 */
    public boolean isShowing() {
        return !stack.isEmpty();
    }

    /** 栈深度。 */
    public int depth() {
        return stack.size();
    }

    /**
     * 弹出一个新弹窗。
     *
     * @param title   标题文字
     * @param content 内容视图，可为 null
     * @return 新建的弹窗
     */
    /**
     * 弹出一个新弹窗，底部按钮显示为「确定」，点击后执行确认动作再关闭。
     *
     * <p>适用于用户填完只会点确认的表单类弹窗：
     * 与其摆一个多余的关闭按钮，不如让它直接承担确认职责。
     *
     * @param title      标题
     * @param content    内容视图
     * @param onConfirm  确认动作（先执行，成功后由调用方自行决定是否关闭）
     * @return 新建的弹窗；被防抖拦截时返回 null
     */
    public PopupView showConfirm(String title, View content, Runnable onConfirm) {
        PopupView popup = show(title, content);
        if (popup == null) return null;
        popup.setButtonIcon("\u2713");
        popup.setButtonText("\u786e\u5b9a");
        popup.setOnCloseListener(() -> {
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
        return popup;
    }

    public PopupView show(String title, View content) {
        // 防抖：快速连点时，第一次点击到弹窗显示之间遮罩尚未接管，
        // 第二次点击会穿透到页面内容再次触发 show，导致叠出重复弹窗
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastShowTime < 350) {
            android.util.Log.d("Popup", "show blocked by debounce");
            return null;
        }
        lastShowTime = now;

        final PopupView popup = new PopupView(activity);
        android.util.Log.d("Popup", "show: title=" + title + " stackBefore=" + stack.size());
        popup.setTitle(title);
        popup.setContent(content);
        popup.setOnCloseListener(this::dismissTop);

        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int width = (int) (screenWidth * WIDTH_RATIO);
        float density = activity.getResources().getDisplayMetrics().density;

        // 关键：弹窗贴屏幕底部，最新弹窗始终在最下方；
        // 旧弹窗通过上移让出空间。
        // bottomMargin 额外加上系统导航栏高度，避免与手势条/导航栏重叠。
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = (int) (BOTTOM_MARGIN_DP * density) + navBarInset;
        popupLayer.addView(popup, lp);

        // 先隐藏，等测量完成后播放进场动画
        popup.setVisibility(View.INVISIBLE);

        popup.post(() -> {
            int popupHeight = popup.getStableHeight();
            android.util.Log.d("Popup", "post: h=" + popupHeight + " vis=" + popup.getVisibility());
            if (popupHeight <= 0) {
                // 极端情况：布局尚未完成，直接显示不再动画
                popup.setVisibility(View.VISIBLE);
                stack.add(popup);
                showMaskIfNeeded();
                relayoutStack(null);
                return;
            }

            // ★ 高度锁定：把 wrap_content 替换为精确像素高度。
            // 此后任何子 View（如触摸拦截层）都无法改变弹窗的实际高度，
            // relayoutStack 的位置计算永远与实际渲染一致。
            ViewGroup.LayoutParams lockLp = popup.getLayoutParams();
            lockLp.height = popupHeight;
            popup.setLayoutParams(lockLp);

            stack.add(popup);
            relayoutStack(popup);
            showMaskIfNeeded();
        });

        return popup;
    }

    private void showMaskIfNeeded() {
        if (mask.getVisibility() != View.VISIBLE) {
            mask.setAlpha(0f);
            mask.setVisibility(View.VISIBLE);
            mask.animate().alpha(1f).setDuration(200).start();
        }
    }

    // ==================================================================
    // 全量重排：弹窗位置由"栈中深度"唯一决定
    // ==================================================================

    /**
     * 重新计算栈内所有弹窗的位置。
     *
     * <p>从最上层（栈顶）开始自底向上排：
     * <ul>
     *   <li>最上层弹窗贴底（bottomMargin）</li>
     *   <li>每往下一层，其底边 = 上一层的顶边 - 间隙</li>
     * </ul>
     *
     * @param newPopup 刚弹出的新弹窗（会从屏幕底部滑入）；null 表示只是重排旧弹窗
     */
    private void relayoutStack(PopupView newPopup) {
        int layerH = popupLayer.getHeight();
        if (layerH <= 0) return;

        int margin = (int) (BOTTOM_MARGIN_DP * density) + navBarInset;
        int gapPx = (int) (EXTRA_SHIFT_DP * density);

        // 从栈顶（最上层）向下排
        int cursorBottom = layerH - margin;   // 当前可用的底边
        android.util.Log.d("Popup", "relayout: stack=" + stack.size() + " layerH=" + layerH);

        for (int i = stack.size() - 1; i >= 0; i--) {
            PopupView p = stack.get(i);
            int h = p.getStableHeight();
            if (h <= 0) h = 200;

            int targetTop = cursorBottom - h;
            float translation = targetTop - (layerH - margin - h);

            android.util.Log.d("Popup", "  layer" + i + ": h=" + h
                    + " targetTop=" + targetTop
                    + " transY=" + translation
                    + " curTransY=" + p.getTranslationY());

            // 恢复可见性：show() 曾把新弹窗设为 INVISIBLE 等待测量，
            // 重排时必须切回 VISIBLE，否则 View 永远不绘制
            if (p.getVisibility() != View.VISIBLE) {
                p.setVisibility(View.VISIBLE);
            }

            if (p == newPopup) {
                // ===== 新弹窗：从屏幕底部滑入到目标位置 =====
                // 起点位置 = 目标位置 + 自身高度 + 底边距（完全在屏幕外下方）
                p.setTranslationY(translation + h + margin);
                p.animate()
                        .translationY(translation)
                        .alpha(1f)
                        .setDuration(DURATION)
                        .start();
            } else {
                // ===== 旧弹窗：平移到新目标位置 =====
                // alpha 由栈深决定：最上层恒为 1，其余 0.6
                // （关闭后恢复的弹窗也走这里，alpha 从 0.6 过渡到 1）
                p.animate()
                        .translationY(translation)
                        .alpha(i == stack.size() - 1 ? 1f : ALPHA_STEP)
                        .setDuration(DURATION)
                        .start();
            }

            // 锁定除最上层以外的全部弹窗
            if (i == stack.size() - 1) {
                unblockTouch(p);
            } else {
                blockTouch(p);
            }

            // 下一层（更旧的弹窗）的底边 = 本层顶边 - 间隙
            cursorBottom = targetTop - gapPx;
        }
    }

    /** 关闭最上层弹窗。 */
    public void dismissTop() {
        if (stack.isEmpty()) return;

        final PopupView top = stack.remove(stack.size() - 1);
        int topHeight = top.getStableHeight();
        if (topHeight <= 0) topHeight = 200;

        top.animate()
                .translationYBy(topHeight)
                .alpha(0f)
                .setDuration(DURATION)
                .withEndAction(() -> popupLayer.removeView(top))
                .start();

        // 解除关闭弹窗的锁定引用（视图即将移除）
        unblockTouch(top);

        // 剩余弹窗全量重排：回贴底或让出位置
        if (!stack.isEmpty()) {
            relayoutStack(null);
        } else {
            mask.animate().alpha(0f).setDuration(180)
                    .withEndAction(() -> mask.setVisibility(View.GONE))
                    .start();
        }
    }

    /** 关闭全部弹窗。 */
    public void dismissAll() {
        for (PopupView p : stack) {
            popupLayer.removeView(p);
        }
        stack.clear();
        mask.setVisibility(View.GONE);
    }
    /** 处理返回键。返回 true 表示已消费。 */
    public boolean handleBack() {
        if (!stack.isEmpty()) {
            dismissTop();
            return true;
        }
        return false;
    }

    // ==================================================================
    // 触摸锁定
    // ==================================================================

    /**
     * 禁止某个弹窗的所有交互。
     *
     * <p>关键：{@code setOnTouchListener} 只拦截落在 View 自身上的触摸，
     * 拦不住其内容子 View（按钮、输入框等）。
     * 因此在弹窗之上盖一层透明 View，由它消费全部触摸事件。
     */
    private void blockTouch(PopupView v) {
        if (v.getTouchBlocker() != null) return;

        View blocker = new View(activity);
        blocker.setClickable(true);
        blocker.setOnClickListener(null);   // 只消费，不响应
        blocker.setOnTouchListener((view, event) -> true);

        // 高度用弹窗的稳定高度 + 探出补偿，避免 MATCH_PARENT 干扰测量
        // 高度 = 弹窗缓存高度（含探出预留），必须精确匹配，
        // MATCH_PARENT 会把 wrap_content 的弹窗撑到全屏，破坏布局
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                v.getStableHeight() + (int) (25 * density));
        v.addView(blocker, lp);
        v.setTouchBlocker(blocker);
    }

    /** 解除触摸锁定，移除拦截层。 */
    private void unblockTouch(PopupView v) {
        View blocker = v.getTouchBlocker();
        if (blocker != null) {
            v.removeView(blocker);
            v.setTouchBlocker(null);
        }
        v.setClickable(true);
    }
}
