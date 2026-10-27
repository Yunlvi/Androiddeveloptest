package com.example.androiddeveloptest;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页。
 *
 * <p>演示三类备选控件的完整用法，且每一项都会真实影响其他页面：
 * <ul>
 *   <li>RadioGroup —— 主题配色，切换后导航栏与选中态立即换色</li>
 *   <li>Spinner —— 默认账户，记账页打开时预选该账户</li>
 *   <li>CheckBox —— 提醒项（多选），首页据此显示提示</li>
 * </ul>
 *
 * <p>页面顶部用 ImageButton 作为返回按钮。
 */
public class SettingsActivity extends AppCompatActivity {

    private RadioGroup rgTheme;
    private Spinner spinnerAccount;
    private CheckBox cbDaily, cbBig, cbMonthly;
    private TextView tvSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        SettingsStore.init(this);

        // 与记账页一致：避开系统状态栏与导航栏，
        // 使底部导航条与记账页处于同一垂直基准
        android.view.ViewGroup root = findViewById(android.R.id.content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                root, (v, insets) -> {
                    androidx.core.graphics.Insets bars =
                            insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                    v.setPadding(0, bars.top, 0, bars.bottom);
                    return insets;
                });

        ImageButton btnBack = findViewById(R.id.btnBack);
        rgTheme = findViewById(R.id.rgTheme);
        spinnerAccount = findViewById(R.id.spinnerAccount);
        cbDaily = findViewById(R.id.cbDaily);
        cbBig = findViewById(R.id.cbBig);
        cbMonthly = findViewById(R.id.cbMonthly);
        tvSummary = findViewById(R.id.tvSummary);

        // 顶栏图标：关闭设置页
        btnBack.setOnClickListener(v -> finish());

        // 底部导航条：首页 / 帮助 / 返回
        findViewById(R.id.toolHome).setOnClickListener(v -> finish());
        findViewById(R.id.toolHelp).setOnClickListener(v ->
                android.widget.Toast.makeText(this, "设置项会立即保存并生效",
                        android.widget.Toast.LENGTH_SHORT).show());
        findViewById(R.id.toolBack).setOnClickListener(v -> finish());

        setupThemeGroup();
        setupAccountSpinner();
        setupReminders();

        refreshSummary();

        // 顶部悬浮显示学号姓名（作业要求静态展示）
        StudentWatermark.attach(this);
    }

    // ==================================================================
    // RadioGroup：主题配色
    // ==================================================================

    /**
     * 单选按钮组：组内互斥，选中一项自动取消其余。
     *
     * <p>用 setOnCheckedChangeListener 监听，回调参数为被选中项的 id。
     */
    private void setupThemeGroup() {
        // 回显已保存的选择
        switch (SettingsStore.getTheme()) {
            case SettingsStore.THEME_BLUE:
                rgTheme.check(R.id.rbBlue);
                break;
            case SettingsStore.THEME_MINT:
                rgTheme.check(R.id.rbMint);
                break;
            default:
                rgTheme.check(R.id.rbWarm);
                break;
        }

        rgTheme.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int theme;
                if (checkedId == R.id.rbBlue) {
                    theme = SettingsStore.THEME_BLUE;
                } else if (checkedId == R.id.rbMint) {
                    theme = SettingsStore.THEME_MINT;
                } else {
                    theme = SettingsStore.THEME_WARM;
                }
                SettingsStore.setTheme(theme);

                // 即时反馈：选中项的提示色跟着变
                refreshCheckedColor();
                refreshSummary();
            }
        });
    }

    /** 让三个单选按钮的文字颜色体现当前主题。 */
    private void refreshCheckedColor() {
        int active = SettingsStore.primaryColor();
        int normal = 0xFF444444;
        android.widget.RadioButton rbWarm = findViewById(R.id.rbWarm);
        android.widget.RadioButton rbBlue = findViewById(R.id.rbBlue);
        android.widget.RadioButton rbMint = findViewById(R.id.rbMint);

        rbWarm.setTextColor(rbWarm.isChecked() ? active : normal);
        rbBlue.setTextColor(rbBlue.isChecked() ? active : normal);
        rbMint.setTextColor(rbMint.isChecked() ? active : normal);
    }

    // ==================================================================
    // Spinner：默认账户
    // ==================================================================

    /**
     * 下拉框：用 ArrayAdapter 绑定数据，用 setSelection 回显，
     * 用 OnItemSelectedListener 监听选择（接口需实现两个方法）。
     */
    private void setupAccountSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                SettingsStore.ACCOUNTS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAccount.setAdapter(adapter);

        // 回显已保存的默认账户
        spinnerAccount.setSelection(SettingsStore.getDefaultAccountIndex(), false);

        spinnerAccount.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                SettingsStore.setDefaultAccount(SettingsStore.ACCOUNTS[position]);
                refreshSummary();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 未选中任何项时不处理，但方法必须实现
            }
        });
    }

    // ==================================================================
    // CheckBox：提醒项（多选）
    // ==================================================================

    /**
     * 复选框：彼此独立可多选。
     *
     * <p>三个复选框共用一个监听器对象，靠回调参数 buttonView
     * 判断是哪个控件触发，避免重复写三份代码。
     */
    private void setupReminders() {
        // 回显已保存状态
        cbDaily.setChecked(SettingsStore.isRemindOn(SettingsStore.KEY_REMIND_DAILY));
        cbBig.setChecked(SettingsStore.isRemindOn(SettingsStore.KEY_REMIND_BIG));
        cbMonthly.setChecked(SettingsStore.isRemindOn(SettingsStore.KEY_REMIND_MONTHLY));

        CompoundButton.OnCheckedChangeListener listener =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        int vid = buttonView.getId();
                        if (vid == R.id.cbDaily) {
                            SettingsStore.setRemind(SettingsStore.KEY_REMIND_DAILY, isChecked);
                        } else if (vid == R.id.cbBig) {
                            SettingsStore.setRemind(SettingsStore.KEY_REMIND_BIG, isChecked);
                        } else if (vid == R.id.cbMonthly) {
                            SettingsStore.setRemind(SettingsStore.KEY_REMIND_MONTHLY, isChecked);
                        }
                        refreshSummary();
                    }
                };

        cbDaily.setOnCheckedChangeListener(listener);
        cbBig.setOnCheckedChangeListener(listener);
        cbMonthly.setOnCheckedChangeListener(listener);
    }

    // ==================================================================
    // 摘要
    // ==================================================================

    /** 底部显示当前设置的组合结果。 */
    private void refreshSummary() {
        refreshCheckedColor();

        List<String> reminds = new ArrayList<>();
        if (cbDaily.isChecked()) reminds.add("每日");
        if (cbBig.isChecked()) reminds.add("大额");
        if (cbMonthly.isChecked()) reminds.add("月报");

        String remindText = reminds.isEmpty() ? "未开启提醒" : "提醒：" + String.join("、", reminds);

        tvSummary.setText("当前：" + SettingsStore.themeName(SettingsStore.getTheme())
                + " · 默认" + SettingsStore.getDefaultAccount()
                + " · " + remindText);
    }
}
