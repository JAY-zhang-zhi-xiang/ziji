package com.ziji.ledger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PaymentPageMatcherTest {
    private static int checks = 0;

    public static void main(String[] args) {
        // --- 应当采集：支付成功页 ---
        accepts("wechat success page", "com.tencent.mm", "微信支付",
                Arrays.asList("支付成功", "¥46.80", "美团外卖", "零钱"),
                4680L, "美团外卖", "expense");
        accepts("alipay success page no unit", "com.eg.android.alipaygphone", "支付宝",
                Arrays.asList("支付成功", "45.00", "某某便利店", "花呗"),
                4500L, "某某便利店", "expense");
        accepts("alipay success with yuan", "com.eg.android.alipaygphone", "支付宝",
                Arrays.asList("付款成功", "付款金额", "32.00", "星巴克", "余额"),
                3200L, "星巴克", "expense");
        accepts("unionpay success", "com.unionpay", "云闪付",
                Arrays.asList("交易成功", "88.00元", "永辉超市"),
                8800L, "永辉超市", "expense");
        accepts("bank app success", "com.icbc", "工商银行",
                Arrays.asList("交易成功", "人民币 128.60 元", "盒马鲜生"),
                12860L, "盒马鲜生", "expense");
        accepts("alipay compact success", "com.eg.android.alipaygphone", "支付宝",
                Arrays.asList("支付成功", "29.90", "瑞幸咖啡"),
                2990L, "瑞幸咖啡", "expense");
        // 「商品说明」是用途标签：标签本身和它后面的值都不是商户。
        // 之前会在账本上记出一家叫「生椰拿铁」的店。
        accepts("purpose label is not merchant", "com.eg.android.alipaygphone", "支付宝",
                Arrays.asList("支付成功", "¥32.00", "商品说明", "生椰拿铁", "零钱"),
                3200L, "未知商户", "expense");

        // --- 应当采集：收款/退款（收入方向） ---
        accepts("wechat receive money", "com.tencent.mm", "微信支付",
                Arrays.asList("收款成功", "20.00元", "来自 张三"),
                2000L, null, "income");
        accepts("alipay refund", "com.eg.android.alipaygphone", "支付宝",
                Arrays.asList("退款成功", "18.00元", "瑞幸咖啡"),
                1800L, "瑞幸咖啡", "income");

        // --- 转账场景：钱转出去，页面写着「收款」，不能记成收入 ---
        accepts("wechat transfer waiting receiver", "com.tencent.mm", "微信支付",
                Arrays.asList("转账成功", "¥0.01", "张三", "等待对方确认收款"),
                1L, "张三", "expense");
        accepts("wechat transfer receiver got it", "com.tencent.mm", "微信支付",
                Arrays.asList("转账成功", "¥0.01", "张三", "对方已收款"),
                1L, "张三", "expense");
        accepts("wechat transfer plain", "com.tencent.mm", "微信支付",
                Arrays.asList("转账成功", "¥0.01", "张三"),
                1L, "张三", "expense");

        // --- 应当拒绝：未完成的支付页 ---
        rejects("confirm payment page", "com.eg.android.alipaygphone", "支付宝",
                Arrays.asList("确认付款", "¥99.00", "立即支付"), "not_completed");
        rejects("pay password page", "com.tencent.mm", "微信支付",
                Arrays.asList("请输入支付密码", "¥99.00"), "not_completed");
        rejects("payment code page", "com.tencent.mm", "微信支付",
                Arrays.asList("付款码已开启", "请注意资金安全"), "not_completed");

        // --- 应当拒绝：失败的支付页 ---
        rejects("failed payment page", "com.eg.android.alipaygphone", "支付宝",
                Arrays.asList("支付失败", "¥99.00", "余额不足"), "not_completed");

        // --- 应当拒绝：非支付页 ---
        rejects("chat page", "com.tencent.mm", "张三",
                Arrays.asList("晚上一起吃饭吗"), "missing_success");
        rejects("bill list page", "com.eg.android.alipaygphone", "支付宝",
                Arrays.asList("账单", "本月支出", "1,234.56"), "missing_success");

        // --- 应当拒绝：微信聊天窗口（历史转账气泡不是新支付） ---
        // 用户打开旧聊天，气泡自带「¥32.00」「对方已收款」，以前会被记成一笔支出。
        rejects("chat window transfer bubble", "com.tencent.mm", "com.tencent.mm.ui.LauncherUI",
                Arrays.asList("张三", "¥32.00", "对方已收款"), "chat_window");
        rejects("old chatting ui window", "com.tencent.mm", "com.tencent.mm.ui.chatting.ChattingUI",
                Arrays.asList("转账成功", "¥0.01", "等待对方确认收款"), "chat_window");
        // 窗口内容变化事件拿不到 Activity 类名，用聊天输入栏特征词兜底。
        rejects("chat content marker", "com.tencent.mm", "微信支付",
                Arrays.asList("张三", "¥32.00", "对方已收款", "按住说话"), "chat_window");

        // --- 应当拒绝：订单列表/详情页（历史订单的「交易成功」不是新支付） ---
        rejects("mini program order list", "com.tencent.mm", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                Arrays.asList("交易成功", "¥32.00", "美团外卖", "待收货", "查看物流"), "order_list");
        rejects("alipay order detail", "com.eg.android.alipaygphone", "支付宝",
                Arrays.asList("交易成功", "¥128.00", "申请售后"), "order_list");
        // 用户实测幽灵账单：美团订单状态页「已支付 ¥14.00 商家已接单」，同一笔订单
        // 在 18:02、18:10 反复入账。「已支付」已从成功信号里移除，履约词再兜一层。
        rejects("meituan order status page", "com.tencent.mm", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                Arrays.asList("已支付", "¥14.00", "微信支付", "商家已接单", "配送中"), "order_list");
        rejects("meituan order fulfilling", "com.sankuai.meituan.takeoutnew", "美团",
                Arrays.asList("交易成功", "¥14.00", "商家已接单", "骑手配送中"), "order_list");
        rejects("meituan merchant chat", "com.tencent.mm", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                Arrays.asList("¥7.00", "交易成功", "可给对方留言复购、咨询"), "order_list");
        // 电商 App 进白名单后，订单页是最大的误报源，履约词必须拦得住。
        rejects("taobao order detail", "com.taobao.taobao", "淘宝",
                Arrays.asList("交易成功", "¥129.00", "等待发货", "确认收货", "查看物流"), "order_list");
        rejects("jd order detail", "com.jingdong.app.mall", "京东",
                Arrays.asList("交易成功", "¥45.80", "已发货", "查看物流", "申请退款"), "order_list");

        // --- 应当采集：美团 App 内的支付结果页（sankuai 包名可信） ---
        accepts("meituan app payment success", "com.sankuai.meituan", "美团",
                Arrays.asList("支付成功", "¥14.00", "红妞包子-软景店"),
                1400L, "红妞包子-软景店", "expense");
        accepts("meituan takeout app payment", "com.sankuai.meituan.takeoutnew", "美团外卖",
                Arrays.asList("付款成功", "付款金额", "¥28.50", "老王面馆", "余额"),
                2850L, "老王面馆", "expense");

        // --- 应当采集：电商 App 内的支付结果页（SDK 收银台跑在自家进程里） ---
        accepts("taobao payment success", "com.taobao.taobao", "淘宝",
                Arrays.asList("付款成功", "¥129.00", "支付宝", "花呗"),
                12900L, "未知商户", "expense");
        accepts("jd payment success", "com.jingdong.app.mall", "京东",
                Arrays.asList("支付成功", "¥45.80", "微信支付"),
                4580L, "未知商户", "expense");
        accepts("pinduoduo payment success", "com.xunmeng.pinduoduo", "拼多多",
                Arrays.asList("支付成功", "¥9.90", "零钱"),
                990L, "未知商户", "expense");
        accepts("eleme payment success", "com.alibaba.android.riming", "饿了么",
                Arrays.asList("支付成功", "¥23.50", "商家", "沙县小吃"),
                2350L, "沙县小吃", "expense");
        accepts("douyin payment success", "com.ss.android.ugc.aweme", "抖音",
                Arrays.asList("支付成功", "¥19.90"),
                1990L, "未知商户", "expense");
        accepts("bank app psbc success", "com.psbc.mobilephonebanking", "邮储银行",
                Arrays.asList("交易成功", "人民币 66.00 元"),
                6600L, "未知商户", "expense");

        // --- 商户名兜底扫描不收界面垃圾词（用户账本上出现过「星标」这种店） ---
        accepts("junk node not merchant", "com.tencent.mm", "微信支付",
                Arrays.asList("支付成功", "¥14.00", "星标", "零钱"),
                1400L, "未知商户", "expense");

        // --- 应当拒绝：成功但无金额 ---
        rejects("success without amount", "com.tencent.mm", "微信支付",
                Arrays.asList("支付成功", "请查看详情"), "missing_amount");

        // --- 应当拒绝：未知来源 ---
        rejects("unknown package", "com.example.game", "游戏中心",
                Arrays.asList("支付成功", "9.90元"), "unknown_source");

        // --- 空输入 ---
        rejects("empty page", "com.tencent.mm", "", Collections.emptyList(), "empty");

        // --- 跨通道去重 ---
        assertCrossChannelDedup();
        assertPrePaymentMerchant();

        System.out.println("payment page matcher tests passed: " + checks);
    }

    /** 付款前确认页能抽出对方名，结果页能拿这个暂存名兜底商户。 */
    private static void assertPrePaymentMerchant() {
        // 确认页：向「张三」转账，但没有「成功」字样，不能采集，只能暂存商户名。
        String pending = PaymentPageMatcher.detectPrePaymentMerchant(
                "向 张三 转账 ¥32.00",
                Arrays.asList("向 张三 转账", "¥32.00", "转账")
        );
        assertTrue("张三".equals(pending), "pre-payment page should yield merchant 张三, got=" + pending);

        // 结果页：只有「支付成功 + 金额」，没有商户；传入暂存名后应补上「张三」。
        PaymentPageMatcher.Result result = PaymentPageMatcher.inspect(
                "com.tencent.mm", "支付成功", Arrays.asList("支付成功", "¥32.00"), "张三"
        );
        checks += 1;
        if (!result.shouldCapture) {
            throw new AssertionError("result page should capture, reason=" + result.reason);
        }
        if (!"张三".equals(result.merchant)) {
            throw new AssertionError("result page merchant should fallback to 张三, got=" + result.merchant);
        }

        // 确认页有「成功」字样时，不当作确认页（避免把结果页误当确认页抽错）。
        String shouldBeEmpty = PaymentPageMatcher.detectPrePaymentMerchant(
                "向 张三 转账成功 ¥32.00",
                Arrays.asList("向 张三 转账成功", "¥32.00")
        );
        assertTrue("".equals(shouldBeEmpty), "success page must not be treated as pre-payment, got=" + shouldBeEmpty);
    }

    private static void assertCrossChannelDedup() {
        LocalLedger ledger = new LocalLedger(20);
        LedgerEntry fromNotification = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付收款助手 你已向 美团外卖 支付 ¥46.80 支付方式 零钱",
                1_000_000L
        );
        assertTrue(ledger.add(fromNotification), "notification entry should be stored");

        assertTrue(ledger.hasSimilar(4680L, "expense", "美团外卖", 1_030_000L, 120_000L, "accessibility"),
                "same payment seen by the other channel should be detected within window");
        // 行为变更（用户实测）：同一笔支付的结果页停留期间反复触发，2~7 分钟后
        // 又被记一笔。同商户同金额 10 分钟内一律判重，不再依赖通道/时间窗。
        assertTrue(ledger.hasSimilar(4680L, "expense", "美团外卖", 1_400_000L, 120_000L, "accessibility"),
                "same merchant + same amount within 10 minutes is the same payment re-captured");
        assertFalse(ledger.hasSimilar(4680L, "expense", "美团外卖", 1_700_000L, 120_000L, "accessibility"),
                "same merchant beyond 10 minutes can be a real second purchase");
        assertFalse(ledger.hasSimilar(5000L, "expense", "美团外卖", 1_030_000L, 120_000L, "accessibility"),
                "different amount is not a duplicate");
        assertFalse(ledger.hasSimilar(4680L, "income", "美团外卖", 1_030_000L, 120_000L, "accessibility"),
                "different direction is not a duplicate");
        // 同通道同商户同金额 10 分钟内同样判重：无障碍通道的支付页反复触发
        // 正是用户实测 17:46/47/48 三笔 ¥14 的来源，不能因为「同通道」就放行。
        assertTrue(ledger.hasSimilar(4680L, "expense", "美团外卖", 1_060_000L, 120_000L, "notification"),
                "same-channel same-merchant re-capture must be deduped too");

        LocalLedger unknownMerchant = new LocalLedger(20);
        LedgerEntry vague = TransactionParser.parse(
                "com.unionpay",
                "云闪付 支付成功 您向 永辉超市 支付 ¥86.50",
                2_000_000L
        );
        assertTrue(unknownMerchant.add(vague), "vague entry should be stored");
        assertTrue(unknownMerchant.hasSimilar(8650L, "expense", "永辉超市", 2_020_000L, 120_000L, "accessibility"),
                "page entry can match an existing capture by amount and time");

        // 用户报告的场景：转出 0.01 被记成收入后，再转同一金额应当仍可入账。
        LocalLedger sequential = new LocalLedger(20);
        LedgerEntry transferOut = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 转账成功 ¥0.01 张三 对方已收款",
                3_000_000L
        );
        assertEquals("expense", transferOut.direction, "transfer out must be expense");
        assertTrue(sequential.add(transferOut), "first transfer should be stored");
        LedgerEntry secondTransfer = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 转账成功 ¥0.01 张三 对方已收款",
                3_600_000L
        );
        assertTrue(sequential.add(secondTransfer),
                "a later identical transfer (different time) should still be stored");
        assertEquals(2, sequential.size(), "both transfers should be kept");
    }

    private static void accepts(
            String name,
            String packageName,
            String title,
            List<String> nodes,
            Long expectedAmount,
            String expectedMerchant,
            String expectedDirection
    ) {
        PaymentPageMatcher.Result result = PaymentPageMatcher.inspect(packageName, title, nodes);
        checks += 1;
        if (!result.shouldCapture) {
            throw new AssertionError(name + " should be accepted, reason=" + result.reason);
        }
        if (expectedAmount != null && result.amountCents != expectedAmount) {
            throw new AssertionError(name + " amount expected " + expectedAmount + " but got " + result.amountCents);
        }
        if (expectedMerchant != null && !expectedMerchant.equals(result.merchant)) {
            throw new AssertionError(name + " merchant expected " + expectedMerchant + " but got " + result.merchant);
        }
        if (!expectedDirection.equals(result.direction)) {
            throw new AssertionError(name + " direction expected " + expectedDirection + " but got " + result.direction);
        }
    }

    private static void rejects(String name, String packageName, String title, List<String> nodes, String expectedReason) {
        PaymentPageMatcher.Result result = PaymentPageMatcher.inspect(packageName, title, nodes);
        checks += 1;
        if (result.shouldCapture) {
            throw new AssertionError(name + " should be rejected but was accepted");
        }
        if (!expectedReason.equals(result.reason)) {
            throw new AssertionError(name + " reason expected " + expectedReason + " but got " + result.reason);
        }
    }

    private static void assertTrue(boolean value, String message) {
        checks += 1;
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        checks += 1;
        if (value) throw new AssertionError(message);
    }

    private static void assertEquals(int expected, int actual, String message) {
        checks += 1;
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        checks += 1;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
