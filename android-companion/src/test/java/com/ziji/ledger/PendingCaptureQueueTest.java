package com.ziji.ledger;

public final class PendingCaptureQueueTest {
    public static void main(String[] args) {
        PendingCaptureQueue queue = new PendingCaptureQueue(2);

        assertTrue("first add", queue.add("支付宝 付款 32.00元"));
        assertFalse("duplicate add", queue.add("支付宝   付款 32.00元"));
        assertTrue("second add", queue.add("微信 支付 46.80元"));
        assertTrue("third add trims oldest", queue.add("银行 消费 128.60元"));
        assertEquals("size after trim", 2, queue.size());
        assertFalse("old item trimmed", queue.remove("支付宝 付款 32.00元"));

        String stored = queue.toStoredString();
        PendingCaptureQueue restored = PendingCaptureQueue.fromStoredString(stored, 5);
        assertEquals("restored size", 2, restored.size());
        assertTrue("remove restored item", restored.remove("微信 支付 46.80元"));
        assertEquals("size after remove", 1, restored.size());

        System.out.println("pending capture queue tests passed: 8");
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) throw new AssertionError(name + " should be true");
    }

    private static void assertFalse(String name, boolean value) {
        if (value) throw new AssertionError(name + " should be false");
    }

    private static void assertEquals(String name, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
