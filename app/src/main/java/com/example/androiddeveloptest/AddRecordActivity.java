package com.example.androiddeveloptest;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import java.util.Calendar;

/**
 * 记一笔页面（二级页面）。
 *
 * <p>通过 {@link #EXTRA_TYPE} 区分收入与支出，主题色与文案随之变化。
 */
public class AddRecordActivity extends AppCompatActivity {

    public static final String EXTRA_TYPE = "extra_type";

    /** 主强调色（橙）。 */
    private static final int COLOR_PRIMARY = 0xFFFF8A3D;
    /** 收入绿。 */
    private static final int COLOR_INCOME = 0xFF3DA35D;
    /** 支出红。 */
    private static final int COLOR_EXPENSE = 0xFFE5484D;

    private static final String[] CATEGORY_NAMES = {
            "餐饮", "交通", "购物", "娱乐", "居住", "医疗", "学习", "更多"
    };
    /** 收入分类（与支出分开，避免"餐饮收入"这类不合理组合）。 */
    private static final String[] INCOME_CATEGORY_NAMES = {
            "工资", "奖金", "理财", "兼职", "红包", "其他"
    };
    private static final int[] CATEGORY_COLORS = {
            0xFFF5A623, 0xFF3D9BE9, 0xFFE05A8C, 0xFF8B5CF6,
            0xFF2FB88A, 0xFFE5484D, 0xFF3B4E87, 0xFF9A9A9A
    };
    private static final int[] CATEGORY_BG = {
            0xFFFFF4E0, 0xFFE8F4FD, 0xFFFDE8F0, 0xFFF0E8FD,
            0xFFE0F7EF, 0xFFFDE8E8, 0xFFE8EDF7, 0xFFF0F0F0
    };

    /** 可选的账户。 */
    private static final String[] ACCOUNTS = {"现金", "微信", "支付宝", "银行卡"};

    private int type;
    private boolean isIncome;

    private EditText etAmount;
    private TextView tvTitle;
    private TextView tvCurrency;
    private TextView btnSwitchType;

    /** 当前选中的分类下标（-1 表示未选）。 */
    private int selectedCategory = -1;
    /** 分类项视图。 */
    private final View[] categoryViews = new View[8];
    /** 分类图标视图，单独持有便于换背景。 */
    private final View[] categoryIcons = new View[8];

    /** 记账日期（当天 0 点）。默认今天。 */
    private long selectedDateMillis;
    /** 当前账户。 */
    private String selectedAccount = "现金";   // onCreate 中按设置覆盖

    /** 金额清空按钮。 */
    private android.widget.ImageView btnClear;

    /** 弹窗栈管理器。 */
    private PopupManager popupManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_record);

        popupManager = new PopupManager(this);

        // 底部避开系统导航栏，使底部按钮与主界面处于同一视觉基准
        android.view.ViewGroup root = findViewById(android.R.id.content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                root, (v, insets) -> {
                    androidx.core.graphics.Insets bars =
                            insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                    v.setPadding(0, bars.top, 0, bars.bottom);
                    return insets;
                });
        // content 的 paddingBottom 区域会裁掉弹窗下移时探出的关闭按钮
        //（PopupManager 的弹窗层挂在 decorView 上，移动时会扫过这块区域）
        root.setClipToPadding(false);
        root.setClipChildren(false);

        type = getIntent().getIntExtra(EXTRA_TYPE, RecordStore.TYPE_EXPENSE);
        isIncome = (type == RecordStore.TYPE_INCOME);

        // 默认账户取设置页的选择
        SettingsStore.init(this);
        selectedAccount = SettingsStore.getDefaultAccount();

        // 默认日期：今天 0 点
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        selectedDateMillis = today.getTimeInMillis();

        tvTitle = findViewById(R.id.tvTitle);
        tvCurrency = findViewById(R.id.tvCurrency);
        etAmount = findViewById(R.id.etAmount);
        btnSwitchType = findViewById(R.id.btnSwitchType);
        btnClear = findViewById(R.id.btnClear);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnDraft).setOnClickListener(v -> showDraftConfirmPopup());
        findViewById(R.id.btnToolDraft).setOnClickListener(v -> showDraftConfirmPopup());

        btnSwitchType.setOnClickListener(v -> switchType());
        findViewById(R.id.btnSave).setOnClickListener(v -> save());

        // 清空金额
        btnClear.setOnClickListener(v -> {
            etAmount.setText("");
            updateClearVisibility();
        });

        // 金额有内容时才显示清空按钮
        etAmount.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                updateClearVisibility();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        // 底部辅助工具栏
        findViewById(R.id.toolHome).setOnClickListener(v -> toast("首页"));
        findViewById(R.id.toolHelp).setOnClickListener(v -> toast("帮助"));
        findViewById(R.id.toolBack).setOnClickListener(v -> finish());

        // 设置（与主界面同一入口）
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                toast("设置功能占位符"));

        // 日期行：点击弹出三列滚轮选择日期
        findViewById(R.id.rowDate).setOnClickListener(v ->
                showDatePopup());

        // 账户行：点击弹出账户选择
        findViewById(R.id.rowAccount).setOnClickListener(v ->
                showAccountPopup());

        setupQuickAmounts();
        setupCategories();
        applyTheme();
        updateDateLabel();
        updateAccountLabel();

        // 顶部悬浮显示学号姓名（作业要求静态展示）
        StudentWatermark.attach(this);

    }

    // ==================================================================
    // 弹窗
    // ==================================================================

    /**
     * 日期选择弹窗：年 / 月 / 日 三列滚轮。
     *
     * <p>上下拖动切换，松手吸附；年月变化时自动修正"日"的合法范围
     * （如 2 月只有 28/29 天）。
     */
    private void showDatePopup() {
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(selectedDateMillis);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 选中结果的实时预览
        final TextView tvPreview = new TextView(this);
        tvPreview.setTextColor(COLOR_PRIMARY);
        tvPreview.setTextSize(18f);
        tvPreview.setGravity(android.view.Gravity.CENTER);
        tvPreview.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(tvPreview);

        // 三列滚轮容器
        LinearLayout wheels = new LinearLayout(this);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams wheelsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        wheelsLp.topMargin = dp(12);
        wheels.setLayoutParams(wheelsLp);
        wheels.setWeightSum(3f);
        box.addView(wheels);

        final WheelView wYear = new WheelView(this);
        final WheelView wMonth = new WheelView(this);
        final WheelView wDay = new WheelView(this);
        for (WheelView w : new WheelView[]{wYear, wMonth, wDay}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            w.setLayoutParams(lp);
            wheels.addView(w);
        }

        // 年范围：前 50 年 ~ 后 10 年
        int curYear = cal.get(Calendar.YEAR);
        String[] years = new String[61];
        for (int i = 0; i < 61; i++) {
            years[i] = (curYear - 50 + i) + " 年";
        }
        wYear.setItems(years);

        String[] months = new String[12];
        for (int i = 0; i < 12; i++) {
            months[i] = (i + 1) + " 月";
        }
        wMonth.setItems(months);

        final int[] selYear = {cal.get(Calendar.YEAR)};
        final int[] selMonth = {cal.get(Calendar.MONTH) + 1};
        final int[] selDay = {cal.get(Calendar.DAY_OF_MONTH)};

        wYear.setSelectedIndex(curYear - (curYear - 50));
        wMonth.setSelectedIndex(selMonth[0] - 1);

        // 按当前年月重建"日"列（天数随月份变化）
        rebuildDays(wDay, selYear[0], selMonth[0], selDay);

        // 更新预览
        Runnable updatePreview = () -> tvPreview.setText(
                selYear[0] + " 年 " + selMonth[0] + " 月 " + selDay[0] + " 日");

        wYear.setOnItemSelectedListener((index, fromUser) -> {
            selYear[0] = curYear - 50 + index;
            rebuildDays(wDay, selYear[0], selMonth[0], selDay);
            updatePreview.run();
        });

        wMonth.setOnItemSelectedListener((index, fromUser) -> {
            selMonth[0] = index + 1;
            rebuildDays(wDay, selYear[0], selMonth[0], selDay);
            updatePreview.run();
        });

        wDay.setOnItemSelectedListener((index, fromUser) -> {
            selDay[0] = index + 1;
            updatePreview.run();
        });

        updatePreview.run();

        // 底部胶囊按钮直接承担「确定」：用户填完只会点它
        popupManager.showConfirm("选择日期", box, () -> {
            Calendar picked = Calendar.getInstance();
            picked.clear();
            picked.set(selYear[0], selMonth[0] - 1, selDay[0]);
            selectedDateMillis = picked.getTimeInMillis();
            updateDateLabel();
            popupManager.dismissTop();
        });
    }

    /**
     * 按年月重建"日"列：天数随月份变化，尽量保持原来选中的日。
     */
    private void rebuildDays(WheelView wDay, int year, int month, int[] selDay) {
        int maxDay = daysInMonth(year, month);
        if (selDay[0] > maxDay) {
            selDay[0] = maxDay;
        }
        if (selDay[0] < 1) {
            selDay[0] = 1;
        }
        String[] days = new String[maxDay];
        for (int i = 0; i < maxDay; i++) {
            days[i] = (i + 1) + " 日";
        }
        wDay.setItems(days);
        wDay.setSelectedIndex(selDay[0] - 1);
    }

    /** 某年某月的天数。 */
    private int daysInMonth(int year, int month) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /** 账户选择弹窗：点选某一项只是临时高亮，点「确定」才生效。 */
    private void showAccountPopup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 临时选中值，点确定前不影响页面
        final String[] pending = {selectedAccount};
        final TextView[] items = new TextView[ACCOUNTS.length];

        for (int i = 0; i < ACCOUNTS.length; i++) {
            final String acc = ACCOUNTS[i];
            TextView item = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            item.setLayoutParams(lp);
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), 0, dp(16), 0);
            item.setText(acc);
            item.setTextSize(15f);
            items[i] = item;

            item.setOnClickListener(v -> {
                pending[0] = acc;
                refreshAccountItems(items, pending[0]);
            });
            box.addView(item);
        }
        refreshAccountItems(items, pending[0]);

        // 底部胶囊按钮承担「确定」
        popupManager.showConfirm("选择账户", box, () -> {
            selectedAccount = pending[0];
            updateAccountLabel();
            popupManager.dismissTop();
        });
    }

    /** 刷新账户列表的选中高亮。 */
    private void refreshAccountItems(TextView[] items, String selected) {
        for (int i = 0; i < items.length; i++) {
            boolean on = ACCOUNTS[i].equals(selected);
            items[i].setTextColor(on ? COLOR_PRIMARY : 0xFF444444);
            items[i].setBackgroundColor(on ? 0x14FF7F2E : 0x00000000);
        }
    }

    /**
     * 存草稿的二次确认弹窗（双按钮形态示例）。
     */
    private void showDraftConfirmPopup() {
        android.util.Log.d("Draft", "showDraftConfirmPopup called");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(this);
        hint.setText("当前填写的金额与分类将保存为草稿，稍后可继续编辑。");
        hint.setTextColor(0xFF666666);
        hint.setTextSize(14f);
        hint.setLineSpacing(dp(6), 1f);
        box.addView(hint);

        popupManager.showDouble("保存草稿", box, "确认", () -> {
            toast("已保存草稿");
            popupManager.dismissTop();
        });
    }

    /** 刷新日期行的显示。 */
    private void updateDateLabel() {
        TextView tvDate = findViewById(R.id.tvDate);
        Calendar picked = Calendar.getInstance();
        picked.setTimeInMillis(selectedDateMillis);

        Calendar today = Calendar.getInstance();
        boolean isToday = picked.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && picked.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);

        if (isToday) {
            tvDate.setText("今天");
        } else {
            tvDate.setText((picked.get(Calendar.MONTH) + 1) + " 月 "
                    + picked.get(Calendar.DAY_OF_MONTH) + " 日");
        }
    }

    /** 刷新账户行的显示。 */
    private void updateAccountLabel() {
        TextView tvAccount = findViewById(R.id.tvAccount);
        tvAccount.setText(selectedAccount);
    }

    @Override
    public void onBackPressed() {
        if (popupManager != null && popupManager.handleBack()) {
            return;
        }
        super.onBackPressed();
    }

    // ==================================================================
    // 主题
    // ==================================================================

    /** 根据当前收支类型刷新标题与强调色。 */
    private void applyTheme() {
        int accent = isIncome ? COLOR_INCOME : COLOR_EXPENSE;
        tvTitle.setText(isIncome ? "记一笔收入" : "记一笔支出");
        tvCurrency.setTextColor(accent);
        etAmount.setTextColor(accent);
        btnSwitchType.setText(isIncome ? "切换为支出" : "切换为收入");
    }

    /** 在收入与支出之间切换。 */
    private void switchType() {
        isIncome = !isIncome;
        type = isIncome ? RecordStore.TYPE_INCOME : RecordStore.TYPE_EXPENSE;
        applyTheme();
        // 收支使用两套不同分类，切换类型必须重建网格
        setupCategories();
    }

    // ==================================================================
    // 快捷金额
    // ==================================================================

    private void setupQuickAmounts() {
        int[] ids = {R.id.chip10, R.id.chip20, R.id.chip50, R.id.chip100};
        int[] values = {10, 20, 50, 100};

        for (int i = 0; i < ids.length; i++) {
            final int value = values[i];
            findViewById(ids[i]).setOnClickListener(v -> addAmount(value));
        }
    }

    /**
     * 在当前金额基础上累加。
     *
     * <p>输入框为空或非法时按 0 处理；结果为整数时不显示小数位。
     */
    private void addAmount(int delta) {
        double base = 0;
        String cur = etAmount.getText().toString().trim();
        if (!TextUtils.isEmpty(cur)) {
            try {
                base = Double.parseDouble(cur);
            } catch (NumberFormatException ignored) {
                // 非法输入按 0 处理
            }
        }

        double result = base + delta;
        if (result == Math.floor(result) && !Double.isInfinite(result)) {
            etAmount.setText(String.valueOf((long) result));
        } else {
            etAmount.setText(String.valueOf(result));
        }
        etAmount.setSelection(etAmount.getText().length());
    }

    /** 金额非空时显示清空按钮。 */
    private void updateClearVisibility() {
        boolean has = !TextUtils.isEmpty(etAmount.getText().toString().trim());
        btnClear.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    // ==================================================================
    // 分类
    // ==================================================================

    /**
     * 动态生成 4 列 2 行的分类项。
     *
     * <p>每项为竖排「圆形图标 + 名称」，选中时切换为橙描边 + 浅橙底。
     */
    /**
     * 构建分类网格。
     *
     * <p>按当前类型（收入/支出）填充两套不同的分类，
     * 因此切换类型时需要重建。
     */
    private void setupCategories() {
        GridLayout grid = findViewById(R.id.gridCategory);
        grid.removeAllViews();
        grid.setColumnCount(4);
        grid.setRowCount(2);

        String[] names = currentCategoryNames();
        selectedCategory = -1;

        int iconSize = dp(52);
        int margin = dp(4);

        for (int i = 0; i < names.length; i++) {
            final int index = i;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(4), dp(4), dp(4), dp(4));

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(i % 4, 1f);
            lp.rowSpec = GridLayout.spec(i / 4);
            lp.setMargins(margin, margin, margin, margin);
            item.setLayoutParams(lp);

            // 圆形图标底（暂用分类名首字代替图标）
            TextView icon = new TextView(this);
            icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
            icon.setGravity(Gravity.CENTER);
            icon.setText(names[i].substring(0, 1));
            icon.setTextColor(CATEGORY_COLORS[i % CATEGORY_COLORS.length]);
            icon.setTextSize(17f);
            icon.setTypeface(Typeface.DEFAULT_BOLD);
            icon.setBackground(circleBg(CATEGORY_BG[i % CATEGORY_BG.length], 0));
            item.addView(icon);

            // 名称
            TextView label = new TextView(this);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            textLp.topMargin = dp(4);
            label.setLayoutParams(textLp);
            label.setText(names[i]);
            label.setTextColor(0xFF6B6B6B);
            label.setTextSize(12f);
            item.addView(label);

            item.setOnClickListener(v -> selectCategory(index));

            categoryViews[i] = item;
            categoryIcons[i] = icon;
            grid.addView(item);
        }
    }

    /** 当前类型对应的分类名数组。 */
    private String[] currentCategoryNames() {
        return isIncome ? INCOME_CATEGORY_NAMES : CATEGORY_NAMES;
    }

    /** 选中某个分类，并刷新所有分类的选中态。 */
    private void selectCategory(int index) {
        selectedCategory = index;

        for (int i = 0; i < categoryIcons.length; i++) {
            if (categoryIcons[i] == null) continue;
            if (i == index) {
                categoryIcons[i].setBackground(circleBg(0xFFFFF0E6, dp(2)));
            } else {
                categoryIcons[i].setBackground(
                        circleBg(CATEGORY_BG[i % CATEGORY_BG.length], 0));
            }
        }
    }

    // ==================================================================
    // 保存
    // ==================================================================

    private void save() {
        String text = etAmount.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            toast("请输入金额");
            return;
        }

        // 分类必须选，否则账单里会出现"无分类"的记录
        if (selectedCategory < 0) {
            toast("请选择分类");
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            toast("金额格式不正确");
            return;
        }

        if (amount <= 0) {
            toast("金额需大于 0");
            return;
        }

        EditText etNote = findViewById(R.id.etNote);
        String categoryName = currentCategoryNames()[selectedCategory];

        RecordStore.add(new RecordStore.Record(
                type,
                amount,
                etNote.getText().toString().trim(),
                selectedDateMillis,
                categoryName,
                selectedAccount));

        toast(isIncome ? "已记一笔收入" : "已记一笔支出");
        finish();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 生成圆形背景。
     *
     * @param color       填充色
     * @param strokeWidth 描边宽度（px），0 表示无描边
     */
    private GradientDrawable circleBg(int color, int strokeWidth) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        if (strokeWidth > 0) {
            gd.setStroke(strokeWidth, COLOR_PRIMARY);
        }
        return gd;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
