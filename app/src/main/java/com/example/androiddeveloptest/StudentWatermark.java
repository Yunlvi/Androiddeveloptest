package com.example.androiddeveloptest;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * 学号姓名水印。
 *
 * <p>作业要求所有页面静态显示姓名与学号、且不可编辑。这里用 PopupWindow
 * 悬浮在页面最顶部，覆盖在所有组件之上，不占用任何布局空间。
 *
 * <p>交互：进入页面时显示，点击水印即淡出消失，
 * 避免长期遮挡；再次进入页面时会重新出现。
 */
public final class StudentWatermark {

    /** 静态写死的学号姓名，不可编辑。 */
    private static final String TEXT = "2406100211  \u9ad8\u6da6\u6cfd";

    /** 高度（dp），兼顾录屏可读性与遮挡面积。 */
    private static final int HEIGHT_DP = 30;
    /** 文字大小（sp），录屏时清晰可辨。 */
    private static final float TEXT_SP = 14f;
    /** 淡出时长（毫秒）。 */
    private static final long FADE_MS = 200;

    private StudentWatermark() {
        // 工具类，禁止实例化
    }

    /**
     * 在当前 Activity 顶部悬浮显示水印。
     *
     * @param activity 目标页面
     */
    public static void attach(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        float density = activity.getResources().getDisplayMetrics().density;

        TextView tv = new TextView(activity);
        tv.setText(TEXT);
        tv.setTextSize(TEXT_SP);
        tv.setTextColor(0xFF8A7460);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);

        // 半透明浅米底，比页面内容稍暗一点，录屏时容易看清
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF7F0E6D6);
        bg.setCornerRadii(new float[]{0, 0, 0, 0,
                14 * density, 14 * density, 14 * density, 14 * density});
        tv.setBackground(bg);

        PopupWindow popup = new PopupWindow(
                tv,
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (HEIGHT_DP * density),
                false);
        popup.setClippingEnabled(false);
        popup.setTouchable(true);
        popup.setFocusable(false);
        popup.setOutsideTouchable(false);
        // PopupWindow 需要非空 background 才能正确接收触摸事件，
        // 用透明色即可，不影响视觉
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0x00000000));

        // 点击水印：淡出后关闭
        tv.setOnClickListener(v -> {
            tv.animate()
                    .alpha(0f)
                    .setDuration(FADE_MS)
                    .withEndAction(popup::dismiss)
                    .start();
        });

        View decor = activity.getWindow().getDecorView();
        decor.post(() -> {
            if (activity.isFinishing()) return;
            popup.showAtLocation(decor, Gravity.TOP | Gravity.START,
                    0, topOffset(activity));
        });
    }

    /** 顶部偏移：避开状态栏，使水印紧贴状态栏下沿。 */
    private static int topOffset(Activity activity) {
        int statusBarHeight = 0;
        int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            statusBarHeight = activity.getResources().getDimensionPixelSize(id);
        }
        return statusBarHeight;
    }
}
