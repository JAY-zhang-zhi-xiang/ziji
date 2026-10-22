package com.ziji.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TransactionParser {
    /** Prefix marking transaction IDs derived from a stable capture event. */
    public static final String STABLE_CAPTURE_ID_PREFIX = "capture-";
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "(?:(?:人民币|RMB|CNY|¥|￥)\\s*)?((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)\\s*(?:元|块)?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ACTION_PATTERN = Pattern.compile("付款|支付(?!宝)|消费|扣款|支出|交易金额|金额|退款|到账|收入|转入");

    /**
     * 转出状态词：出现在转账成功页上，但「收款」二字说的是对方收钱，
     * 钱是从我这里付出去的，必须判为支出。所以要在收入词之前判断。
     */
    private static final Pattern TRANSFER_OUT_PATTERN = Pattern.compile(
            "转账给|已转账给|付款给|支付给|向.{1,24}转账"
                    + "|对方已收款|对方已确认收款|对方收款|对方已领取|对方已接收"
                    + "|等待对方确认收款|等待对方收款|待对方确认收款|等待对方|待对方"
    );

    private static final Pattern INCOME_PATTERN = Pattern.compile(
            "收款成功|收款到账|已收款到账|收到转账|收到一笔|收到红包|红包到账"
                    + "|退款到账|退款成功|退款|退回|收入到账|收入|转入|入账|来自"
    );
    private static final Pattern BALANCE_OR_CARD_PATTERN = Pattern.compile("余额|可用额度|尾号|卡号|账号|账户余额");
    private static final Pattern DATE_CONTEXT_PATTERN = Pattern.compile("\\d{1,2}月\\d{1,2}日|\\d{1,2}:\\d{2}");

    /**
     * 交易时间的几种常见写法。按「信息量」从多到少排列：
     * 带年份的完整时间 > 月日时分 > 昨天时分 > 只有时分。
     */
    private static final Pattern FULL_DATE_TIME_PATTERN = Pattern.compile(
            "(20\\d{2})\\s*[-/年.]\\s*(\\d{1,2})\\s*[-/月.]\\s*(\\d{1,2})\\s*日?"
                    + "\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})(?:\\s*[:：]\\s*(\\d{2}))?"
    );
    private static final Pattern MONTH_DAY_TIME_PATTERN = Pattern.compile(
            "(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})(?:\\s*[:：]\\s*(\\d{2}))?"
    );
    /** 只用来判断「原文写了日期」，不参与解析。 */
    private static final Pattern MONTH_DAY_HINT_PATTERN = Pattern.compile("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日");
    private static final Pattern YESTERDAY_TIME_PATTERN = Pattern.compile(
            "昨天\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})(?:\\s*[:：]\\s*(\\d{2}))?"
    );
    private static final Pattern CLOCK_TIME_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*[:：]\\s*(\\d{2})(?:\\s*[:：]\\s*(\\d{2}))?(?!\\d)"
    );

    /** 时间解析的可信范围：太久远的日期多半来自账单文本，不是这一笔。 */
    private static final long OCCURRED_AT_MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L;
    /** 容忍的时钟误差：超过这个「未来」的时间不可信。 */
    private static final long OCCURRED_AT_FUTURE_TOLERANCE_MILLIS = 2 * 60 * 1000L;

    private static final Pattern BANK_PATTERN = Pattern.compile(
            "银行|信用卡|储蓄卡|银行卡|尾号|云闪付|银联|UnionPay|招商|工商|农业|建设|中国银行|交通银行|浦发|中信|广发|平安",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 状态词黑名单：这些词描述的是交易状态，不是商户。
     * 例如转账页上的「确认收款」「已收款」「等待对方」，若当成商户名会很难看。
     */
    private static final Pattern MERCHANT_STATE_BLACKLIST = Pattern.compile(
            "确认收款|已收款|收款|等待对方|待对方|对方|已到账|到账|成功|完成|已支付|已付款|处理中|支付中"
    );

    /**
     * 界面元素不是商户。
     *
     * 用户账本里出现过一家叫「搜索」的店——那是微信/支付宝页面顶部的搜索框节点。
     * 危害不只是难看：同一笔支付的另一条记录带着真实商户名，
     * 两条「商户名不同」，跨通道判重会直接认定为两笔不同消费，于是重复入账。
     */
    private static final Pattern MERCHANT_UI_NOISE = Pattern.compile(
            "^(?:搜索|搜索结果|全部|全部账单|账单|账单详情|明细|详情|记录|交易记录|支付记录|首页|消息|我的|设置"
                    + "|返回|关闭|更多|筛选|统计|复制|分享|保存|截图|取消|确定|完成)$"
    );

    private static final Pattern[] MERCHANT_PATTERNS = new Pattern[] {
            // 只收「商户类」标签。「商品/商品说明/说明/摘要」说的是买了什么，不是在哪买的：
            // 把「商品说明 生椰拿铁」当商户，账本上就会出现一个叫「说明 生椰拿铁」的店。
            // 「付款方」要排除「付款方式」——否则标签匹配到前三个字，商户会变成「式 花呗」。
            Pattern.compile("(?:商户名称|商户全称|交易商户|商户(?!单号)|商家|收款方|付款方(?!式)|交易对方)[:：\\s]*([^,.;，。；]{2,32})"),
            Pattern.compile("(?:交易对象|对方)[:：]\\s*([^,.;，。；]{2,32})"),
            Pattern.compile("(?:支出|消费|扣款|支付)[(（]([^)）]{2,32})[)）]"),
            Pattern.compile("(?:支付给|付款给|转账给)\\s*([^,.;，。；¥￥\\d]{2,32}?)(?=\\s*(?:¥|￥|人民币|RMB|CNY|\\d)|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("向\\s*([^,.;，。；]{2,32}?)\\s*(?:付款|支付|转账)"),
            Pattern.compile("在\\s*([^,.;，。；]{2,32}?)\\s*(?:消费|付款|支付|扣款)"),
            Pattern.compile("(?:来自|由)\\s*([^,.;，。；]{2,32}?)\\s*(?:的)?\\s*(?:转账|付款|收款|退款)?\\s*(?:¥|￥|人民币|RMB|CNY|\\d|$)")
    };

    /**
     * 商户名后面常常紧跟下一个字段，而字段之间只有空格、没有标点。
     * 不做截断的话，「商家：星巴克 付款金额 ￥32.00」会解析成商户「星巴克 付款」。
     */
    private static final Pattern MERCHANT_FIELD_BOUNDARY = Pattern.compile(
            "\\s*(?:付款方式|支付方式|付款金额|支付金额|交易金额|订单金额|实付金额|实付"
                    + "|订单号|交易单号|商户单号|流水号"
                    + "|支付时间|交易时间|转账说明|转账备注|添加备注|备注"
                    + "|立减|优惠|折扣|手续费|余额|卡号|尾号|金额)"
    );

    private TransactionParser() {
    }

    public static LedgerEntry parse(String packageName, String source, long occurredAtMillis) {
        return parse(packageName, source, occurredAtMillis, null);
    }

    public static LedgerEntry fromPage(
            String packageName,
            String pageText,
            long amountCents,
            String merchant,
            String direction,
            long occurredAtMillis,
            List<String> rules
    ) {
        return fromPage(packageName, pageText, amountCents, merchant, direction, occurredAtMillis, rules, "");
    }

    /**
     * 无障碍通道专用：金额、商户、方向、用途已由 PaymentPageMatcher 从支付成功页提取，
     * 这里只补齐账户、分类与时间戳，保证两条采集通道共用同一套分类规则。
     */
    public static LedgerEntry fromPage(
            String packageName,
            String pageText,
            long amountCents,
            String merchant,
            String direction,
            long occurredAtMillis,
            List<String> rules,
            String note
    ) {
        String text = normalize(pageText);
        String safeMerchant = safe(merchant).isEmpty() ? "未知商户" : merchant;
        String safeDirection = "income".equals(direction) ? "income" : "expense";
        String account = detectAccount(packageName, text);
        // 分类要带上 note（用途/商品名）：平台型商户（美团/饿了么）背后商品千差万别，
        // 光看「美团外卖」会把咖啡、麻辣烫、买药全归成餐饮；把「生椰拿铁」这类商品名
        // 一起喂给分类，detectProductCategory 才能按真实消费对象归对类。
        String category = detectCategory(text + " " + safeMerchant + " " + safe(note), safeDirection);
        category = applyRules(text, safeMerchant, safe(note), category, rules);
        // 传 0 表示「让解析器从原文里找交易时间」，比直接取当前时间更准。
        long timestamp = occurredAtMillis > 0
                ? occurredAtMillis
                : resolveOccurredAt(text, System.currentTimeMillis());
        String id = "acc-" + timestamp + "-" + Integer.toHexString(text.hashCode());
        String safePackage = safe(packageName);
        return new LedgerEntry(
                id,
                amountCents,
                safeDirection,
                safeMerchant,
                account,
                category,
                timestamp,
                text,
                safePackage,
                "accessibility",
                LedgerCodec.fingerprint(safePackage + "|" + amountCents + "|" + safeDirection + "|" + safeMerchant + "|" + account + "|" + category + "|" + text),
                timestamp,
                timestamp,
                0L,
                true,
                safe(note)
        );
    }

    public static LedgerEntry parse(String packageName, String source, long occurredAtMillis, List<String> rules) {
        return parse(packageName, source, occurredAtMillis, rules, "");
    }

    /** Parse a notification while preserving the source event's idempotency key in the ledger ID. */
    public static LedgerEntry parse(
            String packageName,
            String source,
            long occurredAtMillis,
            List<String> rules,
            String stableEventId
    ) {
        String text = normalize(source);
        long amountCents = detectAmountCents(text);
        String direction = detectDirection(text);
        String merchant = detectMerchant(text);
        String account = detectAccount(packageName, text);
        String category = detectCategory(text + " " + merchant, direction);
        category = applyRules(text, merchant, category, rules);
        String note = resolveNote(text, merchant);
        long timestamp = occurredAtMillis > 0
                ? occurredAtMillis
                : resolveOccurredAt(text, System.currentTimeMillis());
        String id = stableEventId == null || stableEventId.trim().isEmpty()
                ? "txn-" + timestamp + "-" + Integer.toHexString(text.hashCode())
                : STABLE_CAPTURE_ID_PREFIX + LedgerCodec.checksum(stableEventId).replace("sha256:", "");
        String sourceType = packageName == null || packageName.trim().isEmpty() ? "share" : "notification";
        return new LedgerEntry(
                id,
                amountCents,
                direction,
                merchant,
                account,
                category,
                timestamp,
                text,
                safe(packageName),
                sourceType,
                LedgerCodec.fingerprint(safe(packageName) + "|" + amountCents + "|" + direction + "|" + merchant + "|" + account + "|" + category + "|" + text),
                timestamp,
                timestamp,
                0L,
                true,
                note
        );
    }

    /**
     * 通知通道的用途推断：先看正文里有没有「转账说明」「商品说明」这类字段，
     * 没有就用商户名查词典（瑞幸咖啡 → 咖啡）。两个都没有就留空，不编造。
     */
    private static String resolveNote(String text, String merchant) {
        PurposeResolver.Result result = PurposeResolver.resolveFromText(text, merchant);
        if (result.isPresent()) return result.note;
        return PurposeResolver.fromDictionary(merchant, text).note;
    }

    /**
     * 统一的方向判定。两条采集通道（通知、页面识别）都走这里，避免各自为政。
     *
     * 顺序很关键：先判「转出」，再判「收入」。
     * 因为转账成功页上写着「等待对方确认收款」「对方已收款」——这里的「收款」
     * 描述的是对方收钱，钱是我付出去的，属于支出；若先匹配收入词就会记反。
     */
    public static String detectDirection(String source) {
        String text = normalize(source);
        if (TRANSFER_OUT_PATTERN.matcher(text).find()) return "expense";
        return INCOME_PATTERN.matcher(text).find() ? "income" : "expense";
    }

    /**
     * 从原文里解析这笔钱实际发生的时间。
     *
     * 以前统一用「通知到达时间」记账，有两个真实后果：23:58 付的钱、00:02 才到通知，
     * 会被记到第二天；月末那几笔一跨月，两个月的统计同时错。支付页和通知里
     * 大多数时候都写着交易时间，没有理由不用它。
     *
     * 解析不出来、或者解析结果明显不可信（太旧、在未来）时，回落到当前时间。
     * 这里的取舍是「宁可退回当前时间，也不记一个错的日期」。
     */
    public static long resolveOccurredAt(String source, long nowMillis) {
        String text = normalize(source);
        long now = nowMillis > 0 ? nowMillis : System.currentTimeMillis();
        if (text.isEmpty()) return now;

        Long parsed = parseFullDateTime(text);
        if (parsed == null) parsed = parseMonthDayTime(text, now);
        // 原文写了日期却解析不出来（比如「2月30日」），说明这条时间不可信，
        // 不要再退而求其次去猜里面的时分——那只会把一个错日期换成另一个。
        boolean dateWritten = MONTH_DAY_HINT_PATTERN.matcher(text).find();
        if (parsed == null && !dateWritten) parsed = parseYesterdayTime(text, now);
        if (parsed == null && !dateWritten) parsed = parseClockTime(text, now);
        if (parsed == null) return now;

        long value = parsed;
        if (value > now + OCCURRED_AT_FUTURE_TOLERANCE_MILLIS) return now;
        if (value < now - OCCURRED_AT_MAX_AGE_MILLIS) return now;
        return value;
    }

    private static Long parseFullDateTime(String text) {
        Matcher matcher = FULL_DATE_TIME_PATTERN.matcher(text);
        if (!matcher.find()) return null;
        return buildTime(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)),
                Integer.parseInt(matcher.group(5)),
                matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6))
        );
    }

    private static Long parseMonthDayTime(String text, long now) {
        Matcher matcher = MONTH_DAY_TIME_PATTERN.matcher(text);
        if (!matcher.find()) return null;
        int month = Integer.parseInt(matcher.group(1));
        int day = Integer.parseInt(matcher.group(2));
        int hour = Integer.parseInt(matcher.group(3));
        int minute = Integer.parseInt(matcher.group(4));
        int second = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));

        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(now);
        int year = base.get(Calendar.YEAR);
        Long candidate = buildTime(year, month, day, hour, minute, second);
        // 「12月31日」这类没写年份的，算出来在未来一般是去年的账单。
        if (candidate != null && candidate > now + 24 * 60 * 60 * 1000L) {
            candidate = buildTime(year - 1, month, day, hour, minute, second);
        }
        return candidate;
    }

    private static Long parseYesterdayTime(String text, long now) {
        Matcher matcher = YESTERDAY_TIME_PATTERN.matcher(text);
        if (!matcher.find()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        if (hour > 23 || minute > 59 || second > 59) return null;

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static Long parseClockTime(String text, long now) {
        Matcher matcher = CLOCK_TIME_PATTERN.matcher(text);
        if (!matcher.find()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        if (hour > 23 || minute > 59 || second > 59) return null;

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, 0);
        long candidate = calendar.getTimeInMillis();
        // 只有时分、没有日期时，比现在晚的只可能是昨天——这正是跨午夜那类记录。
        if (candidate > now + OCCURRED_AT_FUTURE_TOLERANCE_MILLIS) {
            candidate -= 24 * 60 * 60 * 1000L;
        }
        return candidate;
    }

    private static Long buildTime(int year, int month, int day, int hour, int minute, int second) {
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) return null;
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        // 2 月 30 日这类非法日期会被 Calendar 顺延，顺延了就说明原文有误。
        if (calendar.get(Calendar.DAY_OF_MONTH) != day) return null;
        return calendar.getTimeInMillis();
    }

    private static String applyRules(String text, String merchant, String fallback, List<String> rules) {
        return applyRules(text, merchant, "", fallback, rules);
    }

    private static String applyRules(String text, String merchant, String note, String fallback, List<String> rules) {
        if (rules == null) return fallback;
        // 规则关键词可能来自商户名，也可能来自商品名（note）——两者都要参与匹配。
        String haystack = (safe(text) + " " + safe(merchant) + " " + safe(note)).toLowerCase(Locale.ROOT);
        for (String rule : rules) {
            String[] fields = safe(rule).split("\\t", 2);
            if (fields.length != 2) continue;
            String keyword = fields[0].trim().toLowerCase(Locale.ROOT);
            String category = fields[1].trim();
            if (!keyword.isEmpty() && !category.isEmpty() && haystack.contains(keyword)) return category;
        }
        return fallback;
    }

    /**
     * 采纳一个金额候选所需的最低分。
     *
     * 打分原本是「相对」的：初值 {@code Integer.MIN_VALUE}，于是即使所有候选都是
     * 负分（余额 −100、日期 −90），排在最前的那个仍会被当成金额——
     * 「支付成功 12:31」记成 31 元，「支付成功 2026」记成 2026 元。
     *
     * 30 分对应「至少有货币符号 / 元 / 块，或者上下文里有支付动作」。
     * 裸整数一个都不满足，宁可判定「缺少金额」让用户补录，也不猜一个错的数。
     */
    private static final int MIN_AMOUNT_SCORE = 30;

    public static long detectAmountCents(String source) {
        String text = normalize(source);
        Matcher matcher = MONEY_PATTERN.matcher(text);
        long bestAmount = 0;
        int bestScore = Integer.MIN_VALUE;

        while (matcher.find()) {
            long cents = toCents(matcher.group(1));
            if (cents <= 0 || cents >= 100_000_000L) continue;

            int start = matcher.start(1);
            int end = matcher.end(1);
            String before = text.substring(Math.max(0, start - 28), start);
            String after = text.substring(end, Math.min(text.length(), end + 28));
            String context = before + matcher.group() + after;
            boolean hasCurrency = matcher.group().matches(".*(?:人民币|RMB|CNY|¥|￥|元|块).*");
            boolean hasDecimal = matcher.group(1).contains(".");
            String digits = matcher.group(1).replace(",", "");
            int score = 0;

            if (ACTION_PATTERN.matcher(context).find()) score += 45;
            if (hasCurrency) score += 30;
            if (hasDecimal) score += 5;
            if (BALANCE_OR_CARD_PATTERN.matcher(before.substring(Math.max(0, before.length() - 12))).find()) score -= 100;
            if (DATE_CONTEXT_PATTERN.matcher(context).find() && !ACTION_PATTERN.matcher(context).find()) score -= 70;
            if (after.matches("^\\s*(?:月|日|:).*")) score -= 90;
            // 时间片段：12:31 里的 31（前面是数字冒号）、2026-10-17 里的 10 和 17。
            // 带货币单位的数字不参与这些扣分——「金额：32.00」的冒号是标签，不是时间。
            if (!hasCurrency && before.matches(".*\\d{1,2}\\s*[:：]\\s*$")) score -= 120;
            if (!hasCurrency && (before.matches(".*[-/]\\s*$") || after.matches("^\\s*[-/]\\s*\\d.*"))) score -= 80;
            // 四位以上的裸整数（没有货币单位、没有小数）更像年份、尾号、流水号：
            // 「支付成功 2026」不该记成 2026 元。
            if (!hasCurrency && !hasDecimal && digits.length() >= 4) score -= 60;

            if (score >= MIN_AMOUNT_SCORE && score > bestScore) {
                bestScore = score;
                bestAmount = cents;
            }
        }
        return bestAmount;
    }

    private static String detectMerchant(String text) {
        for (Pattern pattern : MERCHANT_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) return cleanMerchant(matcher.group(1));
        }
        return "未知商户";
    }

    /** 这些不是商户，拿它们当关键词会把所有账单都归成同一类。 */
    private static final Pattern MERCHANT_UNLEARNABLE = Pattern.compile(
            "^[\\d\\s¥￥.,，。:：+\\-]*$|未知商户|其他|未知|转账|收款|付款|支付|消费|红包|退款|收入"
    );

    /**
     * 商户归一化：用来做「商户 → 分类」的学习关键词。
     *
     * 同一家店在不同订单里可能写成「瑞幸咖啡」「瑞幸咖啡(国贸店)」「瑞幸咖啡国贸店」，
     * 不归一化就会学出三条规则，而且每条只对那一种写法生效，等于白学。
     */
    public static String merchantKey(String merchant) {
        String value = safe(merchant).replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) return "";
        // 去掉括号里的门店/城市等限定（中英文括号都处理）
        value = value.replaceAll("[（(][^）)]{0,20}[）)]", "").trim();
        // 去掉公司后缀与门店后缀
        value = value.replaceAll("(股份有限公司|有限责任公司|有限公司|分公司)$", "").trim();
        value = value.replaceAll("(旗舰店|专卖店|分店|门店)$", "").trim();
        // 「XX店」只在前面还剩至少两个字时才剥，避免把「书店」剥没了
        if (value.length() > 3 && value.endsWith("店")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        value = value.replaceAll("^[\\s\\p{Punct}]+|[\\s\\p{Punct}]+$", "").trim();
        if (value.length() < 2) return "";
        if (MERCHANT_UNLEARNABLE.matcher(value).find()) return "";
        return value;
    }

    private static String cleanMerchant(String value) {
        String cleaned = safe(value);
        // 先按「下一个字段」截断，再去掉标签与标点——
        // 「商户全称 瑞幸咖啡(国贸店) 付款方式 零钱」只能保留中间那一段。
        Matcher boundary = MERCHANT_FIELD_BOUNDARY.matcher(cleaned);
        if (boundary.find()) cleaned = cleaned.substring(0, boundary.start());
        cleaned = cleaned
                .replaceAll("^(?:全称|名称|姓名)\\s*", "")
                .replaceAll("(付款方式|支付方式|金额|人民币|成功|余额).*$", "")
                .replaceAll("[:：]", "")
                .trim();
        if (cleaned.isEmpty()) return "未知商户";
        // 清理后若只剩状态词（如「确认收款」「已收款」「等待对方」），不能当商户名。
        if (MERCHANT_STATE_BLACKLIST.matcher(cleaned).find()) return "未知商户";
        // 界面元素（搜索框、账单页标题、底栏文案）也不是商户：
        // 「搜索」被当成店名之后，同一笔支付的两条记录商户名对不上，
        // 跨通道判重就会把它们当成两笔不同的消费放行。
        if (MERCHANT_UI_NOISE.matcher(cleaned).find()) return "未知商户";
        return cleaned;
    }

    private static String detectAccount(String packageName, String text) {
        String pkg = safe(packageName).toLowerCase(Locale.ROOT);
        if (BANK_PATTERN.matcher(text).find() || pkg.contains("unionpay") || pkg.contains("bank")) return "银行卡";
        if (pkg.equals("com.tencent.mm") || text.contains("微信") || text.contains("零钱") || text.contains("财付通")) return "微信";
        if (pkg.equals("com.eg.android.alipaygphone") || text.contains("支付宝") || text.contains("花呗") || text.contains("余额宝")) return "支付宝";
        return "未识别账户";
    }

    private static String detectCategory(String text, String direction) {
        if ("income".equals(direction)) return text.contains("退款") || text.contains("退回") ? "退款" : "收入";
        // 先看「商品名」：这是真实消费对象，优先级最高。
        // 平台型商户（美团/饿了么/京东/淘宝）背后商品千差万别，光看「美团外卖」会把
        // 咖啡、麻辣烫、买药全归成餐饮；只有先按商品词（生椰拿铁→餐饮、感冒灵→医疗）判断，
        // 才能把它们分开。商品词没命中再退回商户词典。
        String productCategory = detectProductCategory(text);
        if (!productCategory.isEmpty()) return productCategory;
        // 再查品牌词典：它能识别出「瑞幸咖啡」「蜜雪冰城」这类关键词规则覆盖不到的品牌，
        // 是「其他」占比居高不下的主要原因。
        String dictionaryCategory = MerchantDictionary.categoryOf(text);
        if (!dictionaryCategory.isEmpty()) return dictionaryCategory;
        if (containsAny(text, "餐", "饭", "咖啡", "奶茶", "外卖", "美团", "饿了么", "星巴克", "瑞幸", "肯德基", "麦当劳", "盒饭")) return "餐饮";
        if (containsAny(text, "地铁", "公交", "滴滴", "打车", "出租", "高铁", "火车", "机票", "停车", "加油", "充电桩")) return "交通";
        if (containsAny(text, "淘宝", "天猫", "京东", "拼多多", "超市", "便利店", "便利蜂", "盒马", "山姆", "商场", "优衣库")) return "购物";
        if (containsAny(text, "房租", "物业", "水费", "电费", "燃气", "宽带", "话费", "移动", "联通", "电信")) return "居家";
        if (containsAny(text, "电影", "游戏", "会员", "音乐", "演出", "爱奇艺", "腾讯视频", "网易云", "Steam")) return "娱乐";
        if (containsAny(text, "医院", "药", "门诊", "挂号", "体检", "医保")) return "医疗";
        if (containsAny(text, "课程", "书", "培训", "考试")) return "学习";
        if (containsAny(text, "红包", "礼物", "转账", "份子", "生日")) return "人情";
        return "其他";
    }

    /** 平台型商户：钱付给平台，平台名不能代表消费对象，分类必须靠商品词，猜不出就待确认。 */
    private static final String[] PLATFORM_MERCHANTS = new String[] {
            "美团", "饿了么", "京东", "淘宝", "天猫", "拼多多", "抖音商城", "快手小店", "唯品会"
    };

    /**
     * 按「商品名」分类，优先级高于商户名。
     *
     * 只收能代表消费对象的具体商品词，不收平台名、不收品牌名（那些交给商户词典）。
     * 用「最长命中优先」，避免「药」抢先于「感冒灵」。
     */
    private static String detectProductCategory(String text) {
        String haystack = text;
        String best = "";
        String bestCategory = "";
        for (String[] group : PRODUCT_KEYWORDS) {
            String category = group[0];
            for (int index = 1; index < group.length; index += 1) {
                String keyword = group[index];
                if (keyword.isEmpty()) continue;
                if (keyword.length() <= best.length()) continue;
                if (haystack.contains(keyword)) {
                    best = keyword;
                    bestCategory = category;
                }
            }
        }
        return bestCategory;
    }

    /** 商品词表：具体商品 → 分类。只用完整商品词，不用单字（单字「书」会误伤「秘书」）。 */
    private static final String[][] PRODUCT_KEYWORDS = new String[][] {
            // 餐饮：具体饮品与食物
            {"餐饮", "生椰拿铁", "拿铁", "美式", "馥芮白", "卡布奇诺", "咖啡", "奶茶", "果茶",
                    "汉堡", "薯条", "披萨", "炸鸡", "麻辣烫", "麻辣香锅", "火锅", "烧烤", "寿司",
                    "盖浇饭", "炒饭", "炒面", "拉面", "米线", "饺子", "包子", "粥", "三明治", "沙拉"},
            // 医疗：具体药品/健康
            {"医疗", "感冒灵", "布洛芬", "阿莫西林", "退烧", "止咳", "消炎", "维生素", "口罩",
                    "创可贴", "退热贴", "药膏", "眼药水", "体温计"},
            // 日用
            {"日用", "纸巾", "抽纸", "卷纸", "洗衣液", "洗洁精", "牙膏", "牙刷", "洗发水",
                    "沐浴露", "垃圾袋", "保鲜膜", "电池"},
            // 零食
            {"零食", "薯片", "饼干", "巧克力", "糖果", "坚果", "辣条", "瓜子", "冰淇淋", "雪糕"},
    };

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private static long toCents(String numberText) {
        try {
            return new BigDecimal(numberText.replace(",", ""))
                    .setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2)
                    .longValueExact();
        } catch (RuntimeException error) {
            return 0;
        }
    }

    private static String normalize(String value) {
        return safe(value).replaceAll("\\s+", " ").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
