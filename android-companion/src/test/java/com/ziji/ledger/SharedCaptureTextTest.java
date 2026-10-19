package com.ziji.ledger;

public final class SharedCaptureTextTest {
    public static void main(String[] args) {
        String text = SharedCaptureText.fromParts(
                "微信支付凭证",
                "微信支付凭证",
                "你已支付给瑞幸咖啡¥18.00 支付方式 零钱"
        );

        assertEquals(
                "deduped shared text",
                "微信支付凭证 你已支付给瑞幸咖啡¥18.00 支付方式 零钱",
                text
        );

        // 微信包名的来源判定只看通知标题（标题就是发送方「微信支付」），
        // 正文提到「微信支付」的好友聊天不能再冒充支付通知。
        NotificationMatcher.MatchResult result = NotificationMatcher.inspect("com.tencent.mm", "微信支付", text);
        assertTrue("shared payment text should match", result.shouldCapture());
        assertFalse("chat with wechat-pay wording must not pass", NotificationMatcher.inspect("com.tencent.mm", "张三", text).shouldCapture());

        String romText = new NotificationTextBuilder()
                .add("支付宝")
                .add("交易提醒")
                .add("2026-09-01 12:31")
                .add("支付成功")
                .add("向 星巴克 付款 32.00元")
                .add("摘要：午餐")
                .build();
        assertTrue("rom text should match", NotificationMatcher.inspect("com.eg.android.alipaygphone", "支付宝", romText).shouldCapture());

        System.out.println("shared capture text tests passed: 3");
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) throw new AssertionError(name + " should be true");
    }

    private static void assertFalse(String name, boolean value) {
        if (value) throw new AssertionError(name + " should be false");
    }

    private static void assertEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
