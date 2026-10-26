package com.example.androiddeveloptest;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记账数据存储。
 *
 * <p>使用 SharedPreferences 以 JSON 数组形式持久化，App 重启后数据保留。
 * 数据量在千条级别时性能足够；若日后记录数上千，可平滑替换为 Room，
 * 对外接口保持不变。
 *
 * <p>使用前必须在 Application 或首屏调用一次 {@link #init(Context)}。
 */
public final class RecordStore {

    /** 收入。 */
    public static final int TYPE_INCOME = 1;
    /** 支出。 */
    public static final int TYPE_EXPENSE = 2;

    private static final String PREFS_NAME = "record_store";
    private static final String KEY_RECORDS = "records";

    /** 一条记账记录。 */
    public static final class Record {
        public final int type;
        public final double amount;
        public final String note;
        /**
         * 记账所属的日期（毫秒，当天 0 点）。
         *
         * <p>取用户选择的日期而非保存时刻，
         * 这样补记前几天的账时统计归属正确。
         */
        public final long timeMillis;
        /** 分类名称，如"餐饮""工资"。 */
        public final String category;
        /** 账户名称，如"现金""微信"。 */
        public final String account;

        public Record(int type, double amount, String note, long timeMillis,
                      String category, String account) {
            this.type = type;
            this.amount = amount;
            this.note = note == null ? "" : note;
            this.timeMillis = timeMillis;
            this.category = category == null ? "" : category;
            this.account = account == null ? "" : account;
        }
    }

    private static final List<Record> RECORDS = new ArrayList<>();
    private static SharedPreferences prefs;
    /** 是否已完成初始化并从磁盘加载。 */
    private static boolean loaded;

    private RecordStore() {
        // 工具类，禁止实例化
    }

    /**
     * 初始化并加载磁盘数据。应用启动时调用一次即可。
     */
    public static synchronized void init(Context context) {
        if (loaded) return;
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        RECORDS.clear();
        RECORDS.addAll(readFromDisk());
        loaded = true;
    }

    /** 添加一条记录并落盘。 */
    public static synchronized void add(Record record) {
        RECORDS.add(record);
        saveToDisk();
    }

    /** 按类型汇总金额。 */
    public static synchronized double totalOf(int type) {
        double sum = 0;
        for (Record r : RECORDS) {
            if (r.type == type) {
                sum += r.amount;
            }
        }
        return sum;
    }

    /** 全部记录（按时间倒序，最新的在前）。 */
    public static synchronized List<Record> getAll() {
        List<Record> copy = new ArrayList<>(RECORDS);
        Collections.sort(copy, (a, b) -> Long.compare(b.timeMillis, a.timeMillis));
        return Collections.unmodifiableList(copy);
    }

    /** 记录条数。 */
    public static synchronized int size() {
        return RECORDS.size();
    }

    /** 清空全部记录。 */
    public static synchronized void clear() {
        RECORDS.clear();
        saveToDisk();
    }

    // ==================================================================
    // 磁盘读写
    // ==================================================================

    /** 把内存列表写成 JSON 数组。 */
    private static void saveToDisk() {
        if (prefs == null) return;

        JSONArray arr = new JSONArray();
        for (Record r : RECORDS) {
            JSONObject o = new JSONObject();
            try {
                o.put("type", r.type);
                o.put("amount", r.amount);
                o.put("note", r.note);
                o.put("time", r.timeMillis);
                o.put("category", r.category);
                o.put("account", r.account);
                arr.put(o);
            } catch (JSONException e) {
                // 单条失败不影响其余记录
            }
        }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply();
    }

    /** 从 JSON 数组读回内存列表。 */
    private static List<Record> readFromDisk() {
        List<Record> list = new ArrayList<>();
        if (prefs == null) return list;

        String json = prefs.getString(KEY_RECORDS, null);
        if (json == null || json.isEmpty()) return list;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                list.add(new Record(
                        o.optInt("type", TYPE_EXPENSE),
                        o.optDouble("amount", 0),
                        o.optString("note", ""),
                        o.optLong("time", 0),
                        o.optString("category", ""),
                        o.optString("account", "")));
            }
        } catch (JSONException e) {
            // 数据损坏时从空开始，避免整个 App 崩溃
        }
        return list;
    }
}
