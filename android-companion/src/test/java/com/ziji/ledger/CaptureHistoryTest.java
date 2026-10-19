package com.ziji.ledger;

public final class CaptureHistoryTest {
    public static void main(String[] args) {
        CaptureHistory history = new CaptureHistory(2);
        history.add(new CaptureEvent(1000L, "captured", "captured", "com.tencent.mm", "微信支付 12.00元"));
        history.add(new CaptureEvent(2000L, "duplicate", "duplicate", "com.tencent.mm", "微信支付 12.00元"));
        history.add(new CaptureEvent(3000L, "rejected", "missing_amount", "com.eg.android.alipaygphone", "支付宝付款成功"));

        assertEquals(2, history.size(), "history should enforce max entries");
        assertEquals("rejected", history.entries().get(0).status, "newest event should be first");
        CaptureHistory restored = CaptureHistory.fromStoredString(history.toStoredString(), 2);
        assertEquals(2, restored.size(), "history should restore entries");
        assertEquals("missing_amount", restored.entries().get(0).reason, "reason should round-trip");
        assertEquals("微信支付 12.00元", restored.entries().get(1).text, "Chinese text should round-trip");
        System.out.println("capture history tests passed: 5");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
