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

        CaptureEvent firstCandidate = new CaptureEvent(
                4000L, "possible_duplicate", CaptureHistory.possibleDuplicateReason("entry-a"),
                "com.tencent.mm", "微信支付 ¥12.00");
        CaptureHistory pending = new CaptureHistory(2);
        pending.add(firstCandidate);
        pending.add(new CaptureEvent(5000L, "captured", "captured", "com.tencent.mm", "普通记录 1"));
        pending.add(new CaptureEvent(6000L, "captured", "captured", "com.tencent.mm", "普通记录 2"));
        pending.add(new CaptureEvent(7000L, "captured", "captured", "com.tencent.mm", "普通记录 3"));
        assertEquals(3, pending.size(), "待复核候选不应被普通历史事件挤掉");
        assertEquals(true, CaptureHistory.isPendingDuplicate(firstCandidate, pending.entries()),
                "未处理候选仍应处于待复核状态");

        CaptureEvent sameTextCandidate = new CaptureEvent(
                4100L, "possible_duplicate", CaptureHistory.possibleDuplicateReason("entry-a"),
                "com.tencent.mm", firstCandidate.text);
        pending.add(sameTextCandidate);
        CaptureEvent resolution = new CaptureEvent(
                8000L, "resolved", CaptureHistory.resolutionReason(firstCandidate, "candidate_kept"),
                "com.tencent.mm", firstCandidate.text);
        pending.add(resolution);
        assertEquals(false, CaptureHistory.isPendingDuplicate(firstCandidate, pending.entries()),
                "处理后候选应标为已解决");
        assertEquals(true, CaptureHistory.isPendingDuplicate(sameTextCandidate, pending.entries()),
                "处理一条候选不能顺带隐藏同文本的另一笔");

        CaptureHistory restoredPending = CaptureHistory.fromStoredString(pending.toStoredString(), 2);
        assertEquals(true, CaptureHistory.isPendingDuplicate(sameTextCandidate, restoredPending.entries()),
                "待复核状态应跨历史持久化恢复");
        System.out.println("capture history tests passed: 10");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
