package com.ziji.ledger;

import java.util.Locale;
import java.util.regex.Pattern;

public final class NotificationMatcher {
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:人民币|RMB|CNY|¥|￥)\\s*(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?\\s*(?:元|块)?|(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?\\s*(?:元|块)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXPENSE_PATTERN = Pattern.compile(
            "付款|支付(?!宝)|消费|扣款|支出|交易成功|已向|已转账给|转账给|向.{1,24}(?:付款|支付|转账)|在.{1,24}(?:消费|付款|支付|扣款)"
    );
    private static final Pattern INCOME_OR_REFUND_PATTERN = Pattern.compile("退款|退回|收入到账|转入|入账|红包到账|收到红包");
    private static final Pattern SOURCE_PATTERN = Pattern.compile(
            "微信支付|支付宝|花呗|银行|信用卡|储蓄卡|银行卡|尾号|云闪付|银联|UnionPay|招商银行|工商银行|农业银行|建设银行|中国银行|交通银行|浦发|中信|广发|平安",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern WECHAT_PAYMENT_SOURCE_PATTERN = Pattern.compile("微信支付|支付收款助手|微信支付收款助手");
    private static final Pattern EXCLUDE_PATTERN = Pattern.compile(
            "验证码|校验码|动态码|登录|付款码|收款码|优惠券|红包提醒|活动|广告|订阅|账单日|还款提醒|信用卡.{0,16}还款|还款.{0,16}信用卡"
                    // 信用卡/花呗/白条等授信还款是「消费时已记过账」的二次扣款，
                    // 不是新支出；记进去等于同一笔消费算两次。
                    + "|还款成功|已还款|还款完成|自动还款|(?:花呗|白条|借呗|贷款).{0,16}还款|还款.{0,16}(?:花呗|白条|借呗|贷款)"
                    // 理财/自动转入是自己账户内部的搬运（零钱→余额宝、工资→理财），
                    // 不是真实收入；「赎回/申购/定投」同理。
                    + "|自动转入|零钱通|工资理财|申购成功|赎回|定投"
    );

    private NotificationMatcher() {
    }

    public static MatchResult inspect(String packageName, String title, String text) {
        String normalized = normalize(title, text);
        if (isBlank(normalized)) {
            return MatchResult.reject(normalized, "empty");
        }
        if (EXCLUDE_PATTERN.matcher(normalized).find()) {
            return MatchResult.reject(normalized, "excluded");
        }
        if (!looksLikePaymentSource(packageName, title, normalized)) {
            return MatchResult.reject(normalized, "unknown_source");
        }
        if (!AMOUNT_PATTERN.matcher(normalized).find()) {
            return MatchResult.reject(normalized, "missing_amount");
        }
        if (EXPENSE_PATTERN.matcher(normalized).find() || INCOME_OR_REFUND_PATTERN.matcher(normalized).find()) {
            return MatchResult.accept(normalized);
        }
        return MatchResult.reject(normalized, "missing_payment_action");
    }

    public static boolean shouldCapture(String packageName, String title, String text) {
        return inspect(packageName, title, text).shouldCapture();
    }

    public static boolean shouldLogRejection(String reason) {
        return "missing_amount".equals(reason) || "missing_payment_action".equals(reason);
    }

    private static boolean looksLikePaymentSource(String packageName, String title, String normalized) {
        String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        if (pkg.equals("com.tencent.mm")) {
            // 微信的通知标题就是发送方：「微信支付」的服务通知标题固定含「微信支付」，
            // 而好友聊天通知的标题是联系人名。以前拿「标题+正文」拼接判定，
            // 聊天正文里出现「微信支付」四个字（如「我用微信支付转你了32元」）
            // 就会冒充支付通知——所以这里必须只看标题。
            return WECHAT_PAYMENT_SOURCE_PATTERN.matcher(safe(title)).find();
        }
        return pkg.equals("com.eg.android.alipaygphone")
                || SOURCE_PATTERN.matcher(normalized).find();
    }

    private static String normalize(String title, String text) {
        String joined = String.join(" ", safe(title), safe(text));
        return joined.replaceAll("\\s+", " ").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class MatchResult {
        private final boolean shouldCapture;
        private final String normalizedText;
        private final String reason;

        private MatchResult(boolean shouldCapture, String normalizedText, String reason) {
            this.shouldCapture = shouldCapture;
            this.normalizedText = normalizedText;
            this.reason = reason;
        }

        static MatchResult accept(String normalizedText) {
            return new MatchResult(true, normalizedText, "matched");
        }

        static MatchResult reject(String normalizedText, String reason) {
            return new MatchResult(false, normalizedText, reason);
        }

        public boolean shouldCapture() {
            return shouldCapture;
        }

        public String normalizedText() {
            return normalizedText;
        }

        public String reason() {
            return reason;
        }
    }
}
