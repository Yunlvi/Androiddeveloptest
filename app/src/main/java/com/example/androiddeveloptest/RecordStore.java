package com.example.androiddeveloptest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记账数据的临时内存存储。
 *
 * <p>App 关闭后数据丢失，后续可替换为 Room 而无需修改调用方。
 */
public final class RecordStore {

    /** 收入。 */
    public static final int TYPE_INCOME = 1;
    /** 支出。 */
    public static final int TYPE_EXPENSE = 2;

    /** 一条记账记录。 */
    public static final class Record {
        public final int type;
        public final double amount;
        public final String note;
        public final long timeMillis;

        public Record(int type, double amount, String note, long timeMillis) {
            this.type = type;
            this.amount = amount;
            this.note = note;
            this.timeMillis = timeMillis;
        }
    }

    private static final List<Record> RECORDS = new ArrayList<>();

    private RecordStore() {
        // 工具类，禁止实例化
    }

    /** 添加一条记录。 */
    public static void add(Record record) {
        RECORDS.add(record);
    }

    /** 按类型汇总金额。 */
    public static double totalOf(int type) {
        double sum = 0;
        for (Record r : RECORDS) {
            if (r.type == type) {
                sum += r.amount;
            }
        }
        return sum;
    }

    /** 全部记录（只读）。 */
    public static List<Record> getAll() {
        return Collections.unmodifiableList(RECORDS);
    }

    /** 记录条数。 */
    public static int size() {
        return RECORDS.size();
    }
}