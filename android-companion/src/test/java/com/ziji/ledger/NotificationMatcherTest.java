package com.ziji.ledger;

public final class NotificationMatcherTest {
    public static void main(String[] args) {
        accepts("alipay expense",
                "com.eg.android.AlipayGphone",
                "支付宝",
                "06月06日 12:31 向 星巴克 付款 32.00元 付款方式 花呗");
        accepts("wechat pay expense",
                "com.tencent.mm",
                "微信支付收款助手",
                "你已向 美团外卖 支付 ¥46.80，支付方式 零钱");
        accepts("bank card expense",
                "com.cmbchina.ccd",
                "招商银行",
                "您尾号 8899 的信用卡 06月06日 20:17 在 盒马鲜生 消费人民币 128.60 元。");
        accepts("refund",
                "com.tencent.mm",
                "微信支付",
                "退款到账 19.90元，商户 腾讯视频，原交易已退回零钱。");
        accepts("alipay merchant label payment",
                "com.eg.android.AlipayGphone",
                "支付宝",
                "付款成功 付款金额 ￥12.34 商家：瑞幸咖啡 付款方式 花呗");
        accepts("wechat paid-to compact format",
                "com.tencent.mm",
                "微信支付凭证",
                "你已支付给瑞幸咖啡¥18.00 支付方式 零钱");
        accepts("icbc quick pay",
                "com.icbc",
                "工商银行",
                "您尾号1234卡07月07日10:20快捷支付支出(美团)交易金额人民币56.70元，余额888.00元。");
        accepts("ccb merchant pay",
                "com.chinamworld.main",
                "建设银行",
                "您尾号6789账户07月07日11:05向 沃尔玛超市 支付人民币199.90元，当前余额1000.00元。");
        accepts("bank sms with tenpay merchant",
                "com.google.android.apps.messaging",
                "95533",
                "【建设银行】您尾号1234账户07月07日12:30向 财付通支付科技有限公司 支付人民币45.60元，当前余额1000.00元。");
        accepts("bank sms with alipay quick pay",
                "com.google.android.apps.messaging",
                "95555",
                "【招商银行】您尾号8899信用卡07月07日13:20支付宝快捷支付消费人民币88.80元，商户名称淘宝，余额1000.00元。");
        accepts("bank sms from messages app",
                "com.google.android.apps.messaging",
                "95599",
                "【农业银行】您尾号5678账户07月07日13:20完成快捷支付支出人民币15.00元，收款方：便利蜂，余额1000.00元。");
        accepts("bank sms with thousands amount",
                "com.cmbchina.ccd",
                "招商银行",
                "您尾号8899信用卡07月07日在山姆会员店消费人民币1,234.56元，余额12,345.67元。");
        accepts("unionpay cloud quickpass expense",
                "com.unionpay",
                "云闪付",
                "支付成功：您向 永辉超市 支付 ¥86.50");
        accepts("alipay refund compact",
                "com.eg.android.AlipayGphone",
                "支付宝",
                "退款成功 商户：瑞幸咖啡 金额18.00元 已退回余额");
        accepts("wechat transfer out",
                "com.tencent.mm",
                "微信支付",
                "转账给 李四 66.00元 已到账");

        rejects("plain chat",
                "com.tencent.mm",
                "张三",
                "晚上一起吃饭吗");
        rejects("wechat chat with payment words",
                "com.tencent.mm",
                "张三",
                "我刚向 星巴克 支付 32.00元，晚点发你截图");
        rejects("verification code",
                "com.eg.android.AlipayGphone",
                "支付宝",
                "验证码 123456，请勿告诉他人");
        rejects("bank sms verification code",
                "com.google.android.apps.messaging",
                "95555",
                "【招商银行】验证码 123456，用于快捷支付，金额人民币15.00元，切勿告知他人。");
        rejects("unknown app balance payment words",
                "com.example.game",
                "游戏中心",
                "余额支付 9.90元成功，点此查看详情");
        rejects("unknown app rmb payment words",
                "com.example.game",
                "游戏中心",
                "支付人民币9.90元成功，点此查看详情");
        rejects("unknown package containing bank",
                "com.example.bankgame",
                "游戏中心",
                "支付9.90元成功，点此查看详情");
        rejects("payment code",
                "com.tencent.mm",
                "微信支付",
                "付款码已开启，请注意资金安全");
        rejects("coupon",
                "com.eg.android.AlipayGphone",
                "支付宝",
                "你有 1 张优惠券即将过期");
        rejects("balance only",
                "com.cmbchina.ccd",
                "招商银行",
                "您尾号 8899 当前账户余额 1000.00 元。");
        rejects("credit card repayment",
                "com.cmbchina.ccd",
                "招商银行",
                "您尾号8899信用卡自动还款扣款人民币1200.00元，还款成功。");
        rejects("bill reminder",
                "com.eg.android.AlipayGphone",
                "支付宝",
                "本月花呗账单 1200.00 元，请按时还款。");
        rejects("wechat red packet reminder",
                "com.tencent.mm",
                "微信红包",
                "你收到一个红包，点击领取 8.88元");
        rejects("alipay balance change only",
                "com.eg.android.AlipayGphone",
                "支付宝",
                "余额变动提醒：余额宝昨日收益 0.12 元，当前余额 1000.12 元。");
        rejects("unionpay login risk",
                "com.unionpay",
                "云闪付",
                "登录提醒：您正在登录云闪付，验证码 123456。");
        // 微信通知的发送方写在标题里：好友聊天正文提到「微信支付」也不能冒充支付通知。
        rejects("wechat chat mentioning wechat pay",
                "com.tencent.mm",
                "张三",
                "我用微信支付转给你 32.00元 了，查收一下");
        // 花呗/信用卡类还款是消费时已记过账的二次扣款，不是新支出。
        rejects("huabei repayment success",
                "com.eg.android.alipaygphone",
                "支付宝",
                "花呗还款成功，已还款 1200.00 元。");
        // 余额宝/零钱通自动转入是自己账户内部的搬运，不是真实收入。
        rejects("yuebao auto sweep",
                "com.eg.android.alipaygphone",
                "支付宝",
                "零钱自动转入余额宝 500.00 元，工资理财计划执行成功。");

        assertEquals("log missing amount", true, NotificationMatcher.shouldLogRejection("missing_amount"));
        assertEquals("log missing payment action", true, NotificationMatcher.shouldLogRejection("missing_payment_action"));
        assertEquals("do not log unknown source", false, NotificationMatcher.shouldLogRejection("unknown_source"));
        assertEquals("do not log excluded", false, NotificationMatcher.shouldLogRejection("excluded"));

        System.out.println("android matcher tests passed: 34");
    }

    private static void accepts(String name, String pkg, String title, String text) {
        NotificationMatcher.MatchResult result = NotificationMatcher.inspect(pkg, title, text);
        if (!result.shouldCapture()) {
            throw new AssertionError(name + " should be accepted, reason=" + result.reason());
        }
    }

    private static void rejects(String name, String pkg, String title, String text) {
        NotificationMatcher.MatchResult result = NotificationMatcher.inspect(pkg, title, text);
        if (result.shouldCapture()) {
            throw new AssertionError(name + " should be rejected");
        }
    }

    private static void assertEquals(String name, boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
