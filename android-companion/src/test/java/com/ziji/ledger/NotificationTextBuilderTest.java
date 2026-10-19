package com.ziji.ledger;

public final class NotificationTextBuilderTest {
    public static void main(String[] args) {
        String text = new NotificationTextBuilder()
                .add("支付宝")
                .add("支付宝")
                .add(new CharSequence[] {
                        "06月06日 12:31",
                        "向 星巴克 付款 32.00元",
                        "付款方式 花呗"
                })
                .add(null)
                .add("  付款方式   花呗  ")
                .build();

        assertEquals(
                "merged text",
                "支付宝 06月06日 12:31 向 星巴克 付款 32.00元 付款方式 花呗",
                text
        );

        NotificationMatcher.MatchResult result = NotificationMatcher.inspect(
                "com.eg.android.alipaygphone",
                "",
                text
        );
        assertTrue("merged notification should match", result.shouldCapture());

        System.out.println("notification text builder tests passed: 2");
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) throw new AssertionError(name + " should be true");
    }

    private static void assertEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
