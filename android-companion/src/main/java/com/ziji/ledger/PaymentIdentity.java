package com.ziji.ledger;

/**
 * 「这两条记录是不是同一笔钱」的唯一判定口径。
 *
 * 以前这段判断散在两处：通知通道和页面通道各写一遍 LocalLedger.hasSimilar，
 * 参数还不一样；而真正落盘的 LedgerRepository 只看「指纹 + 2 分钟」。
 * 结果是三套规则互相打架——同一笔支付在页面上记一次（带商户名）、
 * 通知里再记一次（未知商户），两边都认为自己是「新的一笔」。
 *
 * 放进一个纯函数类之后，判重口径只有一份，测试也只测这一份。
 *
 * 判定依据不是「指纹」：两条通道拿到的原文本来就不同，指纹必然不同。
 * 自动合并还要求有证据表明来自不同采集通道；同通道的独立事件不能只因文案/金额相同就被吞掉。
 */
final class PaymentIdentity {

    /**
     * 同一瞬间的窗口。
     *
     * 跨通道采集对同一次付款的时间差。该窗口不适用于同通道事件；
     * 后者依靠事件 ID 或页面短期签名去重。
     */
    static final long SAME_INSTANT_WINDOW_MILLIS = 30 * 1000L;

    /** 不同采集通道对同一付款的短暂时间差；更长时间内的同店复购必须保留。 */
    static final long SAME_MERCHANT_WINDOW_MILLIS = 45 * 1000L;

    /** 这些词出现在「商户」位置上，说明解析抓到了界面元素，不是店名。 */
    private static final String[] MERCHANT_NOISE = {
            "搜索", "全部", "账单", "明细", "详情", "记录", "首页", "消息", "我的", "设置",
            "返回", "完成", "确定", "取消", "关闭", "查看", "更多", "筛选", "统计", "分类",
            "微信支付", "支付宝", "支付成功", "付款成功", "交易成功", "收款", "付款", "转账",
            "订单", "余额", "零钱", "花呗", "银行卡", "信用卡", "未知商户", "未知", "其他", "未分类"
    };

    private PaymentIdentity() {
    }

    /**
     * 判断两条记录是不是同一笔支付。
     *
     * 规则按证据强度排序，先强后弱：
     * 1. 来源确实跨通道；
     * 2. 时间差不超过 30 秒，或商户明确相同且时间差不超过 45 秒；
     * 商户缺失时不把「同一分钟」当作重复证据；这类交易宁可保留并提示复核。
     */
    static boolean isSamePayment(
            long amountCentsA,
            String directionA,
            String merchantA,
            long occurredAtA,
            String sourceTypeA,
            String sourcePackageA,
            long amountCentsB,
            String directionB,
            String merchantB,
            long occurredAtB,
            String sourceTypeB,
            String sourcePackageB
    ) {
        if (amountCentsA <= 0L || amountCentsA != amountCentsB) return false;
        if (!safe(directionA).equals(safe(directionB))) return false;

        long gap = Math.abs(occurredAtA - occurredAtB);
        boolean reliableA = isReliableMerchant(merchantA);
        boolean reliableB = isReliableMerchant(merchantB);

        if (reliableA && reliableB) {
            // 两个明确的店名对不上，哪怕同一秒也是两笔不同的消费。
            if (!sameMerchant(merchantA, merchantB)) return false;
            if (!isCrossChannel(sourceTypeA, sourcePackageA, sourceTypeB, sourcePackageB)) return false;
            if (gap <= SAME_INSTANT_WINDOW_MILLIS) return true;
            return gap <= SAME_MERCHANT_WINDOW_MILLIS;
        }

        // 商户缺失时只在明确跨通道、且时间几乎同时的情况下合并。
        return gap <= SAME_INSTANT_WINDOW_MILLIS
                && isCrossChannel(sourceTypeA, sourcePackageA, sourceTypeB, sourcePackageB);
    }

    static boolean isSameCaptureSource(String sourceTypeA, String sourcePackageA, String sourceTypeB, String sourcePackageB) {
        String typeA = safe(sourceTypeA).trim();
        String typeB = safe(sourceTypeB).trim();
        String packageA = safe(sourcePackageA).trim();
        String packageB = safe(sourcePackageB).trim();
        return !typeA.isEmpty() && typeA.equalsIgnoreCase(typeB)
                && !packageA.isEmpty() && packageA.equalsIgnoreCase(packageB);
    }

    /**
     * 两条记录是否来自不同的采集通道。
     *
     * 通道类型不同（通知 / 无障碍）或者来源 App 不同（微信通知 / 银行短信），
     * 都算两条独立证据。来源字段不足以证明不同通道时，不自动合并，避免误吞新交易。
     */
    static boolean isCrossChannel(String sourceTypeA, String sourcePackageA, String sourceTypeB, String sourcePackageB) {
        String typeA = safe(sourceTypeA).trim();
        String typeB = safe(sourceTypeB).trim();
        if (!typeA.isEmpty() && !typeB.isEmpty() && !typeA.equalsIgnoreCase(typeB)) return true;
        String packageA = safe(sourcePackageA).trim().toLowerCase();
        String packageB = safe(sourcePackageB).trim().toLowerCase();
        if (!packageA.isEmpty() && !packageB.isEmpty() && !packageA.equals(packageB)) return true;
        return false;
    }

    /** 商户名是否可信到可以作为「这是另一家店」的依据。 */
    static boolean isReliableMerchant(String merchant) {
        String value = safe(merchant).trim();
        if (value.length() < 2) return false;
        String lower = value.toLowerCase();
        for (String noise : MERCHANT_NOISE) {
            if (lower.equals(noise.toLowerCase())) return false;
        }
        // 「搜索包子」这种拼起来的词也不可信：解析把两个界面节点连成了一串。
        return !lower.contains("搜索") && !lower.contains("支付成功") && !lower.contains("付款成功");
    }

    /** 同一家店的两种写法：「红姐包子」与「红姐包子-软景店」应当视为同一家。 */
    static boolean sameMerchant(String merchantA, String merchantB) {
        String keyA = merchantKey(merchantA);
        String keyB = merchantKey(merchantB);
        if (keyA.isEmpty() || keyB.isEmpty()) return false;
        if (keyA.equals(keyB)) return true;
        String shorter = keyA.length() <= keyB.length() ? keyA : keyB;
        String longer = shorter == keyA ? keyB : keyA;
        return shorter.length() >= 2 && longer.contains(shorter);
    }

    /**
     * 商户归一化：去掉门店/公司后缀和括号限定。
     *
     * 直接复用解析器的实现，避免「判重用的名字」和「学习分类用的名字」是两套。
     */
    static String merchantKey(String merchant) {
        return TransactionParser.merchantKey(safe(merchant));
    }

    /** 两条记录是否落在同一分钟（按本地时间，解析精度只到分钟）。 */
    static boolean sameMinute(long millisA, long millisB) {
        return millisA / 60_000L == millisB / 60_000L;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
