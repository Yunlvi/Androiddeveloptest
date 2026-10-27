package com.example.androiddeveloptest;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用设置项存储。
 *
 * <p>用 SharedPreferences 保存用户在设置页所做的选择，
 * 其他页面通过静态读取方法获取，从而让设置真正生效。
 */
public final class SettingsStore {

    private static final String PREFS_NAME = "app_settings";

    /** 主题配色：暖橙（默认）。 */
    public static final int THEME_WARM = 0;
    /** 主题配色：淡蓝。 */
    public static final int THEME_BLUE = 1;
    /** 主题配色：薄荷。 */
    public static final int THEME_MINT = 2;

    /** 提醒项：每日记账提醒。 */
    public static final String KEY_REMIND_DAILY = "remind_daily";
    /** 提醒项：大额支出提醒。 */
    public static final String KEY_REMIND_BIG = "remind_big";
    /** 提醒项：月度报表。 */
    public static final String KEY_REMIND_MONTHLY = "remind_monthly";

    /** 可选账户，与记账页保持一致。 */
    public static final String[] ACCOUNTS = {"现金", "微信", "支付宝", "银行卡"};

    private static SharedPreferences prefs;

    private SettingsStore() {
        // 工具类，禁止实例化
    }

    /** 初始化。应用启动时调用一次。 */
    public static synchronized void init(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    // ==================================================================
    // 主题
    // ==================================================================

    /** 读取当前主题。 */
    public static int getTheme() {
        return prefs == null ? THEME_WARM : prefs.getInt("theme", THEME_WARM);
    }

    /** 保存主题。 */
    public static void setTheme(int theme) {
        if (prefs != null) {
            prefs.edit().putInt("theme", theme).apply();
        }
    }

    // ==================================================================
    // 默认账户
    // ==================================================================

    /** 记账页默认账户。 */
    public static String getDefaultAccount() {
        return prefs == null ? ACCOUNTS[0]
                : prefs.getString("default_account", ACCOUNTS[0]);
    }

    /** 保存默认账户。 */
    public static void setDefaultAccount(String account) {
        if (prefs != null) {
            prefs.edit().putString("default_account", account).apply();
        }
    }

    /** 账户在数组中的下标（供 Spinner 定位）。 */
    public static int getDefaultAccountIndex() {
        String acc = getDefaultAccount();
        for (int i = 0; i < ACCOUNTS.length; i++) {
            if (ACCOUNTS[i].equals(acc)) return i;
        }
        return 0;
    }

    // ==================================================================
    // 提醒开关
    // ==================================================================

    /** 读取某个提醒项是否开启。 */
    public static boolean isRemindOn(String key) {
        return prefs != null && prefs.getBoolean(key, false);
    }

    /** 保存某个提醒项的开关状态。 */
    public static void setRemind(String key, boolean on) {
        if (prefs != null) {
            prefs.edit().putBoolean(key, on).apply();
        }
    }

    // ==================================================================
    // 主题配色
    // ==================================================================

    /** 主色（未选中/强调用）。 */
    public static int primaryColor() {
        switch (getTheme()) {
            case THEME_BLUE:
                return 0xFF4A90D9;
            case THEME_MINT:
                return 0xFF3DA37A;
            default:
                return 0xFFFF7F2E;
        }
    }

    /** 主色的浅色变体（用于选中背景）。 */
    public static int primaryColorLight() {
        switch (getTheme()) {
            case THEME_BLUE:
                return 0x144A90D9;
            case THEME_MINT:
                return 0x143DA37A;
            default:
                return 0x14FF7F2E;
        }
    }

    /** 主题名称，用于设置页显示。 */
    public static String themeName(int theme) {
        switch (theme) {
            case THEME_BLUE:
                return "淡蓝";
            case THEME_MINT:
                return "薄荷";
            default:
                return "暖橙";
        }
    }
}
