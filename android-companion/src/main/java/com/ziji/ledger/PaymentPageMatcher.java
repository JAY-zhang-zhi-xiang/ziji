package com.ziji.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 判断当前前台页面是不是「支付完成页」，并从中提取金额、商户和方向。
 *
 * 与 NotificationMatcher 的区别：
 * - NotificationMatcher 看的是对方 App 有没有主动发通知（被动、覆盖率低）。
 * - PaymentPageMatcher 看的是支付成功页本身（主动、只要页面出现就能识别）。
 *
 * 判据必须严格：只有同时满足「来源可信 + 出现成功字样 + 有金额」才采集，
 * 并显式排除「确认付款」「请输入支付密码」「支付失败」这类页面，避免误记。
 */
public final class PaymentPageMatcher {
    /** 支付完成的正向信号。 */
    private static final Pattern SUCCESS_PATTERN = Pattern.compile(
            "支付成功|付款成功|交易成功|支付完成|付款完成|交易完成|扣款成功|消费成功"
                    + "|收款成功|收款到账|退款成功|退款到账|转入成功"
                    + "|转账成功|转账完成|已转账给|已成功转账"
                    + "|等待对方确认收款|对方已收款|对方已确认收款|对方已领取"
    );
    // 注意：「已支付 / 已付款」刻意不在这份名单里——它们是订单状态页的措辞
    // （「已支付 ¥14.00 商家已接单」），不是支付结果页的措辞。真实结果页一定有
    // 「支付成功 / 交易成功」。把状态词当成功信号，用户实测记出了
    // 「星标 ¥14.00」这种同一笔订单反复入账的幽灵账单。

    /** 尚未完成的页面信号，命中即拒绝。 */
    private static final Pattern PENDING_PATTERN = Pattern.compile(
            "确认付款|确认支付|立即支付|立即付款|请输入支付密码|输入支付密码|请输入密码|请付款"
                    + "|待支付|待付款|付款码|收款码|向商家付款|支付中|处理中"
    );

    /** 明确失败的页面信号，命中即拒绝（避免把失败当成功）。 */
    private static final Pattern FAILURE_PATTERN = Pattern.compile(
            "支付失败|付款失败|交易失败|支付未完成|付款超时|余额不足|已取消|订单已关闭|支付异常"
    );

    /** 收入方向信号（与 TransactionParser 的判定保持一致，统一走 detectDirection）。 */
    private static final Pattern INCOME_PATTERN = Pattern.compile(
            "收款|退款|退回|转入|到账|收到红包|红包到账"
    );

    /**
     * 可信来源：包名或页面标题。
     *
     * 只保留支付渠道和银行。电商 App（美团/京东/淘宝…）曾经也在名单里，
     * 但无障碍服务只监听微信/支付宝/银行（见 accessibility_service_config 的
     * packageNames），这些词对白名单通道毫无增益，反而让「跑在微信进程里的
     * 美团小程序订单页」多了「交易成功 + 金额 + 美团」三连命中，被当成支付页
     * 记了一笔根本没有发生的账。
     */
    private static final Pattern SOURCE_PATTERN = Pattern.compile(
            "微信支付|微信|支付宝|花呗|余额宝|云闪付|银联|UnionPay"
                    + "|银行|信用卡|储蓄卡|银行卡|招商|工商|农业|建设|中国银行|交通|浦发|中信|广发|平安",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 微信聊天/主界面窗口。转账气泡、历史消息都出现在这里：
     * 气泡自带「¥32.00」「对方已收款」，页面签名去重只有 10 秒，
     * 几小时后再点开同一个聊天，就会再记一笔根本没有发生的账。
     * 支付完成页、转账结果页都不在这些窗口里，可以放心拒绝。
     */
    private static final Pattern WECHAT_CHAT_WINDOW = Pattern.compile(
            "^com\\.tencent\\.mm\\.ui\\.LauncherUI|^com\\.tencent\\.mm\\.ui\\.chatting\\."
    );

    /** 聊天输入栏的特征词：微信聊天页底部常驻「按住说话」，支付结果页没有。 */
    private static final Pattern CHAT_CONTENT_MARKER = Pattern.compile("按住说话|切换到键盘");

    /**
     * 订单列表/订单详情页的特征词。这些页面充满历史订单的「交易成功 + 金额」，
     * 是仅次于聊天页的误记账来源；而真正的支付结果页不会出现「待收货/查看物流」
     * 这类履约状态按钮。履约中的状态页（商家已接单/配送中/骑手）同样要拦：
     * 它们会以「已支付 + 金额」的形态在订单存续期间反复出现。
     */
    private static final Pattern ORDER_LIST_PATTERN = Pattern.compile(
            "待付款|待发货|待收货|查看物流|申请售后|评价晒单|再来一单|延长收货"
                    + "|商家已接单|已接单|待接单|配送中|骑手|催单|联系商家|联系骑手"
                    + "|收货地址|去评价|复制订单号|订单编号|发票|留言|复购"
                    // 淘宝/京东系订单页特有的履约状态词。
                    + "|确认收货|等待发货|已发货|申请退款|退款详情|物流信息"
    );

    /** 带标签的商户字段，优先级最高。标签后必须紧跟分隔符，避免「对方已收款」被当成商户。 */
    private static final Pattern[] MERCHANT_PATTERNS = new Pattern[] {
            // 「商品说明」不在这里：它是用途的标签，取到的值是「生椰拿铁」而不是店名。
            // 「付款方」排除「付款方式」，避免标签吃掉三个字后把「式 花呗」当成商户。
            Pattern.compile("(?:商户名称|交易商户|商户(?!单号)|商家|收款方|付款方(?!式)|交易对方)[:：]\\s*([^\\s,;，。；]{2,24})"),
            Pattern.compile("(?:支付给|付款给|转账给)\\s*([^\\s,;，。；¥￥]{2,24}?)\\s*(?:¥|￥|人民币|RMB|CNY|\\d|$)"),
            Pattern.compile("向\\s*([^\\s,;，。；]{2,24}?)\\s*(?:支付|付款|转账)"),
            Pattern.compile("在\\s*([^\\s,;，。；]{2,24}?)\\s*(?:消费|付款|支付)"),
            Pattern.compile("(?:来自|由)\\s*([^\\s,;，。；]{2,24}?)\\s*(?:的)?\\s*(?:转账|付款|收款|退款)"),
    };

    /** 不可能是商户的节点词。 */
    private static final Pattern MERCHANT_BLACKLIST = Pattern.compile(
            "支付|付款|成功|失败|完成|金额|余额|方式|时间|订单|详情|确认|取消|返回|关闭|确定|查看|点击|等待|对方"
                    + "|零钱|花呗|余额宝|银行卡|信用卡|储蓄卡|微信|支付宝|云闪付|银联"
                    + "|收款|转账|退款|红包|优惠|积分|账单|明细|首页|消息|我的|设置"
                    // 界面装饰词/履约状态词：节点扫描兜底会把它们抓成商户名，
                    // 用户账本上出现过「星标」这种店。宁可记「未知商户」也不记垃圾词。
                    + "|星标|留言|复购|咨询|接单|配送|骑手|催单|评价|收货|客服|发票|地址|商家"
    );

    private PaymentPageMatcher() {
    }

    public static Result inspect(String packageName, String title, List<String> nodeTexts) {
        return inspect(packageName, title, nodeTexts, "");
    }

    public static Result inspect(String packageName, String title, List<String> nodeTexts, String fallbackMerchant) {
        List<String> nodes = new ArrayList<>();
        String safeTitle = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (nodeTexts != null) {
            for (String node : nodeTexts) {
                if (node == null) continue;
                String trimmed = node.replaceAll("\\s+", " ").trim();
                // 保序、不去重：用途抽取依赖「标签节点」与「值节点」的相邻关系，
                // 一旦去重，第二个「商品说明」及其值就会被吃掉。
                if (!trimmed.isEmpty()) nodes.add(trimmed);
            }
        }

        List<String> parts = new ArrayList<>();
        if (!safeTitle.isEmpty()) parts.add(safeTitle);
        parts.addAll(nodes);
        String page = String.join(" ", parts);
        if (page.trim().isEmpty()) {
            return Result.reject("", "empty");
        }

        if (!looksLikePaymentSource(packageName, safeTitle, page)) {
            return Result.reject(page, "unknown_source");
        }
        // 聊天页比支付页更像支付页：气泡里什么词都有。必须在成功判定前拦下。
        if (isChatLikePage(packageName, safeTitle, page)) {
            return Result.reject(page, "chat_window");
        }
        if (ORDER_LIST_PATTERN.matcher(page).find()) {
            return Result.reject(page, "order_list");
        }
        if (FAILURE_PATTERN.matcher(page).find()) {
            return Result.reject(page, "not_completed");
        }
        if (PENDING_PATTERN.matcher(page).find()) {
            return Result.reject(page, "not_completed");
        }
        if (!SUCCESS_PATTERN.matcher(page).find()) {
            return Result.reject(page, "missing_success");
        }

        long amountCents = TransactionParser.detectAmountCents(page);
        if (amountCents <= 0L) {
            return Result.reject(page, "missing_amount");
        }

        String direction = TransactionParser.detectDirection(page);
        String merchant = detectMerchant(page, nodes);
        // 结果页拿不到商户时，用付款前确认页暂存下来的名字兜底。
        if ((merchant.isEmpty() || "未知商户".equals(merchant)) && fallbackMerchant != null && !fallbackMerchant.isEmpty()) {
            merchant = fallbackMerchant;
        }
        // 用途直接在这里抽出来，和商户同源同页，不用等用户补。
        PurposeResolver.Result purpose = PurposeResolver.resolve(nodes, page, merchant);
        if (!purpose.isPresent()) {
            purpose = PurposeResolver.fromDictionary(merchant, page);
        }
        return Result.accept(page, amountCents, merchant, direction, purpose);
    }

    /** 付款前确认页的信号：出现这些词，说明正在「向谁付款」，但还没付出去。 */
    private static final Pattern PRE_PAYMENT_PATTERN = Pattern.compile(
            "向\\s*[^\\s,;，。；]{1,24}?\\s*(?:支付|付款|转账)|付款给\\s*[^\\s,;，。；]{1,24}|转账给\\s*[^\\s,;，。；]{1,24}"
                    + "|支付给\\s*[^\\s,;，。；]{1,24}"
    );

    /**
     * 从「付款前确认页」里提取对方/商户名，供结果页兜底。
     *
     * 微信支付的付款结果页通常只有「支付成功 + 金额」，商户名只出现在付款前的确认页
     * （如「向 XX 转账 ¥32.00」「付款给 XX」）。结果页单独看永远抽不出商户，所以这里
     * 把确认页的商户名先捞出来，由无障碍服务暂存，等结果页出现时补上。
     */
    public static String detectPrePaymentMerchant(String page, List<String> nodes) {
        if (page == null || page.trim().isEmpty()) return "";
        if (!PRE_PAYMENT_PATTERN.matcher(page).find()) return "";
        // 确认页不能有「成功」字样，否则已经是结果页，交给正常流程处理。
        if (SUCCESS_PATTERN.matcher(page).find()) return "";

        String merchant = detectMerchant(page, nodes);
        // detectMerchant 在确认页可能抓到「确认付款」这类词，已被黑名单挡住后返回未知；
        // 再用确认页专用规则兜一次，把「向 XX 转账」里的 XX 直接抽出来。
        if (merchant.isEmpty() || "未知商户".equals(merchant)) {
            Matcher m = PRE_PAYMENT_PATTERN.matcher(page);
            if (m.find()) {
                String raw = m.group();
                raw = raw.replaceAll("^向\\s*|\\s*(支付|付款|转账).*$|^(付款给|转账给|支付给)\\s*", "");
                raw = raw.replaceAll("[:：¥￥\\d\\s]", "").trim();
                if (raw.length() >= 2 && raw.length() <= 24) merchant = raw;
            }
        }
        return merchant;
    }

    public static boolean shouldLogRejection(String reason) {
        return "missing_amount".equals(reason) || "missing_success".equals(reason);
    }

    /**
     * 判断这个窗口是不是微信聊天/主界面。
     *
     * 无障碍事件里的「标题」实际是窗口类名（TYPE_WINDOW_STATE_CHANGED 时为
     * Activity 类名）。微信聊天页固定是 LauncherUI，支付完成页/转账结果页是
     * 独立的 plugin Activity，不在其列。窗口内容变化事件拿不到 Activity 类名，
     * 所以再用「按住说话」这类聊天输入栏特征词兜底。
     */
    public static boolean isWeChatChatWindow(String className) {
        return className != null && WECHAT_CHAT_WINDOW.matcher(className.trim()).find();
    }

    private static boolean isChatLikePage(String packageName, String title, String page) {
        if (isWeChatChatWindow(title)) return true;
        if (packageName == null || !packageName.equals("com.tencent.mm")) return false;
        return CHAT_CONTENT_MARKER.matcher(page).find();
    }

    private static boolean looksLikePaymentSource(String packageName, String title, String page) {
        String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        if (pkg.equals("com.tencent.mm")
                || pkg.contains("alipay")
                || pkg.contains("unionpay")
                || pkg.contains("sankuai")
                // 电商/外卖：App 内支付时 SDK 收银台跑在自家进程里（与白名单配套）。
                || pkg.contains("taobao")
                || pkg.contains("tmall")
                || pkg.startsWith("com.jingdong.")
                || pkg.startsWith("com.jd.")
                || pkg.contains("xunmeng")
                || pkg.contains("riming")
                || pkg.contains("ss.android.ugc")
                || pkg.contains("bank")
                || pkg.startsWith("com.icbc")
                || pkg.startsWith("com.ccb")
                || pkg.startsWith("com.abchina")
                || pkg.startsWith("com.chinamworld")
                || pkg.startsWith("com.bankcomm")
                || pkg.startsWith("cmb")
                || pkg.startsWith("com.spdb.")
                || pkg.startsWith("com.ecitic.")
                || pkg.startsWith("com.cgbchina.")
                || pkg.startsWith("com.pingan.paces")
                || pkg.startsWith("com.psbc.")
                || pkg.startsWith("com.cib.")) {
            return true;
        }
        return SOURCE_PATTERN.matcher(title + " " + page).find();
    }

    private static String detectMerchant(String page, List<String> nodes) {
        for (Pattern pattern : MERCHANT_PATTERNS) {
            Matcher matcher = pattern.matcher(page);
            if (matcher.find()) {
                String cleaned = cleanMerchant(matcher.group(1));
                if (!cleaned.isEmpty()) return cleaned;
            }
        }

        // 支付成功页上商户常是独立节点（如「美团外卖」），没有标签。
        // 逐节点筛选：无数字、非关键词、长度合理者视为商户。
        for (int index = 0; index < nodes.size(); index += 1) {
            String node = nodes.get(index);
            // 用途标签和它后面那个值都不是商户：标签是「商品说明」，
            // 值是「生椰拿铁」。两个一起跳过，否则商品名会被当成店名。
            if (PurposeResolver.isPurposeLabel(node)) {
                index += 1;
                continue;
            }
            if (node.length() < 2 || node.length() > 24) continue;
            if (node.matches(".*\\d.*")) continue;
            if (MERCHANT_BLACKLIST.matcher(node).find()) continue;
            if (node.matches("^[\\p{Punct}\\s]+$")) continue;
            return cleanMerchant(node);
        }
        return "未知商户";
    }

    private static String cleanMerchant(String value) {
        String cleaned = value == null ? "" : value
                .replaceAll("(付款方式|支付方式|金额|人民币|成功|已完成|完成|余额|零钱|花呗).*$", "")
                .replaceAll("[:：]", "")
                .trim();
        if (cleaned.length() < 2) return "";
        // 清理后若只剩状态词（如「已收款」「等待对方」），不能当商户名。
        if (MERCHANT_BLACKLIST.matcher(cleaned).find()) return "";
        return cleaned;
    }

    public static final class Result {
        public final boolean shouldCapture;
        public final String reason;
        public final String normalizedText;
        public final long amountCents;
        public final String merchant;
        public final String direction;
        /** 自动推断出的用途，可能为空（页面和词典都没线索）。 */
        public final PurposeResolver.Result purpose;

        private Result(
                boolean shouldCapture,
                String reason,
                String normalizedText,
                long amountCents,
                String merchant,
                String direction,
                PurposeResolver.Result purpose
        ) {
            this.shouldCapture = shouldCapture;
            this.reason = reason;
            this.normalizedText = normalizedText;
            this.amountCents = amountCents;
            this.merchant = merchant;
            this.direction = direction;
            this.purpose = purpose;
        }

        static Result accept(
                String normalizedText,
                long amountCents,
                String merchant,
                String direction,
                PurposeResolver.Result purpose
        ) {
            return new Result(true, "matched", normalizedText, amountCents, merchant, direction, purpose);
        }

        static Result reject(String normalizedText, String reason) {
            return new Result(false, reason, normalizedText, 0L, "", "", PurposeResolver.Result.empty());
        }
    }
}
