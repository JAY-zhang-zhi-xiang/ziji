package com.ziji.ledger;

/**
 * 「同笔判定」的口径测试。
 *
 * 这个类存在的理由：重复记账反复修不好，根因是判定规则散落在两条通道里、
 * 参数还互相矛盾（调用方给 2 分钟、内部砍成 30 秒）。规则收拢成一个纯函数之后，
 * 用真实踩过的场景把边界钉死，以后谁改都得先过这里。
 */
public final class PaymentIdentityTest {
    private static int checks = 0;

    public static void main(String[] args) {
        crossChannelSameMinuteIsSamePayment();
        differentReliableMerchantsStaySeparate();
        sameChannelPaymentsStaySeparate();
        minuteApartWithUnknownMerchantStaysSeparate();
        sameMerchantWithinTenMinutesIsSamePayment();
        onlyCrossChannelNearMatchesUseMerchantTolerance();
        differentAmountOrDirectionIsNeverSamePayment();
        merchantNoiseIsNotReliable();
        merchantVariantsAreSameStore();
        System.out.println("payment identity tests passed: " + checks);
    }

    /**
     * 用户截图里的那一笔：¥55 在 14:58 被记了两条。
     * 一条带商户名（页面通道），一条「未知商户」（通知通道），两条时间同为 14:58。
     */
    private static void crossChannelSameMinuteIsSamePayment() {
        long at = 1_760_000_000_000L;
        assertTrue(PaymentIdentity.isSamePayment(
                5500L, "expense", "大连好利来", at, "accessibility", "com.tencent.mm",
                5500L, "expense", "未知商户", at, "notification", "com.tencent.mm"
        ), "同一分钟、同金额、一条商户未知，是同一笔");
    }

    /** 两家都识别出名字的店，哪怕同一分钟也是两笔。 */
    private static void differentReliableMerchantsStaySeparate() {
        long at = 1_760_000_000_000L;
        assertFalse(PaymentIdentity.isSamePayment(
                1400L, "expense", "红姐包子", at, "accessibility", "com.tencent.mm",
                1400L, "expense", "瑞幸咖啡", at + 5_000L, "notification", "com.tencent.mm"
        ), "两个明确的商户名对不上，不能合并");
    }

    /** 同一通道的重复触发由稳定事件 ID / 页面冷却处理，金额相同不应吞掉真实新事件。 */
    private static void sameChannelPaymentsStaySeparate() {
        long at = 1_760_000_000_000L;
        assertFalse(PaymentIdentity.isSamePayment(
                1400L, "expense", "红姐包子", at, "accessibility", "com.tencent.mm",
                1400L, "expense", "未知商户", at + 12_000L, "accessibility", "com.tencent.mm"
        ), "同通道的不同事件不因金额相同而静默合并");
    }

    /**
     * 一分钟后的同金额消费不能被吞掉。
     *
     * 这是之前那版 30 秒窗口想保住的场景；放宽到 45 秒之后仍然要保住：
     * 用户先买一杯 8 元的奶茶（通知没带商户），一分钟后在另一家买第二杯。
     */
    private static void minuteApartWithUnknownMerchantStaysSeparate() {
        long at = 1_760_000_000_000L;
        assertFalse(PaymentIdentity.isSamePayment(
                800L, "expense", "未知商户", at, "notification", "com.tencent.mm",
                800L, "expense", "蜜雪冰城", at + 60_000L, "accessibility", "com.tencent.mm"
        ), "隔一分钟的同金额消费是两笔");
        // 但只差 20 秒（页面时间只到分钟，最多差 59 秒）就要判成同一笔。
        assertTrue(PaymentIdentity.isSamePayment(
                800L, "expense", "未知商户", at, "notification", "com.tencent.mm",
                800L, "expense", "蜜雪冰城", at + 20_000L, "accessibility", "com.tencent.mm"
        ), "相差 20 秒的同金额跨通道记录是同一笔");
    }

    /** 商户一致时只使用短冷却；同店短时复购不能被十分钟窗口吞掉。 */
    private static void sameMerchantWithinTenMinutesIsSamePayment() {
        long at = 1_760_000_000_000L;
        assertFalse(PaymentIdentity.isSamePayment(
                1400L, "expense", "红姐包子", at, "accessibility", "com.tencent.mm",
                1400L, "expense", "红姐包子", at + 6 * 60_000L, "accessibility", "com.tencent.mm"
        ), "同商户 6 分钟后可能是复购，不可静默合并");
        assertFalse(PaymentIdentity.isSamePayment(
                1400L, "expense", "红姐包子", at, "accessibility", "com.tencent.mm",
                1400L, "expense", "红姐包子", at + 15 * 60_000L, "accessibility", "com.tencent.mm"
        ), "超过 10 分钟的同商户同金额可能是真实第二笔");
    }

    private static void onlyCrossChannelNearMatchesUseMerchantTolerance() {
        long at = 1_760_000_000_000L;
        assertTrue(PaymentIdentity.isSamePayment(
                1400L, "expense", "红姐包子", at, "notification", "com.tencent.mm",
                1400L, "expense", "红姐包子", at + 35_000L, "accessibility", "com.tencent.mm"
        ), "同商户的跨通道即时记录可去重");
        assertFalse(PaymentIdentity.isSamePayment(
                1400L, "expense", "红姐包子", at, "accessibility", "com.tencent.mm",
                1400L, "expense", "红姐包子", at + 35_000L, "accessibility", "com.tencent.mm"
        ), "同通道超过 30 秒不作自动合并");
        assertFalse(PaymentIdentity.isSamePayment(
                1400L, "expense", "未知商户", at, "notification", "com.tencent.mm",
                1400L, "expense", "瑞幸咖啡", at + 35_000L, "accessibility", "com.tencent.mm"
        ), "商户不完整且超过强证据时间窗时保留两笔");
    }

    private static void differentAmountOrDirectionIsNeverSamePayment() {
        long at = 1_760_000_000_000L;
        assertFalse(PaymentIdentity.isSamePayment(
                5500L, "expense", "未知商户", at, "notification", "com.tencent.mm",
                5600L, "expense", "未知商户", at, "accessibility", "com.tencent.mm"
        ), "金额不同一定不是同一笔");
        assertFalse(PaymentIdentity.isSamePayment(
                5500L, "expense", "未知商户", at, "notification", "com.tencent.mm",
                5500L, "income", "未知商户", at, "accessibility", "com.tencent.mm"
        ), "方向不同一定不是同一笔");
    }

    /** 界面词不能当成「另一家店」的证据。 */
    private static void merchantNoiseIsNotReliable() {
        assertFalse(PaymentIdentity.isReliableMerchant("搜索"), "「搜索」是界面元素，不是商户");
        assertFalse(PaymentIdentity.isReliableMerchant("未知商户"), "占位商户名不可信");
        assertFalse(PaymentIdentity.isReliableMerchant(""), "空商户不可信");
        assertTrue(PaymentIdentity.isReliableMerchant("大连好利来"), "真实商户名可信");

        // 商户一边是噪声时，同分钟的同金额记录必须合并——旧实现会因为
        // 「双方都非空但不同」直接放行，这正是 ¥14 那条重复的来源。
        long at = 1_760_000_000_000L;
        assertTrue(PaymentIdentity.isSamePayment(
                1400L, "expense", "搜索", at, "notification", "com.tencent.mm",
                1400L, "expense", "红姐包子-软景店", at + 3_000L, "accessibility", "com.tencent.mm"
        ), "「搜索」不是商户，不能拿它当两笔的依据");
    }

    /** 同一家店的两种写法要能认出来。 */
    private static void merchantVariantsAreSameStore() {
        assertTrue(PaymentIdentity.sameMerchant("红姐包子", "红姐包子-软景店"), "带门店后缀是同一家");
        assertTrue(PaymentIdentity.sameMerchant("瑞幸咖啡", "瑞幸咖啡(国贸店)"), "带括号门店是同一家");
        assertFalse(PaymentIdentity.sameMerchant("瑞幸咖啡", "星巴克"), "不同品牌不是同一家");
    }

    private static void assertTrue(boolean value, String message) {
        checks += 1;
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        checks += 1;
        if (value) throw new AssertionError(message);
    }
}
