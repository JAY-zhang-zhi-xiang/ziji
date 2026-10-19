package com.ziji.ledger;

import java.util.Arrays;
import java.util.List;

/**
 * 「用途」自动推断的回归测试。
 *
 * 这里守的是产品底线：用户不需要为了知道「这 32 块花在哪」而手动补录。
 * 支付页上本来就写着商品说明，通知里本来就写着转账说明，只要解析能把它们读出来。
 */
public final class PurposeResolverTest {
    private static int checks = 0;

    public static void main(String[] args) {
        // --- 页面「标签 → 值」配对：真实微信支付成功页的节点结构 ---
        assertEquals("生椰拿铁", resolveNodes(
                "瑞幸咖啡",
                "支付成功", "¥32.00", "瑞幸咖啡", "商品说明", "生椰拿铁", "支付方式", "零钱"
        ), "商品说明下一行就是用途");

        // --- 转账说明 ---
        assertEquals("午饭AA", resolveNodes(
                "张三",
                "转账成功", "¥100.00", "张三", "转账说明", "午饭AA", "零钱"
        ), "转账说明下一行就是用途");

        // --- 标签和值在同一个节点 ---
        assertEquals("关东煮", resolveNodes(
                "某某便利店",
                "支付成功", "¥45.00", "某某便利店", "商品说明：关东煮"
        ), "标签与值同节点时也要能拆开");

        // --- 订单标题 ---
        assertEquals("双人套餐", resolveNodes(
                "海底捞",
                "支付成功", "¥268.00", "海底捞", "订单标题", "双人套餐", "订单号", "2026101722001"
        ), "订单标题同样表达用途");

        // --- 金额、时间、单号、账户都不能被当成用途 ---
        assertEquals("", resolveNodes(
                "某某商店",
                "支付成功", "¥99.00", "某某商店", "商品说明", "¥99.00"
        ), "金额不能当用途");
        assertEquals("", resolveNodes(
                "某某商店",
                "支付成功", "¥99.00", "某某商店", "商品说明", "2026-10-17 17:20"
        ), "时间不能当用途");
        assertEquals("", resolveNodes(
                "某某商店",
                "支付成功", "¥99.00", "某某商店", "备注", "零钱"
        ), "支付账户不能当用途");

        // --- 用途等于商户名时丢弃：避免「瑞幸咖啡 / 瑞幸咖啡」这种重复展示 ---
        assertEquals("", resolveNodes(
                "瑞幸咖啡",
                "支付成功", "¥32.00", "瑞幸咖啡", "商品说明", "瑞幸咖啡"
        ), "用途与商户同名时没有信息量");

        // --- 通知正文里的转账说明 ---
        assertEquals("午饭AA", resolveText(
                "微信支付 转账成功 ¥100.00 张三 转账说明：午饭AA", "张三"
        ), "通知里的转账说明要能读出来");
        assertEquals("奶茶三兄弟", resolveText(
                "支付宝 支付成功 ¥45.00 某某便利店 商品说明 奶茶三兄弟", "某某便利店"
        ), "通知里的商品说明要能读出来");

        // --- 通知里没有字段时，用词典把品牌翻译成用途 ---
        assertEquals("美团外卖", PurposeResolver.fromDictionary(
                "张三", "微信支付 转账成功 ¥20.00 张三 美团外卖代付"
        ).note, "词典能把页面里的品牌词补成用途");
        assertEquals("", PurposeResolver.fromDictionary(
                "瑞幸咖啡", "微信支付 支付成功 ¥32.00 瑞幸咖啡"
        ).note, "词典命中的就是商户本身时不该重复成用途");

        // --- 词典决定分类：「其他」不该是绝大多数账单的归宿 ---
        assertEquals("餐饮", TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥8.00 蜜雪冰城", 1L, null).category,
                "蜜雪冰城应归餐饮");
        assertEquals("餐饮", TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥32.00 瑞幸咖啡", 1L, null).category,
                "瑞幸咖啡应归餐饮");
        assertEquals("交通", TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥18.00 滴滴出行", 1L, null).category,
                "滴滴应归交通");
        assertEquals("购物", TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥58.00 名创优品", 1L, null).category,
                "名创优品应归购物");
        assertEquals("娱乐", TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥25.00 猫眼电影", 1L, null).category,
                "猫眼应归娱乐");
        assertEquals("医疗", TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥36.00 大参林药房", 1L, null).category,
                "大参林应归医疗");

        // --- 词典不能把未知消费硬塞进分类 ---
        assertEquals("其他", TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥12.00 张三小卖部", 1L, null).category,
                "词典没命中就应留在其他，而不是乱猜");

        // --- 收入方向优先于词典 ---
        assertEquals("退款", TransactionParser.parse("com.tencent.mm", "微信支付 退款成功 ¥18.00 瑞幸咖啡", 1L, null).category,
                "退款不能因为命中瑞幸就被归成餐饮");

        // --- 端到端：页面通道把用途写进账本条目 ---
        assertPageEntryNote();

        // --- 页面通道没有线索时不编造 ---
        assertPageEntryWithoutPurpose();

        System.out.println("purpose resolver tests passed: " + checks);
    }

    private static void assertPageEntryNote() {
        List<String> nodes = Arrays.asList(
                "支付成功", "¥32.00", "瑞幸咖啡", "商品说明", "生椰拿铁", "支付方式", "零钱"
        );
        PaymentPageMatcher.Result result = PaymentPageMatcher.inspect("com.tencent.mm", "微信支付", nodes);
        assertTrue(result.shouldCapture, "page should be captured");
        assertEquals("生椰拿铁", result.purpose.note, "matcher should expose the purpose");
        assertTrue(result.purpose.isAutomatic(), "purpose should be marked as automatic");

        LedgerEntry entry = TransactionParser.fromPage(
                "com.tencent.mm",
                result.normalizedText,
                result.amountCents,
                result.merchant,
                result.direction,
                1_000L,
                null,
                result.purpose.note
        );
        assertEquals("生椰拿铁", entry.note, "entry should carry the purpose");
        assertEquals("餐饮", entry.category, "category should come from the dictionary");
        assertFalse(entry.needsPurpose(), "a captured purpose means the entry needs no follow-up");
    }

    private static void assertPageEntryWithoutPurpose() {
        // 页面只有金额和商户，没有任何商品字段，也没有命中词典。
        List<String> nodes = Arrays.asList("支付成功", "¥12.00", "张三小卖部");
        PaymentPageMatcher.Result result = PaymentPageMatcher.inspect("com.tencent.mm", "微信支付", nodes);
        assertTrue(result.shouldCapture, "page should still be captured");
        assertEquals("", result.purpose.note, "no purpose found means empty note, not a guess");

        LedgerEntry entry = TransactionParser.fromPage(
                "com.tencent.mm",
                result.normalizedText,
                result.amountCents,
                result.merchant,
                result.direction,
                2_000L,
                null,
                result.purpose.note
        );
        assertTrue(entry.needsPurpose(), "without a purpose the entry is still flagged, but not blocked");
        assertEquals(1200L, entry.amountCents, "amount is recorded regardless");
        assertEquals("张三小卖部", entry.merchant, "merchant is recorded regardless");
    }

    /** 模拟无障碍通道：节点有序列表 → 整页文本。 */
    private static String resolveNodes(String merchant, String... nodes) {
        List<String> list = Arrays.asList(nodes);
        String page = String.join(" ", list);
        return PurposeResolver.resolve(list, page, merchant).note;
    }

    private static String resolveText(String text, String merchant) {
        PurposeResolver.Result fromText = PurposeResolver.resolveFromText(text, merchant);
        if (fromText.isPresent()) return fromText.note;
        return PurposeResolver.fromDictionary(merchant, text).note;
    }

    private static void assertTrue(boolean value, String message) {
        checks += 1;
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        checks += 1;
        if (value) throw new AssertionError(message);
    }

    private static void assertEquals(String expected, String actual, String message) {
        checks += 1;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=[" + expected + "] actual=[" + actual + "]");
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        checks += 1;
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
