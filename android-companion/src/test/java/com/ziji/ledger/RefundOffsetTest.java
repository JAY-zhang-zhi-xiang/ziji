package com.ziji.ledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * 退款冲抵的口径测试。
 *
 * 这些断言直接对应「数字算得对不对」：一笔钱退回来之后，
 * 本月支出必须变少、分类排行必须跟着变、收入不能凭空增加。
 */
public final class RefundOffsetTest {
    private static int checks = 0;
    private static final long DAY = 24 * 60 * 60 * 1000L;
    private static final long BASE = 1_700_000_000_000L;

    public static void main(String[] args) {
        noRefund();
        fullRefund();
        partialRefund();
        refundInTwoParts();
        unmatchedRefundFallsBackToRefundCategory();
        refundIsNotIncome();
        refundOlderThanWindowDoesNotTouchOriginCategory();
        refundLargerThanOriginIsNotMatched();
        crossMonthRefundGoesBackToOriginMonth();
        refundBeforeExpenseIsNotMatched();

        System.out.println("RefundOffsetTest passed: " + checks);
    }

    private static void noRefund() {
        RefundOffset result = RefundOffset.of(list(
                entry(3200, "expense", "瑞幸咖啡", "餐饮", BASE),
                entry(1500, "expense", "滴滴出行", "交通", BASE + DAY)
        ));
        assertEquals(4700L, result.expenseCents, "没有退款时毛支出应为两笔之和");
        assertEquals(0L, result.refundCents, "没有退款时退款额为 0");
        assertEquals(4700L, result.netExpenseCents, "净支出应等于毛支出");
        assertEquals(Long.valueOf(3200L), result.categoryTotals.get("餐饮"), "餐饮不该被扣");
    }

    private static void fullRefund() {
        RefundOffset result = RefundOffset.of(list(
                entry(3200, "expense", "瑞幸咖啡", "餐饮", BASE),
                entry(3200, "income", "瑞幸咖啡", "退款", BASE + DAY)
        ));
        assertEquals(3200L, result.expenseCents, "毛支出仍是 32");
        assertEquals(3200L, result.refundCents, "退款额应被统计");
        assertEquals(0L, result.netExpenseCents, "全额退款后净支出应为 0");
        assertEquals(null, result.categoryTotals.get("餐饮"), "餐饮合计应被扣到 0 并移除");
    }

    private static void partialRefund() {
        RefundOffset result = RefundOffset.of(list(
                entry(3200, "expense", "瑞幸咖啡", "餐饮", BASE),
                entry(1280, "income", "瑞幸咖啡", "退款", BASE + DAY)
        ));
        assertEquals(1920L, result.netExpenseCents, "部分退款后净支出应为 19.20");
        assertEquals(Long.valueOf(1920L), result.categoryTotals.get("餐饮"),
                "退款应扣回原分类而不是新建一行");
    }

    private static void refundInTwoParts() {
        RefundOffset result = RefundOffset.of(list(
                entry(3200, "expense", "瑞幸咖啡", "餐饮", BASE),
                entry(1600, "income", "瑞幸咖啡", "退款", BASE + DAY),
                entry(1600, "income", "瑞幸咖啡", "退款", BASE + 2 * DAY)
        ));
        assertEquals(0L, result.netExpenseCents, "分两次退完也应归零");
        assertEquals(null, result.categoryTotals.get("餐饮"), "两次都应扣在餐饮上");
    }

    private static void unmatchedRefundFallsBackToRefundCategory() {
        // 商户没识别出来，匹配不到原支出：总额照样要扣，否则支出虚高。
        RefundOffset result = RefundOffset.of(list(
                entry(3200, "expense", "星巴克", "餐饮", BASE),
                entry(1990, "income", "未知商户", "退款", BASE + DAY)
        ));
        assertEquals(1210L, result.netExpenseCents, "匹配不到原交易时总额仍要扣减退款");
        assertEquals(Long.valueOf(-1990L), result.categoryTotals.get("退款"),
                "找不到原分类的退款单独挂在「退款」项下");
        assertEquals(Long.valueOf(3200L), result.categoryTotals.get("餐饮"),
                "没有匹配到就不该动原分类");
    }

    private static void refundIsNotIncome() {
        RefundOffset result = RefundOffset.of(list(
                entry(3200, "expense", "瑞幸咖啡", "餐饮", BASE),
                entry(3200, "income", "瑞幸咖啡", "退款", BASE + DAY),
                entry(500000, "income", "工资", "收入", BASE + DAY)
        ));
        assertEquals(500000L, result.incomeCents, "退款不能算进收入，否则收入凭空翻倍");
        assertEquals(500000L, result.incomeCents - result.netExpenseCents,
                "结余应按「真实收入 - 净支出」计算：退款已冲抵支出，不能再算一次收入");
    }

    private static void refundOlderThanWindowDoesNotTouchOriginCategory() {
        RefundOffset result = RefundOffset.of(list(
                entry(3200, "expense", "瑞幸咖啡", "餐饮", BASE),
                entry(3200, "income", "瑞幸咖啡", "退款", BASE + 40 * DAY)
        ));
        assertEquals(0L, result.netExpenseCents, "跨月退款仍然扣总额");
        assertEquals(Long.valueOf(-3200L), result.categoryTotals.get("退款"),
                "超过 30 天不再认定同一笔，退回「退款」项");
    }

    private static void refundLargerThanOriginIsNotMatched() {
        // 原支出只有 10 元，却退了 32 元，多半不是同一笔，不能扣错分类。
        RefundOffset result = RefundOffset.of(list(
                entry(1000, "expense", "便利店", "购物", BASE),
                entry(3200, "income", "便利店", "退款", BASE + DAY)
        ));
        assertEquals(Long.valueOf(1000L), result.categoryTotals.get("购物"),
                "退款金额超过原支出时不应匹配");
        assertEquals(Long.valueOf(-3200L), result.categoryTotals.get("退款"),
                "未匹配的退款独立成项");
    }

    /**
     * 9 月 28 日买 100、10 月 2 日退 100。
     *
     * 这一条是这次修的核心：先按月切片再找原交易，10 月里根本没有那笔支出，
     * 退款只能挂在 10 月，结果是「10 月支出 −100、9 月仍然 100」——两个月都错。
     * 正确结果是 9 月净支出 0、10 月 0。
     */
    private static void crossMonthRefundGoesBackToOriginMonth() {
        long september28 = timestamp(2026, 9, 28, 12, 0);
        long october2 = timestamp(2026, 10, 2, 9, 30);
        List<LedgerEntry> all = list(
                entry(10000, "expense", "某某电器", "购物", september28),
                entry(10000, "income", "某某电器", "退款", october2)
        );

        RefundOffset september = RefundOffset.ofMonth(all, monthStart(2026, 9), monthStart(2026, 10));
        assertEquals(10000L, september.expenseCents, "9 月毛支出应为 100");
        assertEquals(10000L, september.refundCents, "退款要回到原交易所在的 9 月冲抵");
        assertEquals(0L, september.netExpenseCents, "9 月净支出应为 0，而不是仍然 100");
        assertEquals(0L, september.incomeCents, "退款不计入收入");

        RefundOffset october = RefundOffset.ofMonth(all, monthStart(2026, 10), monthStart(2026, 11));
        assertEquals(0L, october.expenseCents, "10 月没有支出");
        assertEquals(0L, october.refundCents, "10 月不该再扣一次退款");
        assertEquals(0L, october.netExpenseCents, "10 月不能出现负数支出");
    }

    /** 退款时间早于支出，不是同一笔，不能拿来扣别人的分类。 */
    private static void refundBeforeExpenseIsNotMatched() {
        RefundOffset result = RefundOffset.of(list(
                entry(5000, "income", "某某超市", "退款", BASE),
                entry(5000, "expense", "某某超市", "购物", BASE + DAY)
        ));
        assertEquals(Long.valueOf(-5000L), result.categoryTotals.get("退款"),
                "顺序颠倒的两条记录不能当成一笔退款");
        assertEquals(Long.valueOf(5000L), result.categoryTotals.get("购物"),
                "支出分类不应该被凭空扣减");
    }

    private static long timestamp(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static long monthStart(int year, int month) {
        return timestamp(year, month, 1, 0, 0);
    }

    private static LedgerEntry entry(long amount, String direction, String merchant, String category, long time) {
        return new LedgerEntry(
                "id-" + time + "-" + amount,
                amount,
                direction,
                merchant,
                "微信",
                category,
                time,
                merchant + " " + category
        );
    }

    private static List<LedgerEntry> list(LedgerEntry... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        checks += 1;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
