package com.example.androiddeveloptest;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    private CurvedNavBar navBar;
    private FrameLayout pagerContainer;

    private final View[] pages = new View[5];
    /** 账单页的列表与适配器（下标 3）。 */
    private BillAdapter billAdapter;
    private android.widget.ListView lvBills;
    /** 账单页根视图（此时 pages 数组尚未赋值，需单独持有）。 */
    private View billPage;
    private int screenWidth;

    /** 当前页面进度：0 = 第0页，4 = 第4页，2.5 = 中间态 */
    private float pageProgress = 2f;      // 默认选中中间（主页）

    /**
     * 绑定胶囊按钮的左右点击。
     * 目前先用 Toast 验证，下一轮接 AddRecordActivity。
     */
    private void setupCapsule() {
        View btnIncome = pages[2].findViewById(R.id.btnIncome);
        View btnExpense = pages[2].findViewById(R.id.btnExpense);

        btnIncome.setOnClickListener(v ->
                openAddRecord(RecordStore.TYPE_INCOME));
        btnExpense.setOnClickListener(v ->
                openAddRecord(RecordStore.TYPE_EXPENSE));

        // 左下角：日志（位于主布局，非页面内）
        findViewById(R.id.fabLog).setOnClickListener(v ->
                android.widget.Toast.makeText(this, "日志功能占位符",
                        android.widget.Toast.LENGTH_SHORT).show());

        // 右下角：设置
        findViewById(R.id.fabSettings).setOnClickListener(v ->
                android.widget.Toast.makeText(this, "设置功能占位符",
                        android.widget.Toast.LENGTH_SHORT).show());
    }

    /** 跳转到"记一笔"页面。 */
    private void openAddRecord(int type) {
        android.content.Intent intent =
                new android.content.Intent(this, AddRecordActivity.class);
        intent.putExtra(AddRecordActivity.EXTRA_TYPE, type);
        startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        // 加载持久化的记账数据（首屏入口，只需一次）
        RecordStore.init(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        navBar = findViewById(R.id.curvedNavBar);
        pagerContainer = findViewById(R.id.pagerContainer);

        screenWidth = getResources().getDisplayMetrics().widthPixels;

        setupPages();
        applyPageProgress(pageProgress);
        setupCapsule();


        // 导航栏选中项变化 → 页面平滑滑过去
        navBar.setOnItemSelectedListener(new CurvedNavBar.OnItemSelectedListener() {
            @Override
            public void onItemSelected(int index) {
                animateToPage(index);
            }

            @Override
            public void onProgressChanged(float progress) {
                setPageProgress(progress);      // 实时跟随，不动画
            }
        });
    }

    /**
     * 创建 5 个页面并横向排开。
     * 第 2 页（主页）使用 XML 布局，其余为占位色块。
     */
    private void setupPages() {
        for (int i = 0; i < pages.length; i++) {
            View page;
            if (i == 2) {
                page = getLayoutInflater().inflate(
                        R.layout.page_home, pagerContainer, false);
            } else if (i == 3) {
                page = getLayoutInflater().inflate(
                        R.layout.page_bill, pagerContainer, false);
                setupBillPage(page);
            } else {
                page = buildPlaceholderPage(i);
            }

            // 初始位置：第 i 页向右偏移 i 个屏幕宽度
            page.setTranslationX(i * (float) screenWidth);
            pagerContainer.addView(page);
            pages[i] = page;
        }
    }

    /**
     * 初始化账单页：绑定列表适配器并填充数据。
     *
     * <p>注意：此时 setupPages 尚未把 page 写入 pages 数组，
     * 因此汇总视图直接持有引用，不能通过 pages[3] 反查。
     */
    private void setupBillPage(View page) {
        billPage = page;
        lvBills = page.findViewById(R.id.lvBills);
        billAdapter = new BillAdapter(this);
        lvBills.setAdapter(billAdapter);
        refreshBillPage();
    }

    /**
     * 刷新账单页：汇总金额 + 列表内容 + 空状态切换。
     */
    private void refreshBillPage() {
        if (billAdapter == null || billPage == null) return;

        java.util.List<RecordStore.Record> all = RecordStore.getAll();
        billAdapter.setData(all);

        // 汇总
        TextView tvExpense = billPage.findViewById(R.id.tvBillExpense);
        TextView tvIncome = billPage.findViewById(R.id.tvBillIncome);
        TextView tvHeader = billPage.findViewById(R.id.tvBillHeader);
        tvExpense.setText("¥" + formatMoney(RecordStore.totalOf(RecordStore.TYPE_EXPENSE)));
        tvIncome.setText("¥" + formatMoney(RecordStore.totalOf(RecordStore.TYPE_INCOME)));
        tvHeader.setText(all.isEmpty() ? "全部记录" : "全部记录（" + all.size() + " 条）");

        // 空状态
        lvBills.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        billPage.findViewById(R.id.tvBillEmpty)
                .setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 金额格式化：整数不带小数。 */
    private String formatMoney(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v) + ".00";
        }
        return String.format(java.util.Locale.getDefault(), "%.2f", v);
    }

    /**
     * 其他 4 个 Tab 的占位页（纯色 + 标题）。
     */
    private View buildPlaceholderPage(int index) {
        String[] titles = {"工具", "统计", "主页", "账单", "我的"};
        // 暖色系占位底色，与整体配色统一
        int[] colors = {0xFFFDF3E3, 0xFFFBEFE0, 0xFFFDF3E3,
                0xFFFDF3E3, 0xFFFBEFE2};

        FrameLayout page = new FrameLayout(this);
        page.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        page.setBackgroundColor(colors[index]);

        TextView tv = new TextView(this);
        tv.setText(titles[index]);
        tv.setTextSize(36f);
        tv.setTextColor(0xFFB9A896);
        tv.setGravity(Gravity.CENTER);
        page.addView(tv);
        return page;
    }

    /**
     * 根据进度值摆放 5 个页面
     *
     * 公式：第 i 页的 x = (i - progress) × 屏宽
     *
     * progress = 0  →  第0页占满屏幕
     * progress = 2  →  第2页占满屏幕
     * progress = 0.5 →  第0、1页各露一半
     */
    private void applyPageProgress(float progress) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setTranslationX((i - progress) * screenWidth);
        }
    }


    /** 供 ObjectAnimator 调用（必须是 public 的 setter） */
    public void setPageProgress(float progress) {
        pageProgress = progress;
        applyPageProgress(progress);
    }

    public float getPageProgress() {
        return pageProgress;
    }

    /**
     * 平滑滑到目标页
     */
    private void animateToPage(int index) {
        android.animation.ObjectAnimator animator =
                android.animation.ObjectAnimator.ofFloat(
                        this, "pageProgress", pageProgress, (float) index);
        animator.setDuration(350);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (navBar == null) {
            return super.dispatchTouchEvent(ev);
        }

        int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            if (isInHotZone(ev.getX(), ev.getY())) {
                navBar.onGlobalDown(ev.getX(), ev.getY());
            }
        }

        if (navBar.isGlobalDragging()) {
            navBar.onGlobalTouch(ev);
            return true;
        }

        return super.dispatchTouchEvent(ev);
    }

    /**
     * 判断触点是否在导航栏热区（弧带最高点 -> 屏幕底部）。
     *
     * 热区上边界取弧带最高点，而非导航栏控件顶部：
     * 弧带在控件内是向上翘起的，取最高点可以把整个弧带都包含进来，
     * 又不会侵入上方的内容区。
     */
    private boolean isInHotZone(float x, float y) {
        int[] loc = new int[2];
        navBar.getLocationInWindow(loc);

        float hotZoneTop = loc[1] + navBar.getArcTopY();
        return y >= hotZoneTop;
    }
    @Override
    protected void onResume() {
        super.onResume();
        refreshHomeData();
        refreshBillPage();
    }

    /**
     * 从记账页返回后刷新主页的汇总金额。
     */
    private void refreshHomeData() {
        if (pages[2] == null) return;

        TextView tvExpense = pages[2].findViewById(R.id.tvExpense);
        TextView tvIncome = pages[2].findViewById(R.id.tvIncome);
        if (tvExpense == null || tvIncome == null) return;

        tvExpense.setText(String.format("¥ %.2f",
                RecordStore.totalOf(RecordStore.TYPE_EXPENSE)));
        tvIncome.setText(String.format("本月收入  ¥ %.2f",
                RecordStore.totalOf(RecordStore.TYPE_INCOME)));
    }
}