package com.ziji.ledger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SettingsActivity extends Activity {
    // 配色参考 fanqie 的暖灰 + 赤陶体系：底色略暖，卡片近白，
    // 品牌色用赤陶（而非常见的蓝/绿），整体呈现安静的纸质感。
    private static final int COLOR_HEADER = Color.rgb(236, 232, 224);
    private static final int COLOR_BG = Color.rgb(236, 232, 224);
    private static final int COLOR_SURFACE = Color.rgb(254, 253, 250);
    private static final int COLOR_BORDER = Color.rgb(222, 216, 206);
    private static final int COLOR_BORDER_SOFT = Color.rgb(234, 229, 220);
    private static final int COLOR_TEXT = Color.rgb(42, 46, 50);
    private static final int COLOR_MUTED = Color.rgb(88, 95, 101);
    private static final int COLOR_FAINT = Color.rgb(146, 152, 158);
    private static final int COLOR_PRIMARY = Color.rgb(164, 67, 45);
    private static final int COLOR_PRIMARY_DARK = Color.rgb(126, 50, 33);
    private static final int COLOR_PRIMARY_SOFT = Color.rgb(246, 221, 211);
    private static final int COLOR_SECONDARY = Color.rgb(226, 221, 212);
    private static final int COLOR_DANGER = Color.rgb(155, 50, 38);
    private static final int COLOR_EXPENSE = Color.rgb(164, 67, 45);
    /** 收入用低饱和绿，与暖色底和谐，不与赤陶品牌色混淆。 */
    private static final int COLOR_INCOME = Color.rgb(47, 122, 95);
    private static final int COLOR_INCOME_SOFT = Color.rgb(224, 238, 229);
    private static final int COLOR_EXPENSE_SOFT = Color.rgb(246, 226, 219);

    private static final int RADIUS_CARD = 16;
    private static final int RADIUS_BUTTON = 12;
    private static final int RADIUS_CHIP = 999;

    private TextView monthAmountView;
    private TextView todayAmountView;
    private TextView recordCountView;
    /** 首页金额卡片的标题：看当月是「本月支出」，看历史月份是「9月支出」。 */
    private TextView summaryLabelView;
    /**
     * 首页「自动记账已开启」状态的动态标题。
     *
     * 以前这里硬编码「自动记账已开启」，权限被 ROM 静默关闭后首页仍然宣称已开启，
     * 用户完全不知道漏记账了——真实状态只在「自动记」标签页里。现在接到
     * 两个权限检测的结果上，首页直接反映真实状态。
     */
    private TextView insightTitleView;
    /** 权限告警弹窗的引用：防止 onResume 反复触发时叠出多个弹窗。 */
    private android.app.AlertDialog permissionDialog;
    /**
     * 首页与统计页共同查看的月份（已归一化到当月 1 号 0 点）。
     * 记账产品只有「本月」是不够的：想看上月花了多少、和上月比，得有月份维度。
     */
    private Calendar viewMonth;
    /** 首页「自动记账已开启」右侧的动态指标：多少笔已经知道用途了。 */
    private TextView purposeRateView;
    private TextView notificationAccessView;
    private TextView listenerStatusView;
    private TextView sampleStatsView;
    private TextView lastCaptureView;
    private TextView accessibilityAccessView;
    private TextView accessibilityStatusView;
    private LinearLayout recentContainer;
    /** 「还有 N 笔没写清用途」提示条的挂载点。 */
    private LinearLayout pendingPurposesContainer;
    private LinearLayout contentContainer;
    private final List<View> navItems = new ArrayList<>();
    private EditText searchInput;
    private String directionFilter = "all";
    private String pendingRestoreBackup;
    private static final int REQUEST_CREATE_BACKUP = 701;
    private static final int REQUEST_OPEN_BACKUP = 702;
    private static final int REQUEST_CREATE_CSV = 703;
    private static final int REQUEST_OPEN_CSV = 704;
    private int selectedTab = 0;

    /** 明细列表只展示最近这么多条，筛选凑满即可停。 */
    private static final int RECENT_LIST_LIMIT = 30;

    /**
     * 搜索框防抖。
     *
     * 明细列表每次重建都要新建 30 张卡片（每张约 8 个 View），
     * 直接在 onTextChanged 里重建等于「每敲一个字重排 240 个 View」，
     * 在中低端机上输入会明显掉帧。这里等用户停顿后再重建一次。
     */
    private static final long SEARCH_DEBOUNCE_MILLIS = 180L;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable rebuildListRunnable = this::updateLocalLedger;

    /**
     * 分类 → 图标资源名（Lucide，ISC 许可）。
     *
     * 早期版本用 emoji（🍜🎮🛍）当图标，问题是：
     * 1. 各 ROM 的 emoji 字形不同，同一分类在不同手机上"长相"不一；
     * 2. emoji 是彩色卡通风格，与整体的暖灰+赤陶克制审美冲突。
     * 改为内置矢量图后，描边粗细、圆角、配色都由代码统一控制。
     */
    /**
     * 分类表不在这里定义，唯一副本在 {@link LedgerCategories}。
     *
     * 这个文件以前另抄了一份：网格写「住房」「教育」，解析器写「居家」「学习」，
     * 而且网格里没有「医疗」「其他」「收入」。结果自动归类的账点开编辑时
     * 一个格子都不亮，用户随手一点就把分类改了名，统计跟着裂成两桶。
     */
    private static final String[][] EXPENSE_CATEGORY_OPTIONS = LedgerCategories.EXPENSE;
    private static final String[][] INCOME_CATEGORY_OPTIONS = LedgerCategories.INCOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(COLOR_HEADER);
        getWindow().setNavigationBarColor(COLOR_SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(COLOR_BG);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(20), dp(18), dp(20), dp(24));
        scrollView.addView(contentContainer, fullWidth());
        shell.addView(scrollView, weightedHeight());
        shell.addView(buildBottomNav(), fullWidth());

        setContentView(shell);
        // 早期版本分类有两套名字（住房/居家、教育/学习），启动时归一一次。
        // 幂等：没有需要改的记录就不写盘，不会每次开 App 都产生 IO。
        LocalLedgerStore.migrateCategoryAliases(this);
        viewMonth = currentMonth();
        showTab(0);
        updateAllViews();
    }

    /** 当月 1 号 0 点，作为所有「按月」查询的起点。 */
    private static Calendar currentMonth() {
        Calendar month = Calendar.getInstance();
        month.set(Calendar.DAY_OF_MONTH, 1);
        month.set(Calendar.HOUR_OF_DAY, 0);
        month.set(Calendar.MINUTE, 0);
        month.set(Calendar.SECOND, 0);
        month.set(Calendar.MILLISECOND, 0);
        return month;
    }

    private long monthStartMillis() {
        Calendar month = viewMonth == null ? currentMonth() : (Calendar) viewMonth.clone();
        return month.getTimeInMillis();
    }

    private long monthEndMillis() {
        Calendar next = viewMonth == null ? currentMonth() : (Calendar) viewMonth.clone();
        next.add(Calendar.MONTH, 1);
        return next.getTimeInMillis();
    }

    private boolean viewingCurrentMonth() {
        if (viewMonth == null) return true;
        return viewMonth.get(Calendar.YEAR) == currentMonth().get(Calendar.YEAR)
                && viewMonth.get(Calendar.MONTH) == currentMonth().get(Calendar.MONTH);
    }

    private void shiftMonth(int delta) {
        Calendar target = viewMonth == null ? currentMonth() : (Calendar) viewMonth.clone();
        target.add(Calendar.MONTH, delta);
        // 未来的月份没有账可看，直接停在本月。
        if (target.after(currentMonth())) return;
        viewMonth = target;
        showTab(selectedTab);
    }

    private String monthTitle() {
        Calendar month = viewMonth == null ? currentMonth() : viewMonth;
        return month.get(Calendar.YEAR) + "年" + (month.get(Calendar.MONTH) + 1) + "月";
    }

    /**
     * 月份切换条。左右箭头 + 当前月份，非本月时提供「回到本月」。
     * 付款完成后那一刻的账未必马上看，用户更常看的是「上个月到底花了多少」。
     */
    private View buildMonthSwitcher() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(8), dp(8), dp(8));
        bar.setBackground(rounded(COLOR_SURFACE, COLOR_BORDER, RADIUS_BUTTON));

        View prev = monthArrow("ic_chevron", 180f, "上一个月", -1);
        bar.addView(prev, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView title = new TextView(this);
        title.setText(monthTitle());
        title.setTextSize(14);
        title.setTextColor(COLOR_TEXT);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View next = monthArrow("ic_chevron", 0f, "下一个月", 1);
        next.setEnabled(!viewingCurrentMonth());
        next.setAlpha(viewingCurrentMonth() ? 0.3f : 1f);
        bar.addView(next, new LinearLayout.LayoutParams(dp(32), dp(32)));

        if (!viewingCurrentMonth()) {
            TextView back = new TextView(this);
            back.setText("回到本月");
            back.setTextSize(12);
            back.setTextColor(COLOR_PRIMARY);
            back.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            back.setPadding(dp(10), dp(4), dp(6), dp(4));
            back.setClickable(true);
            back.setFocusable(true);
            back.setOnClickListener((view) -> {
                viewMonth = currentMonth();
                showTab(selectedTab);
            });
            bar.addView(back, wrapContent());
        }
        return bar;
    }

    private View monthArrow(String iconName, float rotation, String description, int delta) {
        ImageView arrow = iconView(iconName, COLOR_MUTED, 18);
        arrow.setRotation(rotation);
        arrow.setContentDescription(description);
        arrow.setPadding(dp(7), dp(7), dp(7), dp(7));
        arrow.setClickable(true);
        arrow.setFocusable(true);
        arrow.setOnClickListener((view) -> shiftMonth(delta));
        return arrow;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAllViews();
        // 每次进入 App 都主动核对两个采集权限。部分 ROM（MIUI/EMUI 等）清理后台时
        // 会静默关闭无障碍/通知权限，且不回调任何生命周期方法——用户打开 App 的
        // 这一刻是唯一可靠的感知点，权限缺失必须立刻提醒。
        maybeShowPermissionAlert();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_CREATE_BACKUP) {
            writeBackup(uri);
        } else if (requestCode == REQUEST_OPEN_BACKUP) {
            readBackup(uri);
        } else if (requestCode == REQUEST_CREATE_CSV) {
            writeCsv(uri);
        } else if (requestCode == REQUEST_OPEN_CSV) {
            readCsv(uri);
        }
    }

    private void writeBackup(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("backup output unavailable");
            output.write(LedgerAppStorage.repository(this).exportBackup().getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "账本备份已保存。", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "备份保存失败，请重试。", Toast.LENGTH_LONG).show();
        }
    }

    private void readBackup(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("backup input unavailable");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) >= 0) buffer.write(bytes, 0, count);
            pendingRestoreBackup = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            new AlertDialog.Builder(this)
                    .setTitle("恢复这份备份？")
                    .setMessage("恢复会覆盖当前手机上的账单、规则和处理记录。")
                    .setPositiveButton("恢复", (dialog, which) -> {
                        boolean restored = LedgerAppStorage.repository(this).importBackup(pendingRestoreBackup);
                        pendingRestoreBackup = null;
                        if (restored) {
                            showTab(selectedTab);
                            Toast.makeText(this, "账本已恢复。", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "备份文件格式不正确。", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", (dialog, which) -> pendingRestoreBackup = null)
                    .show();
        } catch (Exception error) {
            Toast.makeText(this, "备份读取失败，请选择自记备份文件。", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        // 防抖任务持有 Activity 引用，退出时必须撤掉，否则页面销毁后还会回调一次。
        uiHandler.removeCallbacks(rebuildListRunnable);
        super.onDestroy();
    }

    private View buildSummaryCard() {
        LinearLayout card = verticalCard(COLOR_SURFACE, COLOR_BORDER);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));

        summaryLabelView = label("本月支出");
        summaryLabelView.setTextSize(13);
        summaryLabelView.setLetterSpacing(0.06f);
        card.addView(summaryLabelView, fullWidth());

        monthAmountView = new TextView(this);
        monthAmountView.setTextSize(40);
        monthAmountView.setTextColor(COLOR_TEXT);
        monthAmountView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        monthAmountView.setLetterSpacing(-0.03f);
        monthAmountView.setPadding(0, dp(6), 0, dp(16));
        card.addView(monthAmountView, fullWidth());

        // 细分隔线，替代原来的"空白间隔"，让指标区与金额区有清晰边界。
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_BORDER_SOFT);
        card.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setPadding(0, dp(14), 0, 0);
        todayAmountView = metricView();
        recordCountView = metricView();
        metrics.addView(todayAmountView, weighted());
        metrics.addView(recordCountView, weighted());
        card.addView(metrics, fullWidth());
        return card;
    }

    private View buildInsightCard() {
        LinearLayout card = verticalCard(COLOR_PRIMARY_SOFT, Color.TRANSPARENT);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setElevation(0f);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = iconView("ic_check", COLOR_PRIMARY, 17);
        row.addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));
        insightTitleView = label("自动记账已开启");
        insightTitleView.setTextSize(14);
        insightTitleView.setTextColor(COLOR_PRIMARY_DARK);
        insightTitleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(insightTitleView, weighted());
        // 这里不只说「已开启」，还要说清楚自动化到底做到了什么程度：
        // 「22/24 笔已知用途」比任何「已开启」都更能回答「到底全不全自动」。
        purposeRateView = label("通知到账后自动记录");
        purposeRateView.setTextSize(12);
        purposeRateView.setTextColor(COLOR_MUTED);
        row.addView(purposeRateView, wrapContent());
        card.addView(row, fullWidth());
        return card;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(COLOR_SURFACE);
        nav.setPadding(dp(10), dp(8), dp(10), dp(10));
        nav.setElevation(dp(12));
        addNavItem(nav, "ic_nav_list", "明细", 0);
        addNavItem(nav, "ic_nav_auto", "自动记", 1);

        // 中间的新增按钮：圆形赤陶底 + 白色加号图标。
        android.widget.FrameLayout add = new android.widget.FrameLayout(this);
        add.setBackground(rounded(COLOR_PRIMARY, Color.TRANSPARENT, RADIUS_CHIP));
        applySoftShadow(add);
        add.setClickable(true);
        add.setFocusable(true);
        add.setContentDescription("新增一笔");
        ImageView plus = iconView("ic_add", Color.WHITE, 24);
        android.widget.FrameLayout.LayoutParams plusParams = new android.widget.FrameLayout.LayoutParams(
                dp(24), dp(24));
        plusParams.gravity = Gravity.CENTER;
        add.addView(plus, plusParams);
        add.setOnClickListener((view) -> showManualEntryDialog());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        addParams.setMargins(dp(10), 0, dp(10), 0);
        nav.addView(add, addParams);

        addNavItem(nav, "ic_nav_stats", "统计", 2);
        addNavItem(nav, "ic_nav_me", "我的", 3);
        return nav;
    }

    private void addNavItem(LinearLayout nav, String iconName, String text, int tab) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(4), dp(4), dp(4));
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(text);
        item.setOnClickListener((view) -> showTab(tab));

        ImageView iconView = iconView(iconName, COLOR_FAINT, 21);
        item.addView(iconView, fullWidth());

        TextView labelView = new TextView(this);
        labelView.setText(text);
        labelView.setTextSize(11);
        labelView.setGravity(Gravity.CENTER);
        item.addView(labelView, fullWidth());

        item.setTag(new Object[] {iconView, labelView});
        navItems.add(item);
        nav.addView(item, new LinearLayout.LayoutParams(0, dp(54), 1f));
    }

    private void updateNavState() {
        for (int index = 0; index < navItems.size(); index += 1) {
            View item = navItems.get(index);
            Object[] parts = (Object[]) item.getTag();
            ImageView iconView = (ImageView) parts[0];
            TextView labelView = (TextView) parts[1];
            boolean selected = index == selectedTab;

            // 图标着色跟随选中态；未选中用低对比灰，选中用赤陶。
            iconView.setColorFilter(selected ? COLOR_PRIMARY : COLOR_FAINT, PorterDuff.Mode.SRC_IN);
            labelView.setTextColor(selected ? COLOR_PRIMARY_DARK : COLOR_MUTED);
            labelView.setTypeface(Typeface.create(
                    selected ? "sans-serif-medium" : "sans-serif",
                    Typeface.NORMAL
            ));
            // 选中态用浅赤陶药丸底，替代原来的整块色块，观感更轻。
            item.setBackground(selected
                    ? rounded(COLOR_PRIMARY_SOFT, Color.TRANSPARENT, RADIUS_BUTTON)
                    : null);
        }
    }

    private void showTab(int tab) {
        selectedTab = tab;
        contentContainer.removeAllViews();
        if (tab == 0) {
            contentContainer.addView(buildHomeHeader(), fullWidthWithBottom(dp(14)));
            contentContainer.addView(buildMonthSwitcher(), cardParams(dp(10)));
            contentContainer.addView(buildSummaryCard(), cardParams(dp(14)));
            contentContainer.addView(buildInsightCard(), cardParams(dp(18)));
            contentContainer.addView(buildFilterBar(), cardParams(dp(16)));
            // 待补用途提示条：有欠账时才有内容，其余时候高度为 0。
            pendingPurposesContainer = new LinearLayout(this);
            pendingPurposesContainer.setOrientation(LinearLayout.VERTICAL);
            contentContainer.addView(pendingPurposesContainer, fullWidth());
            contentContainer.addView(sectionHeader("账单明细", "点击账单可修改分类与用途"), fullWidthWithBottom(dp(10)));
            recentContainer = new LinearLayout(this);
            recentContainer.setOrientation(LinearLayout.VERTICAL);
            contentContainer.addView(recentContainer, fullWidth());
            updateLocalLedger();
        } else if (tab == 1) {
            contentContainer.addView(sectionHeader("自动记", "支付通知识别后会自动进入账本"), fullWidthWithBottom(dp(12)));
            contentContainer.addView(buildPermissionCard(), cardParams(dp(14)));
            contentContainer.addView(buildAccessibilityCard(), fullWidth());
        } else if (tab == 2) {
            contentContainer.addView(buildStatsView(), fullWidth());
        } else {
            contentContainer.addView(buildProfileView(), fullWidth());
        }
        updateNavState();
        updateNotificationAccessStatus();
    }

    private View buildFilterBar() {
        LinearLayout filter = new LinearLayout(this);
        filter.setOrientation(LinearLayout.VERTICAL);
        searchInput = input("搜索商户、分类或备注");
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                uiHandler.removeCallbacks(rebuildListRunnable);
                uiHandler.postDelayed(rebuildListRunnable, SEARCH_DEBOUNCE_MILLIS);
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        filter.addView(searchInput, fullWidthWithBottom(dp(8)));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        addFilterChip(chips, "全部", "all");
        addFilterChip(chips, "支出", "expense");
        addFilterChip(chips, "收入", "income");
        filter.addView(chips, fullWidth());
        return filter;
    }

    private void addFilterChip(LinearLayout parent, String text, String value) {
        TextView chip = choiceChip(text, value.equals(directionFilter));
        chip.setTextSize(13);
        chip.setMinHeight(dp(38));
        chip.setOnClickListener((view) -> {
            directionFilter = value;
            for (int index = 0; index < parent.getChildCount(); index += 1) {
                TextView item = (TextView) parent.getChildAt(index);
                styleChoice(item, item == chip);
            }
            updateLocalLedger();
        });
        parent.addView(chip, weightedWithEnd(dp(6)));
    }

    private View buildHomeHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("本月概览");
        title.setTextSize(26);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(-0.02f);
        header.addView(title, weighted());

        TextView date = label(new SimpleDateFormat("M月d日 · 今日", Locale.CHINA).format(System.currentTimeMillis()));
        date.setTextSize(12);
        date.setTextColor(COLOR_MUTED);
        date.setGravity(Gravity.CENTER);
        date.setPadding(dp(13), dp(7), dp(13), dp(7));
        date.setBackground(rounded(COLOR_SURFACE, COLOR_BORDER, RADIUS_CHIP));
        header.addView(date, wrapContent());
        return header;
    }

    private View buildStatsView() {
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        stats.addView(sectionHeader("收支统计", "按月回看钱到底花在哪"), fullWidthWithBottom(dp(10)));
        stats.addView(buildMonthSwitcher(), cardParams(dp(12)));

        long start = monthStartMillis();
        long end = monthEndMillis();
        // 全量区间查询：账本超过读取上限后，基于 LocalLedger 的统计会偏小。
        List<LedgerEntry> monthEntries = LocalLedgerStore.entriesBetween(this, start, end);
        // 退款归因必须看全量账本：9 月底买的、10 月初退的，原交易不在本月切片里，
        // 只拿本月记录会匹配不上，退款就被算成「10 月负支出」。
        List<LedgerEntry> allEntries = LocalLedgerStore.entriesBetween(this, 0L, Long.MAX_VALUE);
        RefundOffset summary = RefundOffset.ofMonth(allEntries, start, end);

        Calendar cursor = (Calendar) viewMonth.clone();
        int days = cursor.getActualMaximum(Calendar.DAY_OF_MONTH);
        // 趋势按「每笔支出落在它自己那天」画：退款扣在**原交易日**，不是退款到账日。
        // 这样跨月退款不会在退款当月造出一根负柱，也不会有柱子加起来对不上总额。
        long[] netByDay = new long[days];
        for (LedgerEntry entry : monthEntries) {
            if (!entry.isExpense()) continue;
            int dayIndex = dayOfMonth(entry.occurredAtMillis, days);
            if (dayIndex < 0) continue;
            long refunded = summary.refundedByOrigin.getOrDefault(entry.id, 0L);
            netByDay[dayIndex] += entry.amountCents - refunded;
        }
        // 找不到原交易的退款，留在它自己发生的那天（通常是上个月没记全的孤立退款）。
        for (LedgerEntry refund : summary.unmatchedRefunds) {
            if (refund.occurredAtMillis < start || refund.occurredAtMillis >= end) continue;
            int dayIndex = dayOfMonth(refund.occurredAtMillis, days);
            if (dayIndex < 0) continue;
            netByDay[dayIndex] -= refund.amountCents;
        }

        LinearLayout overview = verticalCard(COLOR_SURFACE, COLOR_BORDER);
        overview.addView(sectionHeader(
                "收支总览",
                viewingCurrentMonth() ? "本月累计" : monthTitle() + "累计"
        ), fullWidthWithBottom(dp(8)));
        long balance = summary.incomeCents - summary.netExpenseCents;
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.addView(statMetric("支出", "¥" + formatYuan(summary.netExpenseCents), COLOR_EXPENSE), weightedWithEnd(dp(5)));
        metrics.addView(statMetric("收入", "¥" + formatYuan(summary.incomeCents), COLOR_INCOME), weightedWithStart(dp(5)));
        metrics.addView(statMetric("结余", "¥" + formatYuan(balance), balance >= 0 ? COLOR_INCOME : COLOR_EXPENSE), weightedWithStart(dp(10)));
        overview.addView(metrics, fullWidth());
        if (summary.refundCents > 0) {
            // 退款不写在明面上，用户会觉得「我明明退了，怎么支出没变」。
            TextView refundNote = label("退款 ¥" + formatYuan(summary.refundCents) + " 已从支出中扣除，不计入收入");
            refundNote.setTextSize(11);
            refundNote.setTextColor(COLOR_MUTED);
            refundNote.setPadding(0, dp(10), 0, 0);
            overview.addView(refundNote, fullWidth());
        }
        stats.addView(overview, cardParams(dp(12)));

        LinearLayout trendCard = verticalCard(COLOR_SURFACE, COLOR_BORDER);
        trendCard.addView(sectionHeader("每日趋势", "每天的实际支出，已扣退款"), fullWidthWithBottom(dp(6)));
        trendCard.addView(new TrendChartView(this, netByDay, COLOR_PRIMARY), fullWidthWithBottom(dp(4)));
        TextView trendLegend = label("支出   " + formatYuan(summary.netExpenseCents) + "       收入   " + formatYuan(summary.incomeCents));
        trendLegend.setTextSize(12);
        trendLegend.setTextColor(COLOR_MUTED);
        trendCard.addView(trendLegend, fullWidth());
        stats.addView(trendCard, cardParams(dp(12)));

        LinearLayout categoriesCard = verticalCard(COLOR_SURFACE, COLOR_BORDER);
        categoriesCard.addView(sectionHeader("分类排行榜", "支出按金额从高到低"), fullWidthWithBottom(dp(8)));
        List<Map.Entry<String, Long>> ranked = new ArrayList<>(summary.categoryTotals.entrySet());
        Collections.sort(ranked, (left, right) -> Long.compare(right.getValue(), left.getValue()));
        int rank = 1;
        for (Map.Entry<String, Long> item : ranked) {
            categoriesCard.addView(
                    buildRankRow(rank++, item.getKey(), item.getValue(), summary.netExpenseCents),
                    fullWidth()
            );
        }
        if (ranked.isEmpty()) categoriesCard.addView(label("这个月还没有支出记录。"), fullWidth());
        stats.addView(categoriesCard, cardParams(dp(12)));

        LinearLayout detailsCard = verticalCard(COLOR_SURFACE, COLOR_BORDER);
        detailsCard.addView(sectionHeader("最近明细", "这个月最近 8 笔"), fullWidthWithBottom(dp(8)));
        int detailCount = Math.min(monthEntries.size(), 8);
        for (int index = 0; index < detailCount; index += 1) {
            detailsCard.addView(buildDetailRow(monthEntries.get(index)), fullWidth());
        }
        if (detailCount == 0) detailsCard.addView(label("这个月还没有账单。"), fullWidth());
        stats.addView(detailsCard, fullWidth());
        return stats;
    }

    /**
     * 统计页的单条明细。
     *
     * 原实现用一整行字符串 + 手写空格对齐金额，只有等宽字体才对得齐，
     * 且分类图标是 emoji。改为「图标 + 权重布局 + 右对齐金额」后与账单页一致。
     */
    private View buildDetailRow(LedgerEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        ImageView icon = iconView(
                categoryIconName(entry.category),
                entry.isExpense() ? COLOR_EXPENSE : COLOR_INCOME,
                19
        );
        row.addView(icon, new LinearLayout.LayoutParams(dp(19), dp(19)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        TextView name = new TextView(this);
        name.setText(entry.merchant.equals("未知商户") ? entry.category : entry.merchant);
        name.setTextSize(14);
        name.setTextColor(COLOR_TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name, fullWidth());
        TextView meta = new TextView(this);
        meta.setText(entry.category + " · " + formatDate(entry.occurredAtMillis));
        meta.setTextSize(11);
        meta.setTextColor(COLOR_MUTED);
        meta.setPadding(0, dp(3), 0, 0);
        mid.addView(meta, fullWidth());
        row.addView(mid, weighted());

        TextView amount = new TextView(this);
        boolean isRefund = RefundOffset.REFUND_CATEGORY.equals(entry.category);
        if (isRefund) {
            amount.setText("退 ¥" + formatYuan(entry.amountCents));
        } else {
            amount.setText((entry.isExpense() ? "−" : "+") + "¥" + formatYuan(entry.amountCents));
        }
        amount.setTextSize(15);
        amount.setTextColor(entry.isExpense() ? COLOR_EXPENSE : COLOR_INCOME);
        amount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        amount.setGravity(Gravity.END);
        row.addView(amount, wrapContent());
        return row;
    }

    private View statMetric(String title, String value, int color) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = label(title);
        titleView.setTextSize(12);
        titleView.setLetterSpacing(0.05f);
        block.addView(titleView, fullWidth());
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(20);
        valueView.setTextColor(color);
        valueView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        valueView.setLetterSpacing(-0.02f);
        valueView.setPadding(0, dp(5), 0, 0);
        block.addView(valueView, fullWidth());
        return block;
    }

    private View buildRankRow(int rank, String category, long amount, long total) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        // 序号改用小号赤陶数字，贴合 fanqie 的 "01 / 02" 编号语言。
        TextView number = label(String.format(Locale.CHINA, "%02d", rank));
        number.setTextSize(12);
        number.setTextColor(COLOR_PRIMARY);
        number.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        number.setLetterSpacing(0.05f);
        number.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(number, new LinearLayout.LayoutParams(dp(28), dp(44)));

        LinearLayout nameBlock = new LinearLayout(this);
        nameBlock.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(category);
        name.setTextSize(15);
        name.setTextColor(COLOR_TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nameBlock.addView(name, fullWidth());

        android.widget.ProgressBar bar = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        // 分类合计可能是负数（退款没匹配到原支出时会挂在「退款」项下），
        // 进度条不认负数，压到 0 即可，金额本身照常显示。
        bar.setProgress(total > 0 ? (int) Math.max(0L, Math.min(1000L, amount * 1000L / total)) : 0);
        bar.setProgressDrawable(progressDrawable());
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        barParams.topMargin = dp(6);
        nameBlock.addView(bar, barParams);
        row.addView(nameBlock, weightedWithStart(dp(10)));

        TextView value = label("¥" + formatYuan(amount));
        value.setTextSize(15);
        value.setTextColor(COLOR_TEXT);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(value, wrapContent());
        return row;
    }

    /**
     * 排行榜的细条比例尺：浅底轨 + 赤陶进度。
     * ProgressBar 的 progressDrawable 需要 LayerDrawable 才能同时表达「轨道」和「进度」，
     * 单层 GradientDrawable 只会画出整条实心，看不出比例。
     */
    private android.graphics.drawable.LayerDrawable progressDrawable() {
        GradientDrawable track = new GradientDrawable();
        track.setShape(GradientDrawable.RECTANGLE);
        track.setCornerRadius(dp(2));
        track.setColor(COLOR_BORDER_SOFT);

        GradientDrawable progress = new GradientDrawable();
        progress.setShape(GradientDrawable.RECTANGLE);
        progress.setCornerRadius(dp(2));
        progress.setColor(COLOR_PRIMARY);

        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {track, progress});
        // id 必须用系统约定的 progress id，否则 ProgressBar 找不到进度层。
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    private View buildProfileView() {
        LinearLayout profile = new LinearLayout(this);
        profile.setOrientation(LinearLayout.VERTICAL);
        profile.addView(sectionHeader("我的", "本地账本与应用信息"), fullWidthWithBottom(dp(12)));
        LinearLayout card = verticalCard(COLOR_SURFACE, COLOR_BORDER);
        card.addView(label("账单数据保存在这台手机\n自动记账只需要通知读取权限，手动记账不依赖网络。"), fullWidth());
        Button diagnostics = secondaryButton("查看自动记账处理记录", "ic_history");
        diagnostics.setOnClickListener((view) -> showCaptureHistory());
        card.addView(diagnostics, fullWidthWithTop(dp(16)));
        Button rules = secondaryButton("管理分类规则", "ic_rules");
        rules.setOnClickListener((view) -> showRulesDialog());
        card.addView(rules, fullWidthWithTop(dp(8)));
        Button backup = secondaryButton("备份账本", "ic_backup");
        backup.setOnClickListener((view) -> createBackup());
        card.addView(backup, fullWidthWithTop(dp(8)));
        Button restore = secondaryButton("从备份恢复", "ic_restore");
        restore.setOnClickListener((view) -> openBackup());
        card.addView(restore, fullWidthWithTop(dp(8)));
        // 备份是整份快照，只有这个 App 能读。想在电脑上做表、或者把网页端的记录
        // 搬到手机，需要能打开、能被 Excel 读的格式——这就是下面这两个按钮。
        Button exportCsv = secondaryButton("导出 CSV（Excel 可打开）", "ic_download");
        exportCsv.setOnClickListener((view) -> createCsvExport());
        card.addView(exportCsv, fullWidthWithTop(dp(8)));
        Button importCsv = secondaryButton("导入 CSV", "ic_upload");
        importCsv.setOnClickListener((view) -> openCsvImport());
        card.addView(importCsv, fullWidthWithTop(dp(8)));
        profile.addView(card, fullWidth());
        return profile;
    }

    private void createBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "自记备份-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(System.currentTimeMillis()) + ".ziji");
        startActivityForResult(intent, REQUEST_CREATE_BACKUP);
    }

    private void openBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/octet-stream");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_OPEN_BACKUP);
    }

    private void createCsvExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/csv");
        intent.putExtra(
                Intent.EXTRA_TITLE,
                "自记账单-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(System.currentTimeMillis()) + ".csv"
        );
        startActivityForResult(intent, REQUEST_CREATE_CSV);
    }

    private void openCsvImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        // 有些文件管理器给 CSV 的类型是 text/plain 或 application/vnd.ms-excel，
        // 限定成 text/csv 会把这些文件直接灰掉，选不到。
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_OPEN_CSV);
    }

    private void writeCsv(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("csv output unavailable");
            List<LedgerEntry> entries = LocalLedgerStore.entriesBetween(this, 0L, Long.MAX_VALUE);
            // 导出按时间倒序：打开文件第一眼看到的是最近的账，和 App 里一致。
            Collections.sort(entries, (left, right) -> Long.compare(right.occurredAtMillis, left.occurredAtMillis));
            String csv = LedgerCsvExporter.export(entries);
            // UTF-8 BOM：不加的话中文版 Excel 会把 UTF-8 当 GBK 读，整列中文变乱码。
            output.write(("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "已导出 " + entries.size() + " 笔到 CSV。", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "导出失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void readCsv(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("csv input unavailable");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) >= 0) buffer.write(chunk, 0, count);

            String csv = decodeCsv(buffer.toByteArray());
            List<LedgerEntry> existing = LocalLedgerStore.entriesBetween(this, 0L, Long.MAX_VALUE);
            LedgerCsvImporter.Result result = LedgerCsvImporter.parse(csv, existing);

            if (!result.isUsable()) {
                Toast.makeText(this, result.error, Toast.LENGTH_LONG).show();
                return;
            }
            if (result.entries.isEmpty()) {
                Toast.makeText(
                        this,
                        "没有可导入的新记录（重复 " + result.duplicates + " 行，无法识别 " + result.invalid + " 行）",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            StringBuilder message = new StringBuilder("将新增 " + result.entries.size() + " 笔。");
            if (result.duplicates > 0) message.append("\n重复跳过 ").append(result.duplicates).append(" 行。");
            if (result.invalid > 0) message.append("\n格式无法识别 ").append(result.invalid).append(" 行。");

            new AlertDialog.Builder(this)
                    .setTitle("导入这份 CSV？")
                    .setMessage(message.toString())
                    .setPositiveButton("导入", (dialog, which) -> {
                        int added = LocalLedgerStore.addAll(this, result.entries);
                        updateAllViews();
                        Toast.makeText(
                                this,
                                added > 0 ? "已导入 " + added + " 笔。" : "没有新增记录。",
                                Toast.LENGTH_SHORT
                        ).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception error) {
            Toast.makeText(this, "导入失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * CSV 编码兜底：先按 UTF-8 解，出现替换字符再按 GBK 解。
     *
     * 用户很可能用 Excel 打开导出的文件、改两行、再另存回来——中文版 Excel 默认存 GBK。
     * 只认 UTF-8 的话，这类文件导进来全是问号。
     */
    private static String decodeCsv(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) return utf8;
        try {
            return new String(bytes, "GBK");
        } catch (Exception ignored) {
            return utf8;
        }
    }

    private void showRulesDialog() {
        List<String> rules = LedgerRuleStore.load(this);
        String[] labels = new String[rules.size()];
        for (int index = 0; index < rules.size(); index += 1) {
            labels[index] = LedgerRuleStore.keyword(rules.get(index)) + "  →  " + LedgerRuleStore.category(rules.get(index));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("分类规则")
                .setPositiveButton("新增规则", (dialog, which) -> showAddRuleDialog())
                .setNegativeButton("关闭", null);
        if (labels.length == 0) {
            builder.setMessage("还没有规则。添加后，自动通知中命中的关键词会直接使用对应分类。 ");
        } else {
            builder.setItems(labels, (dialog, which) -> confirmRemoveRule(rules.get(which)));
        }
        builder.show();
    }

    private void showAddRuleDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText keyword = input("关键词，例如：瑞幸");
        EditText category = input("分类，例如：餐饮");
        form.addView(keyword, fullWidthWithBottom(dp(8)));
        form.addView(category, fullWidth());
        new AlertDialog.Builder(this)
                .setTitle("新增分类规则")
                .setView(form)
                .setPositiveButton("保存", (dialog, which) -> {
                    boolean added = LedgerRuleStore.add(this, keyword.getText().toString(), category.getText().toString());
                    Toast.makeText(this, added ? "分类规则已保存。" : "请输入有效且不重复的规则。", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveRule(String rule) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条规则？")
                .setMessage(LedgerRuleStore.keyword(rule) + "  →  " + LedgerRuleStore.category(rule))
                .setPositiveButton("删除", (dialog, which) -> {
                    List<String> rules = LedgerRuleStore.load(this);
                    rules.remove(rule);
                    LedgerAppStorage.repository(this).replaceRules(rules);
                    Toast.makeText(this, "分类规则已删除。", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private View buildPermissionCard() {
        LinearLayout card = verticalCard(COLOR_SURFACE, COLOR_BORDER);
        notificationAccessView = new TextView(this);
        notificationAccessView.setTextSize(15);
        notificationAccessView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        notificationAccessView.setPadding(0, 0, 0, dp(10));
        card.addView(notificationAccessView, fullWidth());

        listenerStatusView = label("");
        listenerStatusView.setPadding(0, 0, 0, dp(10));
        card.addView(listenerStatusView, fullWidth());

        sampleStatsView = label("");
        sampleStatsView.setPadding(0, 0, 0, dp(10));
        card.addView(sampleStatsView, fullWidth());

        TextView help = label("只有开启系统通知读取权限，微信、支付宝和银行消费才能自动入账。");
        help.setPadding(0, 0, 0, dp(12));
        card.addView(help, fullWidth());

        lastCaptureView = label("");
        lastCaptureView.setPadding(0, 0, 0, dp(12));
        card.addView(lastCaptureView, fullWidth());

        Button permissionButton = primaryButton("开启通知读取权限", "ic_bell");
        permissionButton.setOnClickListener((view) -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        card.addView(permissionButton, fullWidthWithBottom(dp(8)));

        Button diagnosticsButton = secondaryButton("查看最近处理记录", "ic_history");
        diagnosticsButton.setOnClickListener((view) -> showCaptureHistory());
        card.addView(diagnosticsButton, fullWidth());
        return card;
    }

    /**
     * 第二通道：页面识别（无障碍服务）。
     *
     * 通知监听依赖对方 App 主动发通知，覆盖率有限；支付成功页则一定会出现。
     * 这里给用户一个独立开关与状态显示，两条通道互为补充，重复的支付会自动去重。
     */
    private View buildAccessibilityCard() {
        LinearLayout card = verticalCard(COLOR_SURFACE, COLOR_BORDER);

        accessibilityAccessView = new TextView(this);
        accessibilityAccessView.setTextSize(15);
        accessibilityAccessView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        accessibilityAccessView.setPadding(0, 0, 0, dp(10));
        card.addView(accessibilityAccessView, fullWidth());

        accessibilityStatusView = label("");
        accessibilityStatusView.setPadding(0, 0, 0, dp(10));
        card.addView(accessibilityStatusView, fullWidth());

        TextView help = label("微信、支付宝的支付通知里只有金额，没有商户——所以通知通道记下来的账单常常"
                + "「不知道花在哪」。开启页面识别后，支付成功页上的商户名会被一并记下，"
                + "再按商户自动归类。只读取微信、支付宝、云闪付和银行页面的文字，不截屏、不读取密码。");
        help.setPadding(0, 0, 0, dp(12));
        card.addView(help, fullWidth());

        Button permissionButton = primaryButton("开启页面识别权限", "ic_eye");
        permissionButton.setOnClickListener((view) -> openAccessibilitySettings());
        card.addView(permissionButton, fullWidthWithBottom(dp(8)));

        Button batteryButton = secondaryButton("检查后台运行设置", "ic_battery");
        batteryButton.setOnClickListener((view) -> showBackgroundHint());
        card.addView(batteryButton, fullWidth());
        return card;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "在「已下载的服务」里开启「自记自动记账（页面识别）」。", Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法打开无障碍设置，请到系统设置中手动开启。", Toast.LENGTH_LONG).show();
        }
    }

    private void showBackgroundHint() {
        new AlertDialog.Builder(this)
                .setTitle("让自动记账更稳定")
                .setMessage("部分手机会在后台冻结自记，导致收不到支付信息。建议在系统设置里：\n\n"
                        + "1. 允许自记「自启动」\n"
                        + "2. 电池/省电策略设为「无限制」或「不优化」\n"
                        + "3. 多任务界面锁定自记\n\n"
                        + "设置后支付成功页被识别到的概率会明显提高。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private View buildQuickActions() {
        Button manualButton = primaryButton("＋  手动记账");
        manualButton.setOnClickListener((view) -> showManualEntryDialog());
        return manualButton;
    }

    private void showManualEntryDialog() {
        final String[] direction = new String[] {"expense"};
        // 默认分类取分类表里的兜底项，不要在这里硬编码字符串——
        // 硬编码的名字一旦改名（住房→居家），默认值就会落在一个网格里不存在的项上。
        final String[] category = new String[] {LedgerCategories.FALLBACK_EXPENSE};
        // 补记时间：默认现在，用户可改成实际发生的时刻。
        final long[] occurredAt = new long[] {System.currentTimeMillis()};
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(4), dp(16), 0);

        LinearLayout directionRow = new LinearLayout(this);
        directionRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView expense = choiceChip("支出", true);
        TextView income = choiceChip("收入", false);
        LinearLayout categoryGrid = new LinearLayout(this);
        categoryGrid.setOrientation(LinearLayout.VERTICAL);
        directionRow.addView(expense, weightedWithEnd(dp(6)));
        directionRow.addView(income, weightedWithStart(dp(6)));
        expense.setOnClickListener((view) -> {
            direction[0] = "expense";
            styleChoice(expense, true);
            styleChoice(income, false);
            category[0] = LedgerCategories.FALLBACK_EXPENSE;
            populateCategoryGrid(categoryGrid, LedgerCategories.optionsFor("expense"), category);
        });
        income.setOnClickListener((view) -> {
            direction[0] = "income";
            styleChoice(expense, false);
            styleChoice(income, true);
            category[0] = LedgerCategories.FALLBACK_INCOME;
            populateCategoryGrid(categoryGrid, LedgerCategories.optionsFor("income"), category);
        });
        form.addView(directionRow, fullWidthWithBottom(dp(14)));

        // 补记日期：点一下选日期，时分保留当前值。默认就是「现在」。
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        dateRow.setPadding(dp(12), dp(11), dp(12), dp(11));
        dateRow.setBackground(rounded(COLOR_SURFACE, COLOR_BORDER, RADIUS_BUTTON));
        dateRow.setClickable(true);
        dateRow.setFocusable(true);
        dateRow.addView(iconView("ic_calendar", COLOR_MUTED, 17), new LinearLayout.LayoutParams(dp(17), dp(17)));
        TextView dateText = new TextView(this);
        dateText.setText(formatFullDate(occurredAt[0]));
        dateText.setTextSize(14);
        dateText.setTextColor(COLOR_TEXT);
        dateText.setPadding(dp(9), 0, 0, 0);
        dateRow.addView(dateText, weighted());
        TextView dateHint = new TextView(this);
        dateHint.setText("修改");
        dateHint.setTextSize(12);
        dateHint.setTextColor(COLOR_PRIMARY);
        dateRow.addView(dateHint, wrapContent());
        dateRow.setOnClickListener((view) -> pickDate(occurredAt[0], picked -> {
            occurredAt[0] = picked;
            dateText.setText(formatFullDate(picked));
        }));
        form.addView(dateRow, fullWidthWithBottom(dp(12)));

        TextView categoryTitle = label("选择分类");
        categoryTitle.setTextColor(COLOR_TEXT);
        categoryTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        form.addView(categoryTitle, fullWidthWithBottom(dp(6)));

        populateCategoryGrid(categoryGrid, EXPENSE_CATEGORY_OPTIONS, category);
        form.addView(categoryGrid, fullWidthWithBottom(dp(12)));

        EditText amountInput = input("输入金额");
        amountInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amountInput.setTextSize(24);
        form.addView(amountInput, fullWidthWithBottom(dp(8)));

        // 商户/来源（选填）：在哪花的钱。以前没有这一项，只能拿分类名或用途顶替。
        EditText merchantInput = input("商户 / 来源（选填）：例如 楼下便利店");
        form.addView(merchantInput, fullWidthWithBottom(dp(8)));

        // 用途（选填）：这笔钱具体买了什么。独立存进 note，不再塞进商户字段。
        EditText purposeInput = input("用途（选填）：例如 和同事聚餐");
        form.addView(purposeInput, fullWidthWithBottom(dp(8)));

        // 账户：Android 端账户是自由文本，给默认值「现金」，用户可改。
        EditText accountInput = dialogInput("账户", "现金");
        form.addView(accountInput, fullWidth());

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.78f)
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("手动记账")
                .setView(scroller)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener((ignored) -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener((view) -> {
            long amountCents = TransactionParser.detectAmountCents(amountInput.getText().toString() + "元");
            if (amountCents <= 0) {
                amountInput.setError("请输入大于 0 的金额");
                return;
            }
            String merchant = merchantInput.getText().toString().trim();
            String purpose = purposeInput.getText().toString().trim();
            String account = accountInput.getText().toString().trim();
            // 商户没填时用分类名兜底，保证列表里仍有可读的标签；用途独立存 note。
            String merchantLabel = merchant.isEmpty() ? category[0] : merchant;
            String safeAccount = account.isEmpty() ? "现金" : account;
            long now = System.currentTimeMillis();
            String id = "manual-" + UUID.randomUUID();
            LedgerEntry entry = new LedgerEntry(
                    id, amountCents, direction[0], merchantLabel, safeAccount, category[0],
                    occurredAt[0], "手动记账 · " + merchantLabel, "manual", "manual",
                    LedgerCodec.fingerprint(id), now, now, 0L, true, purpose
            );
            if (!LocalLedgerStore.add(this, entry)) {
                Toast.makeText(this, "保存失败，请重试。", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            showTab(selectedTab);
            Toast.makeText(this, "已记入本机账本。", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private void updateAllViews() {
        updateLocalLedger();
        updateNotificationAccessStatus();
        updateAccessibilityStatus();
        updateCaptureStatus();
        updateInsightCard();
    }

    /**
     * 首页状态卡必须反映两个采集权限的真实状态，而不是永远说「已开启」。
     *
     * 用户对首页的信任度最高：权限被系统关闭后如果首页还在宣称正常，
     * 漏记账就发生在用户最不可能怀疑的地方。
     */
    private void updateInsightCard() {
        if (insightTitleView == null) return;
        boolean notification = isNotificationAccessEnabled();
        boolean accessibility = isAccessibilityEnabled();
        String title;
        int color;
        if (notification && accessibility) {
            title = "自动记账已开启";
            color = COLOR_PRIMARY_DARK;
        } else if (notification) {
            title = "通知识别已开启，页面识别未开启";
            color = COLOR_DANGER;
        } else if (accessibility) {
            title = "页面识别已开启，通知读取未开启";
            color = COLOR_DANGER;
        } else {
            title = "自动记账尚未开启";
            color = COLOR_DANGER;
        }
        insightTitleView.setText(title);
        insightTitleView.setTextColor(color);
    }

    /**
     * 进入 App 时核对采集权限，缺失就弹窗引导。
     *
     * 弹窗只在「确实缺权限」时出现；去系统设置开启后返回，onResume 重新核对，
     * 权限齐了自然不再打扰。还缺的话会再提醒——这正是「每次进入先查一遍」
     * 的预期行为。
     */
    private void maybeShowPermissionAlert() {
        boolean notification = isNotificationAccessEnabled();
        boolean accessibility = isAccessibilityEnabled();
        if (notification && accessibility) return;
        // 从设置页返回会再次触发 onResume，弹窗还挂着时不重复弹。
        if (permissionDialog != null && permissionDialog.isShowing()) return;

        String message = "部分手机在清理后台或省电时会自动关闭这些权限，导致支付后漏记账：\n\n"
                + (notification ? "· 通知读取权限：已开启\n" : "· 通知读取权限：已关闭\n")
                + (accessibility ? "· 页面识别权限：已开启\n" : "· 页面识别权限：已关闭\n")
                + "\n补齐缺失的权限后，自动记账才能恢复。";

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("自动记账权限需要检查")
                .setMessage(message)
                .setNegativeButton("暂不", null);
        if (!notification && !accessibility) {
            builder.setPositiveButton("开启通知读取", (dialog, which) ->
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            builder.setNeutralButton("开启页面识别", (dialog, which) -> openAccessibilitySettings());
        } else if (!notification) {
            builder.setPositiveButton("开启通知读取", (dialog, which) ->
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        } else {
            builder.setPositiveButton("开启页面识别", (dialog, which) -> openAccessibilitySettings());
        }
        permissionDialog = builder.show();
    }

    private void updateAccessibilityStatus() {
        if (accessibilityAccessView == null) return;
        boolean enabled = isAccessibilityEnabled();
        accessibilityAccessView.setText(enabled ? "页面识别已开启" : "页面识别尚未开启");
        accessibilityAccessView.setTextColor(enabled ? COLOR_PRIMARY : COLOR_MUTED);
        if (accessibilityStatusView != null) {
            ListenerStatus status = NotificationListenerStatusStore.snapshotAccessibility(this);
            String summary = status.updatedAtMillis > 0
                    ? new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(status.updatedAtMillis)
                    : "暂无记录";
            accessibilityStatusView.setText("页面通道：" + accessibilityStateText(status.state) + " · " + summary);
            accessibilityStatusView.setTextColor(COLOR_MUTED);
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabled == null) return false;
        String component = new ComponentName(this, ZijiAccessibilityService.class).flattenToString();
        String shortcut = new ComponentName(this, ZijiAccessibilityService.class).flattenToShortString();
        for (String entry : enabled.split(":")) {
            if (component.equalsIgnoreCase(entry) || shortcut.equalsIgnoreCase(entry)) return true;
        }
        return false;
    }

    private void updateLocalLedger() {
        if (monthAmountView == null || recentContainer == null) return;
        long start = monthStartMillis();
        long end = monthEndMillis();
        // 用区间查询而不是 LocalLedger：后者按条数截断，账本变大后统计会偏小。
        List<LedgerEntry> monthEntries = LocalLedgerStore.entriesBetween(this, start, end);
        // 退款归因要看全量：跨月退款的原交易不在本月切片里，只拿本月记录会算成负支出。
        List<LedgerEntry> allEntries = LocalLedgerStore.entriesBetween(this, 0L, Long.MAX_VALUE);
        RefundOffset summary = RefundOffset.ofMonth(allEntries, start, end);

        if (summaryLabelView != null) {
            summaryLabelView.setText(viewingCurrentMonth() ? "本月支出" : monthTitle() + "支出");
        }
        monthAmountView.setText("¥" + formatYuan(summary.netExpenseCents));

        // 当月看「今日」，历史月看「日均」——9 月的今日支出没有意义。
        if (viewingCurrentMonth()) {
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            long dayStart = day.getTimeInMillis();
            day.add(Calendar.DAY_OF_MONTH, 1);
            long todayExpense = RefundOffset
                    .ofMonth(allEntries, dayStart, day.getTimeInMillis())
                    .netExpenseCents;
            todayAmountView.setText("今日\n¥" + formatYuan(todayExpense));
        } else {
            Calendar cursor = (Calendar) viewMonth.clone();
            int days = cursor.getActualMaximum(Calendar.DAY_OF_MONTH);
            long daily = days > 0 ? summary.netExpenseCents / days : 0L;
            todayAmountView.setText("日均\n¥" + formatYuan(daily));
        }
        recordCountView.setText("账单数\n" + monthEntries.size() + " 笔");

        recentContainer.removeAllViews();
        if (purposeRateView != null) {
            int known = 0;
            for (LedgerEntry entry : monthEntries) {
                if (!entry.needsPurpose()) known += 1;
            }
            purposeRateView.setText(monthEntries.isEmpty()
                    ? "通知到账后自动记录"
                    : known + "/" + monthEntries.size() + " 笔已知用途");
        }
        // 待补提示走全量：补用途是待办，不分月份。
        renderPendingPurposes(monthEntries);
        List<LedgerEntry> entries = new ArrayList<>();
        String query = searchInput == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (LedgerEntry entry : monthEntries) {
            if (!"all".equals(directionFilter) && !directionFilter.equals(entry.direction)) continue;
            if (!query.isEmpty()) {
                // 搜索要能命中用途，否则用户补完备注之后反而搜不到。
                String haystack = (entry.merchant + " " + entry.category + " " + entry.note + " " + entry.account + " " + entry.rawText).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) continue;
            }
            entries.add(entry);
            // 列表只展示 30 条，凑满就没必要继续扫完整个账本。
            if (entries.size() >= RECENT_LIST_LIMIT) break;
        }
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(query.isEmpty() && "all".equals(directionFilter)
                    ? "还没有账单\n开启通知读取权限自动记账，或使用上方按钮手动添加。"
                    : "没有符合条件的账单\n试试清空搜索或切换筛选条件。");
            empty.setTextSize(15);
            empty.setTextColor(COLOR_MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(30), dp(20), dp(30));
            empty.setBackground(rounded(COLOR_SURFACE, COLOR_BORDER));
            recentContainer.addView(empty, fullWidth());
            return;
        }

        int count = entries.size();
        for (int index = 0; index < count; index += 1) {
            recentContainer.addView(buildEntryCard(entries.get(index)), cardParams(dp(10)));
        }
    }

    /**
     * 明细页顶部的「用途」汇总条。
     *
     * 自动记账已经把支付页上的商品说明、转账说明、品牌归类都写进了账本，
     * 所以这里的重点不是「催你补」，而是告诉你自动化到底替你干了多少活；
     * 只有真的没线索的那几笔才需要点进去。
     */
    private void renderPendingPurposes(List<LedgerEntry> entries) {
        if (pendingPurposesContainer == null) return;
        pendingPurposesContainer.removeAllViews();

        // 待补数量走全量查询，和下面「下一笔」用的是同一个范围。
        // 不能拿本月的 entries 来数：首页账单数是本月，待办却是跨月的，
        // 两个数字并排出现只会让人怀疑哪个是真的。
        int pending = LocalLedgerStore.pendingPurposes(this, 0).size();
        int known = 0;
        if (entries != null) {
            for (LedgerEntry entry : entries) {
                if (!entry.needsPurpose()) known += 1;
            }
        }
        // 既没有待办、本月也没有记录时不必占地方，避免首页第一屏全是提示。
        if (pending == 0 && known == 0) return;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        card.setBackground(rounded(COLOR_PRIMARY_SOFT, Color.TRANSPARENT, RADIUS_CARD));
        card.setClickable(true);
        card.setFocusable(true);

        card.addView(iconView(pending > 0 ? "ic_edit" : "ic_check", COLOR_PRIMARY, 20),
                new LinearLayout.LayoutParams(dp(20), dp(20)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);

        TextView title = new TextView(this);
        if (pending > 0) {
            // 不把「已知」和「待补」塞进同一句：那是两个不同的分母，并排写必然有人问
            // 「到底总共几笔」。待办就只说待办。
            title.setText("还有 " + pending + " 笔没写清用途");
        } else {
            title.setText("本月 " + known + " 笔都写清了用途");
        }
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(title, fullWidth());

        TextView hint = new TextView(this);
        hint.setText(pending > 0
                ? "补一次分类，同一个商户以后自动归类"
                : "商品说明、转账备注、商户归类都已直接入账");
        hint.setTextSize(12);
        hint.setTextColor(COLOR_MUTED);
        hint.setPadding(0, dp(3), 0, 0);
        texts.addView(hint, fullWidth());

        card.addView(texts, weighted());
        if (pending > 0) {
            card.addView(iconView("ic_chevron", COLOR_PRIMARY, 18), new LinearLayout.LayoutParams(dp(18), dp(18)));
            card.setOnClickListener((view) -> categorizeNext());
        } else {
            card.setOnClickListener(null);
            card.setClickable(false);
        }

        pendingPurposesContainer.addView(card, cardParams(dp(12)));
    }

    /** 打开「最需要补用途」的那一笔；保存成功后自动跳到下一笔。 */
    private void categorizeNext() {
        // 和提示条用同一个查询（全量、最近的在前），否则点进去补的可能是另一笔。
        List<LedgerEntry> pending = LocalLedgerStore.pendingPurposes(this, 1);
        if (pending.isEmpty()) {
            Toast.makeText(this, "所有账单都写清用途了。", Toast.LENGTH_SHORT).show();
            return;
        }
        showEditDialog(pending.get(0), true);
    }

    private View buildEntryCard(LedgerEntry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(COLOR_SURFACE, COLOR_BORDER, RADIUS_CARD));
        applySoftShadow(card);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener((view) -> showEditDialog(entry));

        // 图标徽章：浅色圆角底 + 居中的线性图标。
        // 用 FrameLayout 承载，方便给图标四周留出不等的内边距，视觉重心更稳。
        android.widget.FrameLayout badge = new android.widget.FrameLayout(this);
        badge.setBackground(rounded(
                entry.isExpense() ? COLOR_EXPENSE_SOFT : COLOR_INCOME_SOFT,
                Color.TRANSPARENT,
                RADIUS_BUTTON
        ));
        ImageView icon = iconView(
                categoryIconName(entry.category),
                entry.isExpense() ? COLOR_EXPENSE : COLOR_INCOME,
                22
        );
        android.widget.FrameLayout.LayoutParams iconParams = new android.widget.FrameLayout.LayoutParams(
                dp(22), dp(22));
        iconParams.gravity = Gravity.CENTER;
        badge.addView(icon, iconParams);
        card.addView(badge, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(13), 0, dp(8), 0);

        // 商户名不再重复拼分类图标——左侧徽章已经表达了分类，重复会出现两个图标。
        TextView merchant = new TextView(this);
        merchant.setText(entry.merchant);
        merchant.setTextSize(16);
        merchant.setTextColor(COLOR_TEXT);
        merchant.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        merchant.setMaxLines(1);
        merchant.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(merchant, fullWidth());

        // 用途写在商户下面：列表里第一眼就能看到「这笔钱花在什么上」，
        // 而不是只看到一个金额和一句「其他」。
        if (!entry.note.trim().isEmpty()) {
            TextView noteView = new TextView(this);
            noteView.setText(entry.note);
            noteView.setTextSize(13);
            noteView.setTextColor(COLOR_MUTED);
            noteView.setMaxLines(2);
            noteView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            noteView.setPadding(0, dp(3), 0, 0);
            details.addView(noteView, fullWidth());
        }

        TextView meta = new TextView(this);
        if (entry.needsPurpose()) {
            // 「其他」不是分类，是「还不知道」。用一种提示性的写法，别让它看起来像已经记好了。
            meta.setText("没认出用途 · " + formatDate(entry.occurredAtMillis));
            meta.setTextColor(COLOR_PRIMARY);
            meta.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else if (RefundOffset.REFUND_CATEGORY.equals(entry.category)) {
            // 退款在账本里是 income 记录，但它不是收入，也不会加进收入统计。
            // 写成「已从支出中扣除」，用户才不会以为这个月的收入变多了。
            meta.setText("退款 · 已从支出中扣除 · " + formatDate(entry.occurredAtMillis));
            meta.setTextColor(COLOR_MUTED);
        } else {
            meta.setText(entry.category + " · " + entry.account + " · " + formatDate(entry.occurredAtMillis));
            meta.setTextColor(COLOR_MUTED);
        }
        meta.setTextSize(12);
        meta.setPadding(0, dp(4), 0, 0);
        details.addView(meta, fullWidth());
        card.addView(details, weighted());

        TextView amount = new TextView(this);
        boolean isRefund = RefundOffset.REFUND_CATEGORY.equals(entry.category);
        if (isRefund) {
            // 「+¥32」会被读成一笔收入。退款是回滚，用「退」字打头，符号也不该是加号。
            amount.setText("退 ¥" + formatYuan(entry.amountCents));
        } else {
            amount.setText((entry.isExpense() ? "−" : "+") + "¥" + formatYuan(entry.amountCents));
        }
        amount.setTextSize(17);
        amount.setTextColor(entry.isExpense() ? COLOR_EXPENSE : COLOR_INCOME);
        amount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        amount.setGravity(Gravity.END);
        amount.setPadding(dp(12), 0, 0, 0);
        card.addView(amount, wrapContent());
        return card;
    }

    private void showEditDialog(LedgerEntry entry) {
        showEditDialog(entry, false);
    }

    /**
     * 「这笔钱花在哪」。
     *
     * 旧版本这里是四个自由文本框，分类要手打「餐饮」两个字——等于没做补录。
     * 现在分类改成点选九宫格，另外加一个用途输入框，回答「花在什么上」。
     *
     * 后补的两项是「支出/收入」和「时间」：它们直接决定这笔钱算不算支出、算在哪个月。
     * 记反了方向只能删了重记，重记还要再赌一次解析——那是把系统的错转嫁给用户。
     *
     * @param chainToNext 从「待补用途」入口进来时为 true，保存后自动跳到下一笔。
     */
    private void showEditDialog(LedgerEntry entry, boolean chainToNext) {
        final String[] direction = new String[] {entry.isExpense() ? "expense" : "income"};
        // 账本里的分类可能不在网格里（旧别名、手输的错值），先归一到「一定能被选中」的值。
        // 不归一的话，打开弹窗就是一片空白选中——用户会以为分类丢了。
        final String[] category = new String[] {LedgerCategories.selectable(direction[0], entry.category)};
        final long[] occurredAt = new long[] {entry.occurredAtMillis};

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText amountInput = dialogInput("金额", formatYuan(entry.amountCents));
        amountInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amountInput.setTextSize(22);
        form.addView(amountInput, fullWidthWithBottom(dp(10)));

        // 方向：支出 / 收入。改这一项等于改这笔钱在统计里的归属。
        LinearLayout directionRow = new LinearLayout(this);
        directionRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView expenseChip = choiceChip("支出", "expense".equals(direction[0]));
        TextView incomeChip = choiceChip("收入", "income".equals(direction[0]));
        directionRow.addView(expenseChip, weightedWithEnd(dp(6)));
        directionRow.addView(incomeChip, weightedWithStart(dp(6)));
        form.addView(directionRow, fullWidthWithBottom(dp(10)));

        // 时间：点一下选日期，时分保留原值。
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        dateRow.setPadding(dp(12), dp(11), dp(12), dp(11));
        dateRow.setBackground(rounded(COLOR_SURFACE, COLOR_BORDER, RADIUS_BUTTON));
        dateRow.setClickable(true);
        dateRow.setFocusable(true);
        dateRow.addView(iconView("ic_calendar", COLOR_MUTED, 17), new LinearLayout.LayoutParams(dp(17), dp(17)));
        TextView dateText = new TextView(this);
        dateText.setText(formatFullDate(occurredAt[0]));
        dateText.setTextSize(14);
        dateText.setTextColor(COLOR_TEXT);
        dateText.setPadding(dp(9), 0, 0, 0);
        dateRow.addView(dateText, weighted());
        TextView dateHint = new TextView(this);
        dateHint.setText("修改");
        dateHint.setTextSize(12);
        dateHint.setTextColor(COLOR_PRIMARY);
        dateRow.addView(dateHint, wrapContent());
        dateRow.setOnClickListener((view) -> pickDate(occurredAt[0], picked -> {
            occurredAt[0] = picked;
            dateText.setText(formatFullDate(picked));
        }));
        form.addView(dateRow, fullWidthWithBottom(dp(12)));

        TextView prompt = label(entry.needsPurpose()
                ? "自动记账只拿到了金额，这笔钱花在什么上？"
                : "改一下分类，或者补一句用途");
        prompt.setTextColor(COLOR_MUTED);
        prompt.setTextSize(12);
        form.addView(prompt, fullWidthWithBottom(dp(14)));

        TextView categoryTitle = label("分类");
        categoryTitle.setTextColor(COLOR_TEXT);
        categoryTitle.setTextSize(13);
        categoryTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        form.addView(categoryTitle, fullWidthWithBottom(dp(7)));

        LinearLayout categoryGrid = new LinearLayout(this);
        categoryGrid.setOrientation(LinearLayout.VERTICAL);
        populateCategoryGrid(
                categoryGrid,
                LedgerCategories.optionsFor(direction[0]),
                category
        );
        form.addView(categoryGrid, fullWidthWithBottom(dp(12)));

        // 切方向时分类网格跟着换；原分类在新网格里不存在就落到兜底项，
        // 保证任何时候都有一个格子是亮的。
        expenseChip.setOnClickListener((view) -> {
            direction[0] = "expense";
            styleChoice(expenseChip, true);
            styleChoice(incomeChip, false);
            category[0] = LedgerCategories.selectable("expense", category[0]);
            populateCategoryGrid(categoryGrid, LedgerCategories.optionsFor("expense"), category);
        });
        incomeChip.setOnClickListener((view) -> {
            direction[0] = "income";
            styleChoice(expenseChip, false);
            styleChoice(incomeChip, true);
            category[0] = LedgerCategories.selectable("income", category[0]);
            populateCategoryGrid(categoryGrid, LedgerCategories.optionsFor("income"), category);
        });

        EditText purposeInput = dialogInput("用途（选填）：和谁、做什么", entry.note);
        form.addView(purposeInput, fullWidthWithBottom(dp(8)));

        EditText merchantInput = dialogInput(
                "商户",
                "未知商户".equals(entry.merchant) ? "" : entry.merchant
        );
        form.addView(merchantInput, fullWidthWithBottom(dp(8)));

        EditText accountInput = dialogInput("账户", entry.account);
        form.addView(accountInput, fullWidthWithBottom(dp(8)));

        TextView raw = label("原始通知：" + entry.rawText);
        raw.setMaxLines(4);
        form.addView(raw, fullWidth());

        // 分类网格有 5 行，加上四五个输入框，小屏会被系统裁掉——套一层滚动容器。
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.64f)
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(entry.needsPurpose() ? "这笔钱花在哪" : "编辑账单")
                .setView(scroller)
                .setPositiveButton("保存", null)
                .setNeutralButton("删除", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.setOnShowListener((ignored) -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener((view) -> {
                long amountCents = TransactionParser.detectAmountCents(amountInput.getText().toString() + "元");
                if (amountCents <= 0) {
                    amountInput.setError("请输入正确金额");
                    return;
                }
                String merchant = merchantInput.getText().toString().trim();
                String account = accountInput.getText().toString().trim();
                String note = purposeInput.getText().toString().trim();
                // 网格里的选中值一定合法；这里再归一一次是防「用户没点过任何格子」。
                String chosen = LedgerCategories.selectable(direction[0], category[0]);

                LedgerEntry updated = entry.withEdits(
                        amountCents,
                        direction[0],
                        merchant.isEmpty() ? entry.merchant : merchant,
                        account.isEmpty() ? "未识别账户" : account,
                        chosen,
                        note,
                        occurredAt[0]
                );
                if (!LocalLedgerStore.update(this, updated)) {
                    Toast.makeText(this, "保存失败，请检查手机存储空间。", Toast.LENGTH_LONG).show();
                    return;
                }
                // 用户手动改过分类，说明「这个商户属于这一类」。
                // 记进规则表让以后自动归类，同时把历史上同商户的「其他」一起改掉——
                // 教一次只对以后生效，等于让用户把过去几十笔再点一遍，那不叫学会。
                // 方向也改过时不做学习：那是这笔记录本身记错了，不能拿它去教规则。
                int backfilled = 0;
                if (!chosen.equals(entry.category) && direction[0].equals(entry.direction)) {
                    LocalLedgerStore.learnMerchantCategory(this, updated.merchant, chosen);
                    // 平台型商户（美团/饿了么）下，消费对象是商品名而非平台名；把商品名也
                    // 记一条规则，下次「美团 + 螺蛳粉」自动归餐饮，而不会污染别的商品。
                    if (!note.isEmpty()) {
                        LocalLedgerStore.learnProductCategory(this, note, chosen);
                    }
                    backfilled = LocalLedgerStore.backfillCategory(this, updated.merchant, chosen);
                }
                dialog.dismiss();
                updateLocalLedger();
                StringBuilder message = new StringBuilder(
                        note.isEmpty() ? "已归到「" + chosen + "」" : "已记下：" + chosen + " · " + note
                );
                if (backfilled > 0) {
                    message.append("，历史 ").append(backfilled).append(" 笔一并归类");
                }
                Toast.makeText(this, message.toString(), Toast.LENGTH_SHORT).show();
                // 只有真的补完了才往下跳，否则会在同一笔上反复弹窗。
                if (chainToNext && !updated.needsPurpose()) categorizeNext();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(COLOR_DANGER);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener((view) -> {
                dialog.dismiss();
                confirmDelete(entry);
            });
        });
        dialog.show();
    }

    private void confirmDelete(LedgerEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("删除这笔账单？")
                .setMessage(entry.merchant + "  ¥" + formatYuan(entry.amountCents))
                .setPositiveButton("删除", (dialog, which) -> {
                    LocalLedgerStore.remove(this, entry.id);
                    updateLocalLedger();
                    Toast.makeText(this, "账单已删除。", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateNotificationAccessStatus() {
        if (notificationAccessView == null) return;
        boolean enabled = isNotificationAccessEnabled();
        notificationAccessView.setText(enabled ? "自动记账已开启" : "自动记账尚未开启");
        notificationAccessView.setTextColor(enabled ? COLOR_PRIMARY : COLOR_DANGER);
        if (listenerStatusView != null) {
            // 只读「通知监听」通道的状态，避免被页面识别通道的记录覆盖。
            ListenerStatus status = NotificationListenerStatusStore.snapshotNotification(this);
            String summary = status.updatedAtMillis > 0
                    ? new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(status.updatedAtMillis)
                    : "暂无记录";
            listenerStatusView.setText("通知通道：" + listenerStateText(status.state) + " · " + summary);
            listenerStatusView.setTextColor(COLOR_MUTED);
        }
        if (sampleStatsView != null) {
            NotificationSampleStore.Stats stats = NotificationSampleStore.snapshot(this);
            String latest = stats.latest == null
                    ? "暂无样本"
                    : sourceName(stats.latest.packageName) + " · " + stats.latest.resultState + " · " + formatDate(stats.latest.timestampMillis);
            sampleStatsView.setText("通知样本：" + stats.total + " 条 · 命中率 " + stats.successRate() + "% · 最近 " + latest);
            sampleStatsView.setTextColor(COLOR_MUTED);
        }
    }

    private static String listenerStateText(String state) {
        if (state == null) return "未知";
        if (state.startsWith("connected")) return "已连接";
        if (state.startsWith("rebind_requested")) return "已请求重连";
        if ("captured".equals(state)) return "已入账";
        if ("duplicate".equals(state)) return "重复已忽略";
        if ("rejected".equals(state)) return "收到通知但未识别";
        return state;
    }

    private static String accessibilityStateText(String state) {
        if (state == null) return "未知";
        if ("accessibility_connected".equals(state)) return "已连接";
        if ("accessibility_captured".equals(state)) return "已入账";
        if ("accessibility_duplicate".equals(state)) return "重复已忽略";
        if ("accessibility_no_window".equals(state)) return "页面读取失败";
        if ("accessibility_interrupted".equals(state)) return "被系统暂停";
        if ("rejected".equals(state)) return "看到支付页但未识别";
        if ("unknown".equals(state)) return "暂无记录";
        return state;
    }

    private void updateCaptureStatus() {
        if (lastCaptureView == null) return;
        List<CaptureEvent> events = CaptureHistoryStore.load(this).entries();
        if (events.isEmpty()) {
            lastCaptureView.setText("最近处理：暂无记录");
            return;
        }
        CaptureEvent event = events.get(0);
        lastCaptureView.setText(
                "最近处理：" + sourceName(event.packageName)
                        + " · " + captureStatusText(event)
                        + " · " + formatDate(event.timestampMillis)
        );
    }

    private void showCaptureHistory() {
        List<CaptureEvent> events = CaptureHistoryStore.load(this).entries();
        if (events.isEmpty()) {
            Toast.makeText(this, "暂无支付通知处理记录。", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder message = new StringBuilder();
        int count = Math.min(events.size(), 15);
        for (int index = 0; index < count; index += 1) {
            CaptureEvent event = events.get(index);
            if (index > 0) message.append("\n\n");
            message.append(formatDate(event.timestampMillis))
                    .append("  ").append(sourceName(event.packageName))
                    .append("\n").append(captureStatusText(event));
            String text = event.text.length() > 80 ? event.text.substring(0, 80) + "…" : event.text;
            if (!text.isEmpty()) message.append("\n").append(text);
        }

        new AlertDialog.Builder(this)
                .setTitle("最近自动记账处理")
                .setMessage(message.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private static String captureStatusText(CaptureEvent event) {
        if ("captured".equals(event.status)) return "已自动入账";
        if ("failed".equals(event.status)) return "保存失败，请检查手机存储空间";
        if ("duplicate".equals(event.status)) {
            return "cross_channel_duplicate".equals(event.reason) ? "另一通道已记过，已忽略" : "重复支付，已忽略";
        }
        if ("missing_amount".equals(event.reason)) return "疑似支付，但未识别金额";
        if ("missing_success".equals(event.reason)) return "看到页面但未确认支付成功";
        if ("not_completed".equals(event.reason)) return "支付尚未完成，已跳过";
        if ("missing_payment_action".equals(event.reason)) return "疑似支付，但未识别支付动作";
        return "未写入账本";
    }

    private static String sourceName(String packageName) {
        String value = packageName == null ? "" : packageName;
        if (value.equals("com.tencent.mm")) return "微信";
        if (value.equals("com.eg.android.alipaygphone")) return "支付宝";
        if (value.contains("mms") || value.contains("message")) return "银行短信";
        return value.isEmpty() ? "未知来源" : value;
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        String component = new ComponentName(this, ZijiNotificationListenerService.class).flattenToString();
        for (String entry : enabled.split(":")) {
            // 部分 ROM 写入的组件串大小写与 flattenToString 不一致，
            // 无障碍侧已经用 ignoreCase，这里保持同一口径，避免误报「未开启」。
            if (component.equalsIgnoreCase(entry)) return true;
        }
        return false;
    }

    private LinearLayout verticalCard(int fillColor, int borderColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(fillColor, borderColor, RADIUS_CARD));
        // 近白卡片压在暖灰底上时，色差本身不足以分层，需要极轻的投影补足。
        if (fillColor == COLOR_SURFACE) applySoftShadow(card);
        return card;
    }

    private View sectionHeader(String title, String subtitle) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(19);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setLetterSpacing(-0.01f);
        header.addView(titleView, fullWidth());
        TextView subtitleView = label(subtitle);
        subtitleView.setTextSize(13);
        subtitleView.setPadding(0, dp(4), 0, 0);
        header.addView(subtitleView, fullWidth());
        return header;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(COLOR_MUTED);
        return view;
    }

    private TextView metricView() {
        TextView view = new TextView(this);
        view.setTextSize(15);
        view.setTextColor(COLOR_TEXT);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLineSpacing(0f, 1.25f);
        return view;
    }

    private TextView choiceChip(String text, boolean selected) {
        TextView chip = label(text);
        chip.setTextSize(15);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(dp(44));
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        styleChoice(chip, selected);
        return chip;
    }

    private void styleChoice(TextView view, boolean selected) {
        view.setTextColor(selected ? Color.WHITE : COLOR_TEXT);
        view.setBackground(rounded(
                selected ? COLOR_PRIMARY : COLOR_SURFACE,
                selected ? Color.TRANSPARENT : COLOR_BORDER,
                RADIUS_CHIP
        ));
    }

    /**
     * 分类选项：图标在上、名称在下。
     *
     * 用 LinearLayout 而非 TextView 换行拼接，因为图标现在是 ImageView，
     * 需要独立控制着色与尺寸。tag 里存图标视图，选中态切换时一并改色。
     */
    private LinearLayout categoryOption(String iconName, String name) {
        LinearLayout option = new LinearLayout(this);
        option.setOrientation(LinearLayout.VERTICAL);
        option.setGravity(Gravity.CENTER);
        option.setMinimumHeight(dp(68));
        option.setPadding(dp(2), dp(9), dp(2), dp(8));

        ImageView icon = iconView(iconName, COLOR_MUTED, 21);
        option.addView(icon, fullWidth());

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(12);
        label.setTextColor(COLOR_TEXT);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, 0);
        option.addView(label, fullWidth());

        option.setTag(new Object[] {icon, label});
        styleCategory(option, false);
        return option;
    }

    private void populateCategoryGrid(LinearLayout grid, String[][] categories, String[] selectedCategory) {
        grid.removeAllViews();
        int rowCount = (categories.length + 3) / 4;
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex += 1) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 4; column += 1) {
                int index = rowIndex * 4 + column;
                if (index >= categories.length) {
                    row.addView(new View(this), weightedWithEnd(dp(4)));
                    continue;
                }
                LinearLayout option = categoryOption(categories[index][0], categories[index][1]);
                final int selectedIndex = index;
                option.setOnClickListener((view) -> {
                    selectedCategory[0] = categories[selectedIndex][1];
                    for (int childIndex = 0; childIndex < rowCount; childIndex += 1) {
                        ViewGroup childRow = (ViewGroup) grid.getChildAt(childIndex);
                        for (int itemIndex = 0; itemIndex < childRow.getChildCount(); itemIndex += 1) {
                            View item = childRow.getChildAt(itemIndex);
                            if (item instanceof LinearLayout) styleCategory((LinearLayout) item, item == option);
                        }
                    }
                });
                styleCategory(option, categories[index][1].equals(selectedCategory[0]));
                row.addView(option, weightedWithEnd(dp(4)));
            }
            grid.addView(row, fullWidthWithBottom(dp(7)));
        }
    }

    private void styleCategory(LinearLayout view, boolean selected) {
        Object[] parts = (Object[]) view.getTag();
        ImageView icon = (ImageView) parts[0];
        TextView label = (TextView) parts[1];
        icon.setColorFilter(selected ? COLOR_PRIMARY : COLOR_MUTED, PorterDuff.Mode.SRC_IN);
        label.setTextColor(selected ? COLOR_PRIMARY_DARK : COLOR_TEXT);
        label.setTypeface(Typeface.create(
                selected ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL
        ));
        view.setBackground(rounded(
                selected ? COLOR_PRIMARY_SOFT : COLOR_SURFACE,
                selected ? COLOR_PRIMARY : COLOR_BORDER,
                RADIUS_BUTTON
        ));
    }

    private final class TrendChartView extends View {
        private final long[] values;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        TrendChartView(android.content.Context context, long[] values, int color) {
            super(context);
            this.values = values;
            this.color = color;
            setMinimumHeight(dp(142));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, dp(142));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(8);
            float right = getWidth() - dp(8);
            float bottom = getHeight() - dp(20);
            float top = dp(10);
            paint.setColor(COLOR_BORDER);
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(left, bottom, right, bottom, paint);
            long max = 1;
            for (long value : values) max = Math.max(max, value);
            float slot = (right - left) / Math.max(1, values.length);
            paint.setColor(color);
            for (int index = 0; index < values.length; index += 1) {
                if (values[index] <= 0) continue;
                float barHeight = (bottom - top) * values[index] / (float) max;
                float barLeft = left + index * slot + slot * .22f;
                float barRight = left + index * slot + slot * .78f;
                canvas.drawRoundRect(barLeft, bottom - barHeight, barRight, bottom, dp(4), dp(4), paint);
            }
            paint.setColor(COLOR_MUTED);
            paint.setTextSize(dp(11));
            int[] labels = new int[] {1, 5, 10, 15, 20, 25, values.length};
            for (int day : labels) {
                if (day > values.length) continue;
                float x = left + (day - 1) * slot;
                canvas.drawText(String.format(Locale.CHINA, "%02d", day), x, getHeight() - dp(4), paint);
            }
        }
    }

    /** 分类 → 图标资源名（返回 @drawable 名称，找不到时回退到"其他"）。 */
    private String categoryIconName(String category) {
        // 卡片上没有方向信息，先按旧名归一（住房→居家、教育→学习），再两张表都查一遍。
        String value = LedgerCategories.canonical(category);
        for (String[] option : EXPENSE_CATEGORY_OPTIONS) {
            if (option[1].equals(value)) return option[0];
        }
        for (String[] option : INCOME_CATEGORY_OPTIONS) {
            if (option[1].equals(value)) return option[0];
        }
        return "ic_cat_exp_circle_ellipsis";
    }

    /** 按名称取 drawable 资源 id，避免手写 R.drawable.xxx 的巨型 switch。 */
    private int drawableId(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    /**
     * 构建一个图标 ImageView。
     *
     * 用 ImageView + tint 而非 TextView 承载图标：
     * - 矢量图的描边粗细由资源决定，缩放时不会像字体那样"糊"；
     * - tint 可直接跟随主题色，选中/未选中切换只需改颜色。
     */
    private ImageView iconView(String name, int tintColor, int sizeDp) {
        ImageView view = new ImageView(this);
        int id = drawableId(name);
        if (id != 0) view.setImageResource(id);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        // 图标在这里全部是「配合文字」的装饰元素：读屏时不该单独念一个图形，
        // 否则用户会听到一串没有意义的图标节点，把真正的文案淹没。
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setContentDescription(null);
        return view;
    }

    private EditText input(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_MUTED);
        input.setMinHeight(dp(48));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(rounded(COLOR_BG, COLOR_BORDER));
        return input;
    }

    private EditText dialogInput(String hint, String value) {
        EditText input = input(hint);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        return input;
    }

    private Button primaryButton(String text) {
        Button button = baseButton(text);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(COLOR_PRIMARY, Color.TRANSPARENT, RADIUS_BUTTON));
        applySoftShadow(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = baseButton(text);
        button.setTextColor(COLOR_TEXT);
        button.setBackground(rounded(COLOR_SURFACE, COLOR_BORDER, RADIUS_BUTTON));
        return button;
    }

    /** 带前置图标的按钮：把图标内联进文字串里，图标和文字作为一个整体居中。 */
    private Button secondaryButton(String text, String iconName) {
        Button button = secondaryButton(text);
        applyButtonIcon(button, iconName, COLOR_MUTED);
        return button;
    }

    private Button primaryButton(String text, String iconName) {
        Button button = primaryButton(text);
        applyButtonIcon(button, iconName, Color.WHITE);
        return button;
    }

    private void applyButtonIcon(Button button, String iconName, int tintColor) {
        int id = drawableId(iconName);
        if (id == 0) return;
        android.graphics.drawable.Drawable icon = getResources().getDrawable(id, getTheme());
        icon = icon.mutate();
        icon.setBounds(0, 0, dp(17), dp(17));
        icon.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
        // 图标必须内联进文字里，不能用 setCompoundDrawables：那是「钉」在按钮左边缘的，
        // 文字自己按 gravity 居中，结果就是图标贴左、文字居中、中间空一大段。
        // 这里用 nbsp 占位放图标，第二个 nbsp 是图标与文字的间距。
        CharSequence text = button.getText();
        SpannableString content = new SpannableString("\u00A0\u00A0" + text);
        content.setSpan(new CenteredImageSpan(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(content);
    }

    /** 垂直居中的 ImageSpan。ALIGN_BOTTOM 会把图标压到基线以下，ALIGN_CENTER 要 API 29+，自绘最稳。 */
    private static final class CenteredImageSpan extends ImageSpan {
        CenteredImageSpan(android.graphics.drawable.Drawable drawable) {
            super(drawable, ImageSpan.ALIGN_BOTTOM);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            android.graphics.drawable.Drawable drawable = getDrawable();
            canvas.save();
            int transY = top + (bottom - top - drawable.getBounds().height()) / 2;
            canvas.translate(x, transY);
            drawable.draw(canvas);
            canvas.restore();
        }
    }

    private Button baseButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        // 用无衬线 + 中等字重。原来的 serif 在中文下会回退成很难看的字形，
        // 且与 fanqie 的无衬线基调不一致，是「看起来旧」的主要原因之一。
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setLetterSpacing(0.01f);
        return button;
    }

    private GradientDrawable rounded(int fillColor, int borderColor) {
        return rounded(fillColor, borderColor, RADIUS_CARD);
    }

    private GradientDrawable rounded(int fillColor, int borderColor, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(radiusDp));
        if (borderColor != Color.TRANSPARENT) {
            // 1px 细描边，贴近 fanqie 的 hairline 观感；太粗会让暖底上的卡片显得笨重。
            background.setStroke(1, borderColor);
        }
        return background;
    }

    /** 给卡片加一层克制的投影，制造"浮起"的层次感（fanqie 的 shadow-soft 观感）。 */
    private void applySoftShadow(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setElevation(dp(2));
        }
    }

    private static String formatYuan(long cents) {
        return String.format(Locale.CHINA, "%.2f", cents / 100.0);
    }

    private static String formatDate(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(millis);
    }

    /** 编辑弹窗里的完整时间：用户要改日期，得先看清现在是哪一天。 */
    private static String formatFullDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(millis);
    }

    /**
     * 只选日期，时分沿用原值。
     *
     * 记账场景精确到分钟足够，再弹一次时间选择器只会多一次点击。
     * 用户真正要改的是「这笔算哪天」——月末那几笔跨月的账。
     */
    private void pickDate(long currentMillis, DatePicked callback) {
        Calendar current = Calendar.getInstance();
        current.setTimeInMillis(currentMillis);
        new android.app.DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    Calendar picked = Calendar.getInstance();
                    picked.setTimeInMillis(currentMillis);
                    picked.set(year, month, dayOfMonth);
                    callback.onPicked(picked.getTimeInMillis());
                },
                current.get(Calendar.YEAR),
                current.get(Calendar.MONTH),
                current.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private interface DatePicked {
        void onPicked(long millis);
    }

    /** 时间戳落在所选月份的第几天（0 基）。调用方已按月过滤，这里只做越界保护。 */
    private static int dayOfMonth(long millis, int days) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(millis);
        int index = day.get(Calendar.DAY_OF_MONTH) - 1;
        return index < 0 || index >= days ? -1 : index;
    }

    private ViewGroup.LayoutParams fullWidth() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private ViewGroup.LayoutParams wrapContent() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightedWithStart(int margin) {
        LinearLayout.LayoutParams params = weighted();
        params.setMarginStart(margin);
        return params;
    }

    private LinearLayout.LayoutParams weightedWithEnd(int margin) {
        LinearLayout.LayoutParams params = weighted();
        params.setMarginEnd(margin);
        return params;
    }

    private LinearLayout.LayoutParams weightedHeight() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private LinearLayout.LayoutParams cardParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams fullWidthWithBottom(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = margin;
        return params;
    }

    private LinearLayout.LayoutParams fullWidthWithTop(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = margin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
