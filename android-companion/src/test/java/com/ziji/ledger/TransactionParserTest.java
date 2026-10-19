package com.ziji.ledger;

import java.util.Arrays;
import java.util.Calendar;

public final class TransactionParserTest {
    private static int checks = 0;

    public static void main(String[] args) {
        assertEntry(
                "com.eg.android.alipaygphone",
                "支付宝 06月06日12:31 向 星巴克 付款 32.00元 付款方式 花呗",
                3200, "星巴克", "支付宝", "餐饮", "expense"
        );
        assertEntry(
                "com.tencent.mm",
                "微信支付收款助手：你已向 美团外卖 支付 ¥46.80，支付方式 零钱",
                4680, "美团外卖", "微信", "餐饮", "expense"
        );
        assertEntry(
                "com.android.mms",
                "【招商银行】您尾号8899信用卡在 盒马鲜生 消费人民币128.60元，余额1000.00元。",
                12860, "盒马鲜生", "银行卡", "购物", "expense"
        );
        assertEntry(
                "com.unionpay",
                "云闪付 支付成功：您向 永辉超市 支付 ¥86.50",
                8650, "永辉超市", "银行卡", "购物", "expense"
        );
        assertEntry(
                "com.tencent.mm",
                "微信支付：退款到账19.90元，商户 腾讯视频，原交易已退回零钱。",
                1990, "腾讯视频", "微信", "退款", "income"
        );
        // 转账转出：页面上出现「收款」字样但钱是付出去的，必须是支出。
        assertEntry(
                "com.tencent.mm",
                "微信支付 已转账给 张三 0.01元",
                1, "张三", "微信", "人情", "expense"
        );
        assertEntry(
                "com.tencent.mm",
                "微信支付 转账成功 ¥0.01 等待对方确认收款",
                1, null, "微信", null, "expense"
        );
        assertEntry(
                "com.tencent.mm",
                "微信支付 转账成功 ¥0.01 张三 对方已收款",
                1, null, "微信", null, "expense"
        );
        // 真正收到转账才是收入。
        assertEntry(
                "com.tencent.mm",
                "微信支付 收款成功 20.00元 来自 张三",
                2000, "张三", "微信", null, "income"
        );
        LedgerEntry first = TransactionParser.parse("com.tencent.mm", "微信支付收款助手：你已向 美团外卖 支付 ¥46.80，支付方式 零钱", 1000L);
        LedgerEntry second = TransactionParser.parse("com.tencent.mm", "微信支付收款助手：你已向 美团外卖 支付 ¥46.80，支付方式 零钱", 999999L);
        assertEquals(first.fingerprint, second.fingerprint, "stable fingerprint should ignore timestamps");
        LedgerEntry ruled = TransactionParser.parse(
                "com.eg.android.alipaygphone",
                "支付宝 付款成功 商家：瑞幸咖啡 付款金额 ￥18.00",
                2000L,
                Arrays.asList("瑞幸\t咖啡自定义")
        );
        assertEquals("咖啡自定义", ruled.category, "user rule should override default category");

        assertMerchantKey();
        assertAmountScoring();
        assertPurposeLabelIsNotMerchant();
        assertProductCategoryBeatsPlatform();
        assertLearnedProductRuleApplies();
        System.out.println("transaction parser tests passed: " + checks);
    }

    /** 用户手动把「螺蛳粉」归到餐饮后，学到的规则「螺蛳粉→餐饮」下次要能命中商品名。 */
    private static void assertLearnedProductRuleApplies() {
        // 模拟用户学到的商品规则：螺蛳粉 → 餐饮
        java.util.List<String> rules = Arrays.asList("螺蛳粉\t餐饮");
        LedgerEntry entry = TransactionParser.fromPage(
                "com.tencent.mm",
                "支付成功 ¥25.00",
                2500, "美团外卖", "expense", 0L, rules, "螺蛳粉"
        );
        assertEquals("餐饮", entry.category, "商品规则「螺蛳粉→餐饮」应命中 note 里的商品名");
    }

    /**
     * 平台型商户（美团）背后商品千差万别：点咖啡、点外卖、买药都是「付给美团」，
     * 分类必须靠商品名（note）区分，而不是被「美团外卖→餐饮」统一吞掉。
     */
    private static void assertProductCategoryBeatsPlatform() {
        // 美团点星巴克（商品名「生椰拿铁」）→ 餐饮
        LedgerEntry coffee = TransactionParser.fromPage(
                "com.tencent.mm",
                "支付成功 ¥32.00 商品说明 生椰拿铁",
                3200, "美团外卖", "expense", 0L, java.util.Collections.emptyList(), "生椰拿铁"
        );
        assertEquals("餐饮", coffee.category, "美团+生椰拿铁 应归餐饮");

        // 美团买药（商品名「感冒灵」）→ 医疗，不能被平台名吞成餐饮
        LedgerEntry medicine = TransactionParser.fromPage(
                "com.tencent.mm",
                "支付成功 ¥25.00 商品说明 感冒灵",
                2500, "美团外卖", "expense", 0L, java.util.Collections.emptyList(), "感冒灵"
        );
        assertEquals("医疗", medicine.category, "美团+感冒灵 应归医疗而非餐饮");

        // 商品名、商户名都判断不出 → 其他（即待确认，等用户手动补）
        LedgerEntry unknown = TransactionParser.fromPage(
                "com.tencent.mm",
                "支付成功 ¥50.00",
                5000, "未知商户", "expense", 0L, java.util.Collections.emptyList(), ""
        );
        assertEquals("其他", unknown.category, "无任何线索时应归「其他」待确认");
    }

    /**
     * 金额不能靠「页面里唯一的数字」硬猜。
     *
     * 打分原本没有下限（初值 Integer.MIN_VALUE），于是所有候选都是负分时，
     * 排在最前的那个仍会被选中：余额记成支出、时间记成 31 元、年份记成 2026 元。
     */
    private static void assertAmountScoring() {
        assertEquals(0L, TransactionParser.detectAmountCents("支付成功 零钱余额 ¥1234.56"), "余额不是金额");
        assertEquals(0L, TransactionParser.detectAmountCents("支付成功 12:31"), "时间不是金额");
        assertEquals(0L, TransactionParser.detectAmountCents("支付成功 2026"), "年份不是金额");
        assertEquals(0L, TransactionParser.detectAmountCents("支付成功 2026-10-17"), "日期不是金额");
        assertEquals(3200L,
                TransactionParser.detectAmountCents("支付成功 10月17日 12:31 实付 ¥32.00"),
                "真实金额仍要识别");
        assertEquals(3200L, TransactionParser.detectAmountCents("金额：32.00"), "标签冒号后的金额要识别");
    }

    /**
     * 「商品说明」「说明」说的是买了什么，不是在哪买的。
     * 以前它们和「商户」并列在同一个正则里，于是「商品说明 生椰拿铁」解析出的商户
     * 是「说明 生椰拿铁」——账本上凭空多出一家店。
     */
    private static void assertPurposeLabelIsNotMerchant() {
        assertEntry(
                "com.tencent.mm",
                "微信支付 支付成功 商品说明 生椰拿铁 金额 32.00元",
                3200, "未知商户", "微信", null, "expense"
        );
        assertEntry(
                "com.tencent.mm",
                "微信支付 支付成功 商品说明:生椰拿铁 金额 32.00元",
                3200, "未知商户", "微信", null, "expense"
        );
    }

    /**
     * 商户归一化决定「学到的分类」能不能复用：同一家店的不同写法必须收敛成一个关键词，
     * 否则每换一家门店就多学一条规则，而且旧规则对新写法不生效。
     */
    private static void assertMerchantKey() {
        assertEquals("瑞幸咖啡", TransactionParser.merchantKey("瑞幸咖啡(国贸店)"), "半角括号门店名");
        assertEquals("瑞幸咖啡", TransactionParser.merchantKey("瑞幸咖啡（望京店）"), "全角括号门店名");
        assertEquals("星巴克", TransactionParser.merchantKey("星巴克店"), "单个「店」字后缀");
        assertEquals("星巴克", TransactionParser.merchantKey("星巴克"), "无后缀保持不变");
        assertEquals("美团外卖", TransactionParser.merchantKey(" 美团外卖 "), "去掉首尾空白");
        assertEquals("美团科技", TransactionParser.merchantKey("美团科技有限公司"), "去掉公司后缀");
        // 不带括号的「XX国贸店」无法可靠区分「分店名」和「商户本名」
        // （例如「全家便利店」剥掉会变成「全家便利」甚至「全家」），
        // 这里只剥掉末尾的「店」字，不做更激进的猜测——宁可少归一化，也不要把商户名改坏。
        assertEquals("瑞幸咖啡国贸", TransactionParser.merchantKey("瑞幸咖啡国贸店"), "裸门店后缀只剥「店」字");

        // 不能学的东西必须返回空串：学错了会把所有账单都归成一类。
        assertEquals("", TransactionParser.merchantKey("未知商户"), "未知商户不可学");
        assertEquals("", TransactionParser.merchantKey("其他"), "其他不可学");
        assertEquals("", TransactionParser.merchantKey(""), "空串不可学");
        assertEquals("", TransactionParser.merchantKey("¥28.00"), "纯金额不可学");
        assertEquals("", TransactionParser.merchantKey("转账"), "状态词不可学");
        // 「书店」只有两个字，剥掉「店」就废了；长度保护应该让它原样保留。
        // 原文带上日期时间后，日期里的数字不能被当成金额。
        assertEntry(
                "com.tencent.mm",
                "微信支付 支付成功 2026-10-17 12:31:05 ¥32.00 瑞幸咖啡",
                3200, null, "微信", "餐饮", "expense"
        );

        assertEquals("书店", TransactionParser.merchantKey("书店"), "过短的商户名不做剥离");

        // --- 交易时间：通知到得晚，账不能记到第二天 ---
        long now = time(2026, 10, 17, 13, 0, 0);

        assertEquals(time(2026, 10, 17, 12, 31, 5),
                TransactionParser.resolveOccurredAt("微信支付 支付成功 ¥32.00 2026-10-17 12:31:05", now),
                "带年份的完整时间应被采信");
        assertEquals(time(2026, 10, 17, 9, 5, 0),
                TransactionParser.resolveOccurredAt("支付宝 10月17日 09:05 向 星巴克 付款 32.00元", now),
                "月日时分应补全当年");
        // 跨年：1 月 2 日收到写着「12月31日」的通知，属于去年。
        assertEquals(time(2025, 12, 31, 20, 0, 0),
                TransactionParser.resolveOccurredAt(
                        "微信支付 12月31日 20:00 向 张三 转账 100.00元",
                        time(2026, 1, 2, 10, 0, 0)
                ),
                "没有年份的月日在未来时应回退到去年");
        assertEquals(time(2026, 10, 16, 12, 31, 0),
                TransactionParser.resolveOccurredAt("微信支付 昨天 12:31 向 张三 转账 100.00元", now),
                "「昨天」要相对当前时间回退一天");
        assertEquals(time(2026, 10, 17, 12, 31, 0),
                TransactionParser.resolveOccurredAt("微信支付 12:31 向 星巴克 付款 32.00元", now),
                "只有时分且早于当前时间，应算今天");
        // 跨午夜：00:10 收到 23:50 的通知，这笔钱是前一天花的。
        assertEquals(time(2026, 10, 16, 23, 50, 0),
                TransactionParser.resolveOccurredAt("支付宝 23:50 消费 20.00元", time(2026, 10, 17, 0, 10, 0)),
                "晚于当前时间的时分属于昨天");
        assertEquals(now,
                TransactionParser.resolveOccurredAt("微信支付 支付成功 ¥32.00", now),
                "没有时间信息时回落到当前时间");
        assertEquals(now,
                TransactionParser.resolveOccurredAt("支付宝 2026-10-18 09:00 支付 32.00元", now),
                "未来时间不可信，回落到当前时间");
        assertEquals(now,
                TransactionParser.resolveOccurredAt("支付宝 02月30日 09:00 支付 32.00元", now),
                "非法日期不应被采信");
        assertEquals(now,
                TransactionParser.resolveOccurredAt("支付宝 12月31日 09:00 支付 32.00元", now),
                "跨年的旧日期超出可信范围，回落到当前时间");
    }

    private static long time(int year, int month, int day, int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        return calendar.getTimeInMillis();
    }

    private static void assertEntry(
            String packageName,
            String text,
            long amount,
            String merchant,
            String account,
            String category,
            String direction
    ) {
        LedgerEntry entry = TransactionParser.parse(packageName, text, 1000L);
        assertEquals(amount, entry.amountCents, text);
        if (merchant != null) assertEquals(merchant, entry.merchant, text);
        assertEquals(account, entry.account, text);
        if (category != null) assertEquals(category, entry.category, text);
        assertEquals(direction, entry.direction, text);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        checks += 1;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
