package com.ziji.ledger;

public final class LocalLedgerTest {
    private static int checks = 0;

    public static void main(String[] args) {
        LocalLedger ledger = new LocalLedger(2);
        LedgerEntry first = TransactionParser.parse("com.tencent.mm", "微信支付 向 星巴克 支付 12.50元", 1000L);
        LedgerEntry duplicate = TransactionParser.parse("com.tencent.mm", "微信支付 向 星巴克 支付 12.50元", 1100L);
        LedgerEntry second = TransactionParser.parse("com.eg.android.alipaygphone", "支付宝 向 滴滴出行 付款 20.00元", 2000L);
        LedgerEntry third = TransactionParser.parse("com.android.mms", "招商银行 在 永辉超市 消费人民币30.00元", 3000L);

        assertTrue(ledger.add(first), "first entry should be added");
        assertTrue(ledger.add(duplicate), "identical text with a distinct transaction ID is not enough evidence to drop a payment");
        assertTrue(ledger.add(second), "second entry should be added");
        assertTrue(ledger.add(third), "third entry should be added");
        assertEquals(2, ledger.size(), "ledger should enforce max entries");
        assertEquals(5000L, ledger.expenseTotalSince(1500L), "expense total should use stored cents");

        // 稳定事件 ID 跨服务重启保持不变：重放幂等；新事件即使原文相同也保留。
        LocalLedger eventLedger = new LocalLedger(10);
        String notificationText = "支付宝 向 滴滴出行 付款 20.00元";
        LedgerEntry firstEvent = TransactionParser.parse("com.eg.android.alipaygphone", notificationText, 2300L, null, "notification-1");
        LedgerEntry retriedEvent = TransactionParser.parse("com.eg.android.alipaygphone", notificationText, 2300L, null, "notification-1");
        LedgerEntry nextEvent = TransactionParser.parse("com.eg.android.alipaygphone", notificationText, 2400L, null, "notification-2");
        assertTrue(eventLedger.add(firstEvent), "first notification event should be stored");
        assertFalse(eventLedger.add(retriedEvent), "same stable event ID should be idempotent");
        assertTrue(eventLedger.add(nextEvent), "a distinct event with identical text should be stored");

        // 用户报告的场景：两笔相同金额、相同商户的支付，时间间隔较长时
        // 属于两笔真实交易，不能因为「指纹相同」就永久丢掉后一笔。
        LocalLedger repeatedLedger = new LocalLedger(10);
        LedgerEntry morning = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 转账成功 ¥0.01 张三 对方已收款",
                1_000_000L
        );
        LedgerEntry evening = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 转账成功 ¥0.01 张三 对方已收款",
                10_000_000L
        );
        assertTrue(repeatedLedger.add(morning), "first transfer should be stored");
        assertTrue(repeatedLedger.add(evening),
                "a later identical transfer should still be stored");
        assertEquals(2, repeatedLedger.size(), "both transfers should be kept");

        LocalLedger restored = LocalLedger.fromStoredString(ledger.toStoredString(), 2);
        assertEquals(2, restored.size(), "serialized ledger should restore entries");
        assertEquals("永辉超市", restored.entries().get(0).merchant, "newest entry should stay first");
        assertEquals(3000L, restored.entries().get(0).occurredAtMillis, "timestamp should round-trip");

        LedgerEntry updated = new LedgerEntry(
                restored.entries().get(0).id,
                3150L,
                "expense",
                "永辉生活",
                "银行卡",
                "购物",
                3000L,
                restored.entries().get(0).rawText
        );
        assertTrue(restored.update(updated), "existing entry should update by id");
        assertEquals(3150L, restored.entries().get(0).amountCents, "updated amount should be stored");
        assertTrue(restored.removeById(updated.id), "existing entry should be removable by id");
        assertEquals(1, restored.size(), "remove should reduce size");
        assertFalse(restored.removeById("missing"), "missing id should not remove anything");

        // --- 跨通道去重：不能把真实的第二笔消费当成重复吞掉 ---
        LocalLedger crossChannel = new LocalLedger(50);
        // 微信支付通知里通常没有商户，记下来就是「未知商户」——这正是判重最容易出错的情形。
        LedgerEntry notified = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 支付成功 ¥8.00",
                10_000_000L
        );
        assertTrue(crossChannel.add(notified), "通知通道先记一笔");
        assertEquals("未知商户", notified.merchant, "通知里没有商户信息");

        // 1 分钟后又买了一杯同样价钱的奶茶，被页面通道识别到。
        // 商户未知 + 金额相同，但隔了 1 分钟——这是两笔真实消费，必须留下。
        assertFalse(crossChannel.hasSimilar(
                800L, "expense", "蜜雪冰城", 10_060_000L, 2 * 60 * 1000L, "accessibility"
        ), "同金额但隔了 1 分钟，不能判成重复");
        LedgerEntry uncertain = TransactionParser.parse(
                "com.tencent.mm", "微信支付 支付成功 ¥8.00", 10_090_000L
        );
        assertTrue(crossChannel.findPossibleSimilar(uncertain) == null,
                "同一采集来源的不同事件不该被判成疑似重复而阻止入账");
        LedgerEntry crossChannelUncertain = TransactionParser.fromPage(
                "com.tencent.mm", "支付成功 ¥8.00", 800L, "未知商户", "expense",
                10_060_000L, java.util.Collections.emptyList()
        );
        assertEquals(notified.id, crossChannel.findPossibleSimilar(crossChannelUncertain).id,
                "跨来源且证据不足的组合保留复核候选");

        // 同一次支付：通知和页面几乎同时看到，时间差几秒，应判重。
        assertTrue(crossChannel.hasSimilar(
                800L, "expense", "蜜雪冰城", 10_002_000L, 2 * 60 * 1000L, "accessibility"
        ), "间隔两秒的同金额交易属于跨通道重复");

        // 商户都明确却对不上，即使同一秒也不是同一笔。
        LocalLedger namedLedger = new LocalLedger(50);
        assertTrue(namedLedger.add(TransactionParser.parse(
                "com.tencent.mm", "微信支付 支付成功 ¥8.00 商家：蜜雪冰城", 20_000_000L
        )), "带商户的通知应入账");
        assertFalse(namedLedger.hasSimilar(
                800L, "expense", "瑞幸咖啡", 20_001_000L, 2 * 60 * 1000L, "accessibility"
        ), "商户不同就不该判重");
        LedgerEntry differentMerchant = TransactionParser.parse(
                "com.tencent.mm", "微信支付 支付成功 ¥8.00 商家：星巴克", 20_001_000L
        );
        assertTrue(namedLedger.findPossibleSimilar(differentMerchant) == null, "明确不同商户不进入疑似重复");

        // 同一通道内、前后差 1 秒、金额相同：不自动合并，真实连续支付必须保留。
        assertFalse(crossChannel.hasSimilar(
                800L, "expense", "蜜雪冰城", 10_001_000L, 2 * 60 * 1000L, "notification"
        ), "同通道的独立通知事件不因金额和时间相近而自动合并");

        // 但同通道、隔一分钟就不再判重：那可能是真实的第二笔。
        assertFalse(crossChannel.hasSimilar(
                800L, "expense", "蜜雪冰城", 10_060_000L, 2 * 60 * 1000L, "notification"
        ), "同通道隔一分钟的同金额记录不算重复");

        // 页面重复由本地签名冷却覆盖；超过短窗口的同店同额交易要留给真实复购。
        LocalLedger pageLedger = new LocalLedger(50);
        assertTrue(pageLedger.add(TransactionParser.parse(
                "com.tencent.mm", "微信支付 支付成功 ¥14.00 商家：红妞包子", 40_000_000L
        )), "第一笔应入账");
        assertFalse(pageLedger.hasSimilar(
                1400L, "expense", "红妞包子", 40_060_000L, 2 * 60 * 1000L, "accessibility", "com.tencent.mm"
        ), "同通道间隔 1 分钟的同店消费可能是复购");
        assertFalse(pageLedger.hasSimilar(
                1400L, "expense", "红妞包子", 40_120_000L, 2 * 60 * 1000L, "accessibility", "com.tencent.mm"
        ), "超过短冷却的同店同额交易可能是复购");
        assertFalse(pageLedger.hasSimilar(
                1400L, "expense", "红妞包子", 40_700_000L, 2 * 60 * 1000L, "accessibility", "com.tencent.mm"
        ), "超过短冷却的同商户同金额可能是真实第二笔，不判重");

        System.out.println("local ledger tests passed: " + checks);
    }

    private static void assertTrue(boolean value, String message) {
        checks += 1;
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        checks += 1;
        if (value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        checks += 1;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
