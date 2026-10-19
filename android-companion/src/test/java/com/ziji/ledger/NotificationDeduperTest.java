package com.ziji.ledger;

public final class NotificationDeduperTest {
    public static void main(String[] args) {
        NotificationDeduper deduper = new NotificationDeduper(120_000L, 2);

        assertTrue("first notification", deduper.shouldPost("支付宝 付款 32.00元", 1_000L));
        assertFalse("same notification inside window", deduper.shouldPost("支付宝   付款 32.00元", 2_000L));
        assertTrue("same notification after window", deduper.shouldPost("支付宝 付款 32.00元", 122_001L));

        assertTrue("second entry", deduper.shouldPost("微信 支付 46.80元", 123_000L));
        assertTrue("third entry trims oldest", deduper.shouldPost("银行 消费 128.60元", 124_000L));
        assertTrue("trimmed entry can post again", deduper.shouldPost("支付宝 付款 32.00元", 125_000L));
        assertFalse("blank notification", deduper.shouldPost("   ", 126_000L));

        System.out.println("notification deduper tests passed: 7");
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) throw new AssertionError(name + " should be true");
    }

    private static void assertFalse(String name, boolean value) {
        if (value) throw new AssertionError(name + " should be false");
    }
}
