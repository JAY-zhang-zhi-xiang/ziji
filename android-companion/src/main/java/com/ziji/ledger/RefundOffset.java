package com.ziji.ledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 退款冲抵。
 *
 * 退款到账在账本里是一条 direction=income、category=退款 的记录，但它不是「收入」，
 * 而是某笔支出的回滚。如果统计只累加 expense，32 块的咖啡退回来之后本月支出
 * 仍然是 32，分类排行也仍然把这 32 记在餐饮上——数字看着正常，其实是错的。
 *
 * 归因规则：同商户 + 原支出时间不晚于退款 + 30 天内 + 原支出仍有余额（支持分次退款）。
 * 找不到原交易的（跨月太多、商户没识别出来、本来就是独立退款）留在退款当月，
 * 单独记在「退款」项下。
 *
 * 为什么必须看「全量账本」而不是「当月切片」：
 * 9 月 28 日买 100、10 月 2 日退 100，如果先按月切片再找原交易，10 月的列表里
 * 根本没有那笔支出，退款就只能挂在 10 月，于是 10 月支出显示 -100，9 月还显示 100。
 * 正确的归属是：退款回到它退的那笔支出所在的月份冲抵——9 月净支出 0，10 月 0。
 *
 * 所以 {@link #ofMonth} 接收全量账本 + 统计区间，先在全体记录里做归因，再按区间聚合。
 */
public final class RefundOffset {
    /** 找原交易的窗口。再长就不像同一笔了，匹配错反而更糟。 */
    private static final long MATCH_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000L;
    static final String REFUND_CATEGORY = "退款";

    /** 区间内的毛支出。 */
    public final long expenseCents;
    /** 区间内实际扣掉的退款额（含跨月回溯到本区间的那部分）。 */
    public final long refundCents;
    /** 净支出 = 毛支出 - 退款。 */
    public final long netExpenseCents;
    /** 区间内的真实收入，已排除退款。 */
    public final long incomeCents;
    /** 已扣减退款的分类合计，可能为负（退款没有匹配到原支出时）。 */
    public final Map<String, Long> categoryTotals;
    /** 支出 id → 被退总额。趋势图按「原交易日」扣减时要用。 */
    public final Map<String, Long> refundedByOrigin;
    /** 没匹配到原交易的退款，按发生时间排列；它们留在退款当月。 */
    public final List<LedgerEntry> unmatchedRefunds;

    private RefundOffset(
            long expense,
            long refund,
            long income,
            Map<String, Long> categories,
            Map<String, Long> refundedByOrigin,
            List<LedgerEntry> unmatchedRefunds
    ) {
        this.expenseCents = expense;
        this.refundCents = refund;
        this.netExpenseCents = expense - refund;
        this.incomeCents = income;
        this.categoryTotals = categories;
        this.refundedByOrigin = refundedByOrigin;
        this.unmatchedRefunds = unmatchedRefunds;
    }

    /** 在给定集合内做归因与统计（区间不限）。保留给测试与单页统计使用。 */
    public static RefundOffset of(List<LedgerEntry> entries) {
        return compute(entries, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * 跨月退款回溯：用全量账本归因，只聚合 [startMillis, endMillis) 内的记录。
     *
     * @param allEntries  全量账本。只传当月切片会让跨月退款退化成「退款当月负支出」。
     * @param startMillis 区间起点（含）
     * @param endMillis   区间终点（不含）
     */
    public static RefundOffset ofMonth(List<LedgerEntry> allEntries, long startMillis, long endMillis) {
        return compute(allEntries, startMillis, endMillis);
    }

    private static RefundOffset compute(List<LedgerEntry> all, long startMillis, long endMillis) {
        List<LedgerEntry> expenses = new ArrayList<>();
        List<LedgerEntry> refunds = new ArrayList<>();
        if (all != null) {
            for (LedgerEntry entry : all) {
                if (entry == null || entry.amountCents <= 0) continue;
                if (entry.isExpense()) {
                    expenses.add(entry);
                } else if (isRefund(entry)) {
                    refunds.add(entry);
                }
            }
        }

        // 每笔原支出还能被退多少，支持「一笔支出分两次退」。
        Map<String, Long> remaining = new HashMap<>();
        for (LedgerEntry origin : expenses) {
            remaining.put(origin.id, origin.amountCents);
        }

        // 从最近的退款往前配：同一商户短期内的多笔，退的通常是最近买的那笔。
        refunds.sort((left, right) -> Long.compare(right.occurredAtMillis, left.occurredAtMillis));
        Map<String, LedgerEntry> originByRefundId = new HashMap<>();
        Map<String, Long> refundedByOrigin = new HashMap<>();
        List<LedgerEntry> unmatchedRefunds = new ArrayList<>();
        for (LedgerEntry refund : refunds) {
            LedgerEntry origin = matchOrigin(refund, expenses, remaining);
            if (origin == null) {
                unmatchedRefunds.add(refund);
                continue;
            }
            originByRefundId.put(refund.id, origin);
            remaining.put(origin.id, remaining.get(origin.id) - refund.amountCents);
            Long current = refundedByOrigin.get(origin.id);
            refundedByOrigin.put(origin.id, (current == null ? 0L : current) + refund.amountCents);
        }

        Map<String, Long> categories = new HashMap<>();
        long expense = 0L;
        long refundTotal = 0L;
        long income = 0L;

        if (all != null) {
            for (LedgerEntry entry : all) {
                if (entry == null || entry.amountCents <= 0) continue;
                boolean inRange = entry.occurredAtMillis >= startMillis && entry.occurredAtMillis < endMillis;
                if (!inRange) continue;

                if (entry.isExpense()) {
                    long refunded = refundedByOrigin.getOrDefault(entry.id, 0L);
                    expense += entry.amountCents;
                    refundTotal += refunded;
                    add(categories, entry.category, entry.amountCents - refunded);
                    continue;
                }
                if (isRefund(entry)) {
                    // 已被归因到原交易：不管原交易在哪个月，这笔退款都不再计入退款当月，
                    // 它的影响已经体现在原交易那个月的扣减里。
                    if (originByRefundId.containsKey(entry.id)) continue;
                    refundTotal += entry.amountCents;
                    add(categories, REFUND_CATEGORY, -entry.amountCents);
                    continue;
                }
                income += entry.amountCents;
            }
        }

        categories.values().removeIf(value -> value == 0L);
        return new RefundOffset(expense, refundTotal, income, categories, refundedByOrigin, unmatchedRefunds);
    }

    private static boolean isRefund(LedgerEntry entry) {
        return REFUND_CATEGORY.equals(entry.category);
    }

    private static LedgerEntry matchOrigin(
            LedgerEntry refund,
            List<LedgerEntry> expenses,
            Map<String, Long> remaining
    ) {
        String key = TransactionParser.merchantKey(refund.merchant);
        if (key.isEmpty()) return null;
        LedgerEntry best = null;
        long bestGap = Long.MAX_VALUE;
        for (LedgerEntry candidate : expenses) {
            long left = remaining.getOrDefault(candidate.id, 0L);
            if (left < refund.amountCents) continue;
            // 退款不可能发生在支出之前。允许 5 分钟容差，避免两端时间精度不一致。
            if (candidate.occurredAtMillis > refund.occurredAtMillis + 5 * 60 * 1000L) continue;
            if (!key.equals(TransactionParser.merchantKey(candidate.merchant))) continue;
            long gap = refund.occurredAtMillis - candidate.occurredAtMillis;
            if (gap > MATCH_WINDOW_MILLIS) continue;
            if (gap < bestGap) {
                bestGap = gap;
                best = candidate;
            }
        }
        return best;
    }

    private static void add(Map<String, Long> sink, String key, long value) {
        String name = key == null || key.trim().isEmpty() ? "其他" : key.trim();
        sink.put(name, sink.getOrDefault(name, 0L) + value);
    }
}
