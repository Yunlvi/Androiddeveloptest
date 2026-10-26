package com.example.androiddeveloptest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import androidx.annotation.NonNull;

/**
 * 竖向滚轮选择器。
 *
 * <p>中间一行为选中值（高亮加粗），上下各露出若干行（淡化）。
 * 支持：
 * <ul>
 *   <li>上下拖动：跟手滚动，松手吸附到最近项</li>
 *   <li>快速滑动：惯性滚动后吸附</li>
 *   <li>点击上下行：滚动到该行</li>
 * </ul>
 *
 * <p>数据以字符串数组传入，索引即选中值。外层通过
 * {@link OnItemSelectedListener} 感知变化，用于三列联动。
 */
public class WheelView extends View {

    /** 选中的回调。 */
    public interface OnItemSelectedListener {
        /**
         * @param index 新的选中下标
         * @param fromUser true 表示由用户滑动/点击触发，
         *                 false 表示外部调用 {@link #setSelectedIndex} 引起
         */
        void onItemSelected(int index, boolean fromUser);
    }

    /** 可见行数（奇数，中间为选中行）。 */
    private static final int VISIBLE_COUNT = 5;
    /** 松手后吸附动画时长。 */
    private static final int SNAP_DURATION_MS = 260;

    private String[] items = new String[0];
    private int selectedIndex = 0;

    private Paint textPaint;
    private Paint selectedPaint;
    private final RectF centerBgRect = new RectF();

    /** 单行高度（px）。 */
    private float itemHeight;
    /** 文字大小（px）。 */
    private float textSize;

    private int normalColor = 0xFFB9A896;
    private int selectedColor = 0xFFFF7F2E;

    private final Scroller scroller;
    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private VelocityTracker velocityTracker;

    /** 当前滚动偏移（px）。0 表示精确停在第 0 项。 */
    private float scrollOffset;

    /** 按下时的 y 和当时的偏移。 */
    private float downY;
    private float downOffset;
    private boolean isDragging;

    private OnItemSelectedListener listener;

    private final Handler snapHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            if (msg.what == MSG_SNAP && !scroller.computeScrollOffset()) {
                // 滚动结束，对齐到最近项
                scrollOffset = Math.round(scrollOffset / itemHeight) * itemHeight;
                clampOffset();
                invalidate();
                notifySelection(false);
                return true;
            }
            return false;
        }
    });
    private static final int MSG_SNAP = 1;

    public WheelView(Context context) {
        this(context, null);
    }

    public WheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        scroller = new Scroller(context);

        float density = getResources().getDisplayMetrics().density;
        itemHeight = 44 * density;
        textSize = 17 * density;

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(textSize);

        selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedPaint.setTextAlign(Paint.Align.CENTER);
        selectedPaint.setTextSize(textSize);
        selectedPaint.setFakeBoldText(true);
    }

    // ==================================================================
    // 对外 API
    // ==================================================================

    /** 设置数据。会尽量保持原选中下标。 */
    public void setItems(String[] items) {
        this.items = items == null ? new String[0] : items;
        if (selectedIndex >= this.items.length) {
            selectedIndex = Math.max(0, this.items.length - 1);
        }
        scrollOffset = selectedIndex * itemHeight;
        scrollToOffset(scrollOffset, true);
        invalidate();
    }

    /** 选中回调。 */
    public void setOnItemSelectedListener(OnItemSelectedListener l) {
        this.listener = l;
    }

    /** 当前选中下标。 */
    public int getSelectedIndex() {
        return selectedIndex;
    }

    /** 外部设置选中项（不动画）。 */
    public void setSelectedIndex(int index) {
        if (items.length == 0) return;
        int i = Math.max(0, Math.min(index, items.length - 1));
        selectedIndex = i;
        scrollOffset = i * itemHeight;
        scrollToOffset(scrollOffset, false);
        invalidate();
    }

    // ==================================================================
    // 测量与绘制
    // ==================================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize((int) (60 * getResources().getDisplayMetrics().density),
                widthMeasureSpec);
        int height = resolveSize((int) (itemHeight * VISIBLE_COUNT), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (items.length == 0) return;

        float cx = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float centerRowTop = centerY - itemHeight / 2f;

        // 选中行背景（浅橙圆角条）
        centerBgRect.set(getPaddingLeft() + 2, centerRowTop + 2,
                getWidth() - getPaddingRight() - 2, centerRowTop + itemHeight - 2);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0x14FF7F2E);
        float r = 10 * getResources().getDisplayMetrics().density;
        canvas.drawRoundRect(centerBgRect, r, r, bg);

        // 计算第一行的位置：让 scrollOffset 对应的项正好居中
        float firstRowTop = centerRowTop - scrollOffset;

        for (int i = 0; i < items.length; i++) {
            float rowTop = firstRowTop + i * itemHeight;
            float rowCenter = rowTop + itemHeight / 2f;

            // 只画可见范围内的行
            if (rowTop > getHeight() || rowTop + itemHeight < 0) continue;

            // 距中心的距离（以行为单位），用于淡化
            float distance = Math.abs(rowCenter - centerY) / itemHeight;
            if (distance > VISIBLE_COUNT / 2f + 0.5f) continue;

            float alpha = Math.max(0.15f, 1f - distance * 0.55f);
            boolean isSelected = distance < 0.5f;

            Paint p = isSelected ? selectedPaint : textPaint;
            int baseColor = isSelected ? selectedColor : normalColor;
            p.setColor(applyAlpha(baseColor, alpha));

            // 文字基线
            Paint.FontMetrics fm = p.getFontMetrics();
            float baseline = rowCenter - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(items[i], cx, baseline, p);
        }
    }

    private int applyAlpha(int color, float alpha) {
        int a = (int) (((color >>> 24) & 0xFF) * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    // ==================================================================
    // 触摸处理
    // ==================================================================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (items.length == 0) return false;

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                snapHandler.removeMessages(MSG_SNAP);
                downY = event.getY();
                downOffset = scrollOffset;
                isDragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - downY;
                if (!isDragging && Math.abs(dy) > touchSlop) {
                    isDragging = true;
                    downY = event.getY() - (dy > 0 ? touchSlop : -touchSlop);
                    dy = event.getY() - downY;
                }
                if (isDragging) {
                    scrollOffset = downOffset - dy;
                    clampOffset();
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!isDragging) {
                    // 未拖动：视为点击，判断点在哪一行
                    handleClick(event.getY());
                } else {
                    // 拖动结束：根据速度决定惯性 or 直接吸附
                    velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    float vy = velocityTracker.getYVelocity();
                    if (Math.abs(vy) > minFlingVelocity) {
                        startFling(vy);
                    } else {
                        snapToNearest();
                    }
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    /** 点击某行：滚动到该行。 */
    private void handleClick(float y) {
        float centerY = getHeight() / 2f;
        float centerRowTop = centerY - itemHeight / 2f;
        float firstRowTop = centerRowTop - scrollOffset;

        int row = (int) Math.floor((y - firstRowTop) / itemHeight);
        int target = Math.max(0, Math.min(row, items.length - 1));
        scrollToIndex(target, true);
    }

    /** 惯性滚动。 */
    private void startFling(float velocityY) {
        int startOffset = (int) scrollOffset;
        int minOffset = 0;
        int maxOffset = (int) ((items.length - 1) * itemHeight);
        // 惯性滚动距离放大，手感更顺
        scroller.fling(0, startOffset, 0, (int) (-velocityY * 0.6f),
                0, 0, minOffset, maxOffset);
        post(flingRunnable);
    }

    private final Runnable flingRunnable = new Runnable() {
        @Override
        public void run() {
            if (scroller.computeScrollOffset()) {
                scrollOffset = scroller.getCurrY();
                invalidate();
                postOnAnimation(this);
            } else {
                snapToNearest();
            }
        }
    };

    /** 吸附到最近的整行。 */
    private void snapToNearest() {
        float target = Math.round(scrollOffset / itemHeight) * itemHeight;
        smoothScrollTo(target);
    }

    private void scrollToIndex(int index, boolean smooth) {
        float target = index * itemHeight;
        if (smooth) {
            smoothScrollTo(target);
        } else {
            scrollOffset = target;
            clampOffset();
            invalidate();
            notifySelection(false);
        }
    }

    /** 平滑滚动到指定偏移。 */
    private void smoothScrollTo(final float target) {
        final float start = scrollOffset;
        final long startTime = System.currentTimeMillis();
        post(new Runnable() {
            @Override
            public void run() {
                float t = (System.currentTimeMillis() - startTime) / (float) SNAP_DURATION_MS;
                if (t >= 1f) {
                    scrollOffset = target;
                    clampOffset();
                    invalidate();
                    notifySelection(false);
                } else {
                    // 减速插值
                    float eased = 1f - (1f - t) * (1f - t);
                    scrollOffset = start + (target - start) * eased;
                    invalidate();
                    postOnAnimation(this);
                }
            }
        });
    }

    private void scrollToOffset(float offset, boolean notify) {
        scrollOffset = offset;
        clampOffset();
        if (notify) notifySelection(false);
    }

    /** 限制偏移在合法范围内。 */
    private void clampOffset() {
        if (items.length == 0) {
            scrollOffset = 0;
            return;
        }
        float max = (items.length - 1) * itemHeight;
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > max) scrollOffset = max;
    }

    /** 计算当前选中的下标并回调（仅在变化时）。 */
    private void notifySelection(boolean fromUser) {
        if (items.length == 0) return;
        int idx = Math.round(scrollOffset / itemHeight);
        idx = Math.max(0, Math.min(idx, items.length - 1));
        if (idx != selectedIndex) {
            selectedIndex = idx;
            if (listener != null) {
                listener.onItemSelected(idx, fromUser);
            }
        }
    }
}
