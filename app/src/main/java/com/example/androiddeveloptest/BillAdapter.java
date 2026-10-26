package com.example.androiddeveloptest;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 账单列表适配器。
 *
 * <p>每个条目展示：分类首字圆标、分类名、日期+账户、金额。
 * 支出为红色带负号，收入为绿色带正号。
 */
public class BillAdapter extends BaseAdapter {

    /** 分类对应的强调色，按名称匹配，未命中用默认色。 */
    private static final String[] CATEGORY_KEYS = {
            "餐饮", "交通", "购物", "娱乐", "居住", "医疗", "学习", "更多",
            "工资", "奖金", "理财", "兼职", "红包", "其他"
    };
    private static final int[] CATEGORY_COLORS = {
            0xFFF5A623, 0xFF3D9BE9, 0xFFE05A8C, 0xFF8B5CF6,
            0xFF2FB88A, 0xFFE5484D, 0xFF3B4E87, 0xFF9A9A9A,
            0xFF2FB88A, 0xFFF5A623, 0xFF3D9BE9, 0xFF8B5CF6,
            0xFFE05A8C, 0xFF9A9A9A
    };
    private static final int[] CATEGORY_BGS = {
            0xFFFFF4E0, 0xFFE8F4FD, 0xFFFDE8F0, 0xFFF0E8FD,
            0xFFE0F7EF, 0xFFFDE8E8, 0xFFE8EDF7, 0xFFF0F0F0,
            0xFFE0F7EF, 0xFFFFF4E0, 0xFFE8F4FD, 0xFFF0E8FD,
            0xFFFDE8F0, 0xFFF0F0F0
    };

    private final Context context;
    private final List<RecordStore.Record> data = new ArrayList<>();

    public BillAdapter(Context context) {
        this.context = context;
    }

    /** 更新数据。 */
    public void setData(List<RecordStore.Record> records) {
        data.clear();
        if (records != null) {
            data.addAll(records);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public RecordStore.Record getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                    .inflate(R.layout.item_bill, parent, false);
            holder = new ViewHolder();
            holder.icon = convertView.findViewById(R.id.tvItemIcon);
            holder.category = convertView.findViewById(R.id.tvItemCategory);
            holder.sub = convertView.findViewById(R.id.tvItemSub);
            holder.amount = convertView.findViewById(R.id.tvItemAmount);
            holder.divider = convertView.findViewById(R.id.viewDivider);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        RecordStore.Record r = data.get(position);
        int colorIndex = indexOfCategory(r.category);

        // 分类圆标：首字 + 对应底色
        holder.icon.setText(r.category.isEmpty() ? "?" : r.category.substring(0, 1));
        holder.icon.setTextColor(CATEGORY_COLORS[colorIndex]);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(CATEGORY_BGS[colorIndex]);
        holder.icon.setBackground(bg);

        holder.category.setText(r.category.isEmpty() ? "未分类" : r.category);

        // 副标题：日期 · 账户
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(r.timeMillis);
        String dateStr = (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
        String account = r.account.isEmpty() ? "" : " · " + r.account;
        holder.sub.setText(dateStr + account);

        // 金额：支出红负号，收入绿正号
        boolean income = (r.type == RecordStore.TYPE_INCOME);
        holder.amount.setTextColor(income ? 0xFF3DA35D : 0xFFE5484D);
        holder.amount.setText((income ? "+" : "-") + formatAmount(r.amount));

        // 最后一项不画分割线
        holder.divider.setVisibility(
                position == data.size() - 1 ? View.GONE : View.VISIBLE);

        return convertView;
    }

    /** 金额格式化：整数不带小数，否则保留两位。 */
    private String formatAmount(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format(java.util.Locale.getDefault(), "%.2f", v);
    }

    /** 找分类对应的配色下标。 */
    private int indexOfCategory(String name) {
        for (int i = 0; i < CATEGORY_KEYS.length; i++) {
            if (CATEGORY_KEYS[i].equals(name)) {
                return i;
            }
        }
        return CATEGORY_KEYS.length - 1;
    }

    private static class ViewHolder {
        TextView icon;
        TextView category;
        TextView sub;
        TextView amount;
        View divider;
    }
}
