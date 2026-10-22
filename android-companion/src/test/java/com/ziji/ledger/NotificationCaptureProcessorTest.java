package com.ziji.ledger;

public final class NotificationCaptureProcessorTest {
    public static void main(String[] args) {
        NotificationCaptureProcessor processor = new NotificationCaptureProcessor();
        NotificationDeduper deduper = new NotificationDeduper(120_000L, 20);
        LocalLedger ledger = new LocalLedger(20);
        PendingCaptureQueue queue = new PendingCaptureQueue(20);

        NotificationCaptureProcessor.Result wechat = processor.process(
                "com.tencent.mm",
                "微信支付收款助手",
                "你已向 美团外卖 支付 ¥46.80，支付方式 零钱",
                1000L,
                1000L,
                deduper,
                ledger,
                queue
        );
        assertTrue(wechat.matched, "WeChat payment should match");
        assertTrue(wechat.fresh, "first WeChat notification should be fresh");
        assertTrue(wechat.recorded, "WeChat payment should enter local ledger");
        assertEquals(4680L, wechat.entry.amountCents, "WeChat amount should parse");
        assertEquals("微信", wechat.entry.account, "WeChat account should parse");
        assertEquals(1, ledger.size(), "ledger should contain WeChat payment");
        assertEquals(1, queue.size(), "sync queue should contain WeChat payment");

        NotificationCaptureProcessor.Result duplicate = processor.process(
                "com.tencent.mm",
                "微信支付收款助手",
                "你已向 美团外卖 支付 ¥46.80，支付方式 零钱",
                1100L,
                1100L,
                deduper,
                ledger,
                queue
        );
        assertTrue(duplicate.matched, "duplicate still matches payment rule");
        assertFalse(duplicate.fresh, "duplicate should be rejected by deduper");
        assertEquals(1, ledger.size(), "duplicate should not enter ledger");
        assertEquals(1, queue.size(), "duplicate should not enter queue");

        NotificationCaptureProcessor.Result bank = processor.process(
                "com.android.mms",
                "招商银行",
                "您尾号8899信用卡在 盒马鲜生 消费人民币128.60元，余额1000.00元。",
                2000L,
                2000L,
                deduper,
                ledger,
                queue
        );
        assertTrue(bank.recorded, "bank SMS notification should enter ledger");
        assertEquals(12860L, bank.entry.amountCents, "bank amount should ignore balance");
        assertEquals("银行卡", bank.entry.account, "bank account should parse");

        NotificationCaptureProcessor.Result alipay = processor.process(
                "com.eg.android.alipaygphone",
                "支付宝",
                "向 星巴克 付款 32.00元 付款方式 花呗",
                3000L,
                3000L,
                deduper,
                ledger,
                queue
        );
        assertTrue(alipay.recorded, "Alipay notification should enter ledger");
        assertEquals("星巴克", alipay.entry.merchant, "Alipay merchant should parse");

        NotificationCaptureProcessor.Result otp = processor.process(
                "com.eg.android.alipaygphone",
                "支付宝安全提醒",
                "验证码123456，请勿泄露。付款金额0.01元",
                4000L,
                4000L,
                deduper,
                ledger,
                queue
        );
        assertFalse(otp.matched, "verification code should be excluded");
        assertEquals("excluded", otp.reason, "excluded reason should be retained");
        assertEquals(3, ledger.size(), "noise notification should not enter ledger");
        assertEquals(3, queue.size(), "noise notification should not enter queue");

        NotificationCaptureProcessor.Result zeroAmount = processor.process(
                "com.eg.android.alipaygphone",
                "支付宝",
                "付款成功，付款金额 0.00 元，商家：测试商户",
                5000L,
                5000L,
                deduper,
                ledger,
                queue
        );
        assertFalse(zeroAmount.matched, "zero amount should not be captured");
        assertEquals("missing_amount", zeroAmount.reason, "zero amount should be rejected as invalid");
        assertEquals(3, ledger.size(), "zero amount should not enter ledger");
        assertEquals(3, queue.size(), "zero amount should not enter queue");

        NotificationDeduper eventDeduper = new NotificationDeduper(120_000L, 20);
        LocalLedger eventLedger = new LocalLedger(20);
        PendingCaptureQueue eventQueue = new PendingCaptureQueue(20);
        NotificationCaptureProcessor.Result firstEvent = processor.process(
                "com.tencent.mm", "微信支付", "微信支付 向 星巴克 支付 12.50元",
                10_000L, 10_000L, eventDeduper, eventLedger, eventQueue, null, "event-1"
        );
        NotificationCaptureProcessor.Result distinctEvent = processor.process(
                "com.tencent.mm", "微信支付", "微信支付 向 星巴克 支付 12.50元",
                11_000L, 11_000L, eventDeduper, eventLedger, eventQueue, null, "event-2"
        );
        NotificationCaptureProcessor.Result eventRetry = processor.process(
                "com.tencent.mm", "微信支付", "微信支付 向 星巴克 支付 12.50元",
                11_000L, 12_000L, eventDeduper, eventLedger, eventQueue, null, "event-2"
        );
        assertTrue(firstEvent.recorded, "first stable notification event is recorded");
        assertTrue(distinctEvent.recorded, "same text from a second notification event is recorded");
        assertFalse(eventRetry.fresh, "retry of the same notification event is ignored");
        assertEquals(2, eventLedger.size(), "two distinct events with identical text remain two transactions");
        assertEquals(2, eventQueue.size(), "both distinct events are queued once");
        System.out.println("notification capture processor tests passed: 28");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
