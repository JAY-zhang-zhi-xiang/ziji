package com.ziji.ledger;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 用途推断：从支付页/通知里自动读出「这笔钱买了什么」。
 *
 * 为什么单独拆一个类：
 * 之前「用途」完全依赖用户补录，本质上只是把微信流水换了个壳。实际上支付成功页
 * 和支付通知里本来就写着答案——「商品说明」「订单标题」「转账说明」「备注」这些字段
 * 的值就是用途，只是之前的解析把它们压平成一整行文本，标签和值对不上号，于是全丢了。
 *
 * 推断按置信度分三层，先命中先返回：
 * 1. PAGE_LABEL：页面上的「标签 → 值」配对，最准，等于原始订单信息。
 * 2. INLINE_LABEL：同一行里的「标签：值」，次准。
 * 3. DICTIONARY：用商户名查内置词典（瑞幸咖啡 → 咖啡），覆盖绝大多数日常消费。
 *
 * 三层都没命中时不编造，返回空字符串，由界面标为「未识别」，不阻塞记账。
 */
public final class PurposeResolver {

    /** 强标签：后面跟的一定是「买了什么」。 */
    private static final String[] STRONG_LABELS = new String[] {
            "商品说明", "商品名称", "订单标题", "订单名称", "交易说明", "付款说明",
            "转账说明", "转账备注", "款项", "消费项目", "用途"
    };

    /** 弱标签：可能为空，也可能写的是用途。 */
    private static final String[] WEAK_LABELS = new String[] {
            "商品", "备注", "摘要", "说明", "订单备注", "留言"
    };

    /** 这些值不是用途，是状态/金额/时间/单号。 */
    private static final Pattern NON_PURPOSE = Pattern.compile(
            "^(?:支付成功|付款成功|交易成功|转账成功|收款成功|退款成功|已完成|完成|成功|失败|处理中|待支付)"
                    + "|^[¥￥$]?\\s*[-+]?\\d[\\d,]*\\.?\\d*\\s*元?$"
                    + "|^\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}"
                    + "|^\\d{1,2}:\\d{2}"
                    + "|^\\d{10,}$"
                    + "|^(?:零钱|零钱通|余额宝|花呗|银行卡|信用卡|储蓄卡|微信支付|支付宝|云闪付|余额|积分|优惠)"
    );

    /** 值里出现这些，说明是把整段页面文本误当成了一个节点。 */
    private static final Pattern TOO_NOISY = Pattern.compile(
            "支付成功|付款成功|订单号|交易单号|商户单号|付款方式|支付方式|实付|合计"
    );

    private static final int MAX_PURPOSE_LENGTH = 40;

    private PurposeResolver() {
    }

    /**
     * 从页面的有序节点序列里推断用途。
     *
     * @param orderedNodes 按视觉阅读顺序排列的节点文本（允许重复，重复对配对有用）
     * @param merchant     已解析出的商户，用于排除「用途 == 商户」这种没信息量的结果
     * @param pageText     整页文本，作为行内标签的兜底
     */
    public static Result resolve(List<String> orderedNodes, String pageText, String merchant) {
        String merchantValue = safe(merchant).trim();

        String paired = fromNodes(orderedNodes, merchantValue);
        if (!paired.isEmpty()) return new Result(paired, Source.PAGE_LABEL);

        String inline = fromInlineText(safe(pageText), merchantValue);
        if (!inline.isEmpty()) return new Result(inline, Source.INLINE_LABEL);

        return Result.empty();
    }

    /**
     * 从通知正文里推断用途。
     *
     * 微信/支付宝的转账通知常带「转账说明：午饭」，这类信息在通知里就是完整的，
     * 不需要等页面。没有就退回词典。
     */
    public static Result resolveFromText(String text, String merchant) {
        String merchantValue = safe(merchant).trim();
        String inline = fromInlineText(safe(text), merchantValue);
        if (!inline.isEmpty()) return new Result(inline, Source.INLINE_LABEL);
        return Result.empty();
    }

    /**
     * 这个节点是不是「用途标签」（商品说明、转账说明、备注……）。
     *
     * 支付页上的标签和它的值是两个相邻节点。商户的兜底扫描必须同时跳过这两个，
     * 否则「商品说明 / 生椰拿铁」里的商品名会被当成店名——账本上就多了一家
     * 叫「生椰拿铁」的商户。
     */
    public static boolean isPurposeLabel(String text) {
        String value = safe(text).replaceAll("[\\s:：]+$", "").trim();
        if (value.isEmpty()) return false;
        for (String label : STRONG_LABELS) {
            if (label.equals(value)) return true;
        }
        for (String label : WEAK_LABELS) {
            if (label.equals(value)) return true;
        }
        return false;
    }

    /** 用商户名查词典，得到「咖啡」「外卖」这种用途词。 */
    public static Result fromDictionary(String merchant, String pageText) {
        MerchantDictionary.Match match = MerchantDictionary.lookup(safe(merchant) + " " + safe(pageText));
        if (match == null) return Result.empty();
        // 词典命中的词如果本来就出现在商户名里（「猫眼电影」命中「电影」），
        // 写成用途只是把商户名再切一遍，没有额外信息；这时让分类去表达就够了。
        String merchantValue = safe(merchant).trim().toLowerCase(Locale.ROOT);
        if (!merchantValue.isEmpty() && merchantValue.contains(match.keyword)) {
            return Result.empty();
        }
        return new Result(match.keyword, Source.DICTIONARY);
    }

    /**
     * 标签 → 值的配对。
     *
     * 支付成功页的节点是「标签一个节点、值一个节点」的垂直布局，
     * 「商品说明」和它下面那行「生椰拿铁」在序列里是紧邻的。
     * 所以这里只需要在标签之后找一个合格的值，而不是在整段文本里瞎猜。
     */
    private static String fromNodes(List<String> orderedNodes, String merchant) {
        if (orderedNodes == null || orderedNodes.isEmpty()) return "";

        for (int index = 0; index < orderedNodes.size(); index += 1) {
            String node = safe(orderedNodes.get(index)).trim();
            if (node.isEmpty()) continue;

            String label = matchLabel(node);
            if (label.isEmpty()) continue;

            // 情况一：标签和值在同一个节点里（「商品说明 生椰拿铁」或「商品说明：生椰拿铁」）。
            String tail = node.substring(label.length()).replaceAll("^[:：\\s]+", "").trim();
            if (!tail.isEmpty()) {
                String cleaned = accept(tail, merchant, label);
                if (!cleaned.isEmpty()) return cleaned;
            }

            // 情况二：值在下一个节点。最多往后看 3 个，跳过空节点和另一个标签。
            for (int offset = 1; offset <= 3 && index + offset < orderedNodes.size(); offset += 1) {
                String candidate = safe(orderedNodes.get(index + offset)).trim();
                if (candidate.isEmpty()) continue;
                if (!matchLabel(candidate).isEmpty()) break;
                String cleaned = accept(candidate, merchant, label);
                if (!cleaned.isEmpty()) return cleaned;
            }
        }
        return "";
    }

    /** 行内形式：「商品说明：生椰拿铁」。 */
    private static String fromInlineText(String text, String merchant) {
        if (text.isEmpty()) return "";
        for (String label : STRONG_LABELS) {
            String value = captureAfterLabel(text, label);
            if (!value.isEmpty()) {
                String cleaned = accept(value, merchant, label);
                if (!cleaned.isEmpty()) return cleaned;
            }
        }
        for (String label : WEAK_LABELS) {
            String value = captureAfterLabel(text, label);
            if (!value.isEmpty()) {
                String cleaned = accept(value, merchant, label);
                if (!cleaned.isEmpty()) return cleaned;
            }
        }
        return "";
    }

    private static String captureAfterLabel(String text, String label) {
        int from = 0;
        while (from <= text.length()) {
            int at = text.indexOf(label, from);
            if (at < 0) return "";
            int cursor = at + label.length();
            // 支付页上标签和值常常只有空格分隔（「商品说明 生椰拿铁」），
            // 所以「跳过空白」本身就是有效的分隔证据，不能跳过之后又要求它是空白。
            boolean separated = false;
            while (cursor < text.length() && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) {
                cursor += 1;
                separated = true;
            }
            if (cursor < text.length() && (text.charAt(cursor) == ':' || text.charAt(cursor) == '：')) {
                cursor += 1;
                separated = true;
                while (cursor < text.length() && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) {
                    cursor += 1;
                }
            }
            if (!separated) {
                // 标签后面直接接着别的字，说明这是误命中（例如「商品」命中「商品说明」）。
                from = at + label.length();
                continue;
            }

            int end = cursor;
            while (end < text.length() && end - cursor < MAX_PURPOSE_LENGTH) {
                char ch = text.charAt(end);
                if (ch == ' ' || ch == ',' || ch == '，' || ch == ';' || ch == '；'
                        || ch == '\n' || ch == '|' || ch == '。') {
                    break;
                }
                end += 1;
            }
            if (end > cursor) return text.substring(cursor, end).trim();
            from = at + label.length();
        }
        return "";
    }

    /** 返回命中的标签，未命中返回空串；优先匹配长标签。 */
    private static String matchLabel(String node) {
        for (String label : STRONG_LABELS) {
            if (startsWithLabel(node, label)) return label;
        }
        for (String label : WEAK_LABELS) {
            if (startsWithLabel(node, label)) return label;
        }
        return "";
    }

    private static boolean startsWithLabel(String node, String label) {
        if (!node.startsWith(label)) return false;
        if (node.length() == label.length()) return true;
        char next = node.charAt(label.length());
        return next == ':' || next == '：' || next == ' ' || next == '\t';
    }

    /** 值合格性检查：排除状态、金额、时间、单号、账户、噪声，以及等于商户名的值。 */
    private static String accept(String rawValue, String merchant, String label) {
        String value = safe(rawValue).trim();
        if (value.length() < 2 || value.length() > MAX_PURPOSE_LENGTH) return "";
        if (NON_PURPOSE.matcher(value).find()) return "";
        if (TOO_NOISY.matcher(value).find()) return "";
        if (value.matches("^[\\p{Punct}\\s]+$")) return "";
        if (!merchant.isEmpty() && value.equalsIgnoreCase(merchant)) return "";
        if (value.startsWith(label)) return "";
        // 「商品说明」这种只剩标签本身的情况
        if (matchLabel(value).equals(value)) return "";
        return value;
    }

    /** 用途来源，用于界面区分「自动识别」和用户手填。 */
    public enum Source {
        NONE,
        PAGE_LABEL,
        INLINE_LABEL,
        DICTIONARY
    }

    public static final class Result {
        public final String note;
        public final Source source;

        private Result(String note, Source source) {
            this.note = note;
            this.source = source;
        }

        static Result empty() {
            return new Result("", Source.NONE);
        }

        public boolean isPresent() {
            return !note.isEmpty();
        }

        /** 自动补的用途在界面上标记为「自动」，避免用户以为是自己写的。 */
        public boolean isAutomatic() {
            return source != Source.NONE;
        }

        public String displaySuffix() {
            switch (source) {
                case PAGE_LABEL:
                case INLINE_LABEL:
                    return "自动识别";
                case DICTIONARY:
                    return "自动归类";
                default:
                    return "";
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 供测试与排障使用：把词典分类暴露出来，避免调用方重复实现。 */
    public static String categoryFor(String text) {
        String value = MerchantDictionary.categoryOf(safe(text).toLowerCase(Locale.ROOT));
        return value == null ? "" : value;
    }
}
