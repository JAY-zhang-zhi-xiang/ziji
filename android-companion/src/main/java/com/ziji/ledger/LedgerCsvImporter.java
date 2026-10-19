package com.ziji.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 导入 {@link LedgerCsvExporter} 导出的 CSV。
 *
 * 导出的表头是固定的 8 列：时间,收支,金额,商户,账户,分类,用途,原始通知。
 * 这里按同一份定义反向解析，保证「导出再导入」是幂等的——检验方式就写在测试里：
 * 导出一批记录，解析回来，逐字段应当完全一致。
 *
 * 去重按「时间（秒）+ 金额 + 方向 + 商户」判定。反复导入同一个文件不会把账本翻倍，
 * 这是用户最容易踩的坑：恢复数据时多点了一次导入。
 */
public final class LedgerCsvImporter {

    /** 解析结果。invalid 与 duplicates 分开计数，报错才能说清到底是格式坏了还是本来就导入过。 */
    public static final class Result {
        public final List<LedgerEntry> entries;
        public final int duplicates;
        public final int invalid;
        /** 整体性错误（不是 CSV、没有表头）。为 null 表示解析过程正常。 */
        public final String error;

        private Result(List<LedgerEntry> entries, int duplicates, int invalid, String error) {
            this.entries = entries;
            this.duplicates = duplicates;
            this.invalid = invalid;
            this.error = error;
        }

        public boolean isUsable() {
            return error == null;
        }
    }

    private static final String SOURCE_TYPE = "import";
    private static final String SOURCE_PACKAGE = "csv";

    private LedgerCsvImporter() {
    }

    /**
     * @param csv      文件全文（可以带 UTF-8 BOM）
     * @param existing 账本里已有的记录，用于去重；可以为空
     */
    public static Result parse(String csv, List<LedgerEntry> existing) {
        if (csv == null || csv.trim().isEmpty()) {
            return new Result(new ArrayList<>(), 0, 0, "文件是空的");
        }
        List<String[]> rows = tokenize(stripBom(csv));
        if (rows.isEmpty()) {
            return new Result(new ArrayList<>(), 0, 0, "文件里没有内容");
        }

        String[] header = rows.get(0);
        Map<String, Integer> columns = indexHeader(header);
        if (columns == null) {
            return new Result(new ArrayList<>(), 0, 0,
                    "这不是自记导出的 CSV：第一行缺少「时间」「金额」列");
        }
        int timeColumn = column(columns, "时间");
        int directionColumn = column(columns, "收支", "方向");
        int amountColumn = column(columns, "金额");
        int merchantColumn = column(columns, "商户");
        int accountColumn = column(columns, "账户");
        int categoryColumn = column(columns, "分类");
        int noteColumn = column(columns, "用途", "备注", "来源/备注");
        int rawColumn = column(columns, "原始通知", "来源");
        // 没有方向列时不能猜：以前这里取到的是空串，`"支出".equals("")` 不成立，
        // 于是整份文件都按收入导入——一笔支出变成一笔收入，金额还分毫不差。
        if (directionColumn < 0) {
            return new Result(new ArrayList<>(), 0, 0,
                    "缺少「收支」（或「方向」）列，无法判断每笔是支出还是收入");
        }

        Set<String> seen = new HashSet<>();
        if (existing != null) {
            for (LedgerEntry entry : existing) {
                if (entry != null) seen.add(dedupeKey(entry.occurredAtMillis, entry.amountCents, entry.direction, entry.merchant));
            }
        }

        List<LedgerEntry> imported = new ArrayList<>();
        int duplicates = 0;
        int invalid = 0;

        for (int index = 1; index < rows.size(); index += 1) {
            String[] row = rows.get(index);
            if (isBlankRow(row)) continue;

            long occurredAt;
            Long parsedTime = parseTime(cell(row, timeColumn));
            if (parsedTime == null) {
                invalid += 1;
                continue;
            }
            occurredAt = parsedTime;

            long amountCents = parseCents(cell(row, amountColumn));
            if (amountCents <= 0) {
                invalid += 1;
                continue;
            }

            String direction = directionOf(cell(row, directionColumn));
            // 「转账」和空值都不猜：网页端的转账在 Android 账本里没有对应概念，
            // 空值则说明这份文件本身不完整。硬塞成收入会让统计变错。
            if (direction == null) {
                invalid += 1;
                continue;
            }
            String merchant = unescapeCell(cell(row, merchantColumn));
            String account = unescapeCell(cell(row, accountColumn));
            String category = unescapeCell(cell(row, categoryColumn));
            String note = unescapeCell(cell(row, noteColumn));
            String rawText = unescapeCell(cell(row, rawColumn));

            String key = dedupeKey(occurredAt, amountCents, direction, merchant);
            if (!seen.add(key)) {
                duplicates += 1;
                continue;
            }

            imported.add(new LedgerEntry(
                    "import-" + UUID.randomUUID(),
                    amountCents,
                    direction,
                    merchant.isEmpty() ? "未知商户" : merchant,
                    account.isEmpty() ? "未识别账户" : account,
                    LedgerCategories.canonical(category.isEmpty() ? "其他" : category),
                    occurredAt,
                    rawText.isEmpty() ? "CSV 导入 · " + merchant : rawText,
                    SOURCE_PACKAGE,
                    SOURCE_TYPE,
                    LedgerCodec.fingerprint(SOURCE_PACKAGE + "|" + amountCents + "|" + direction + "|" + merchant + "|" + occurredAt),
                    occurredAt,
                    occurredAt,
                    0L,
                    true,
                    note
            ));
        }

        return new Result(imported, duplicates, invalid, null);
    }

    /**
     * 导入时接受的时间格式。
     *
     * 手机端导出的是 {@code yyyy-MM-dd HH:mm:ss}，网页端 {@code toLocaleString("zh-CN")}
     * 产出的是 {@code 2026/10/17 12:31}——斜杠、没有秒。以前这里只认前一种，
     * 网页导出的 CSV 每一行都算「格式错误」，用户看到的是「导入 0 条」。
     * 这里按「最规范的在前」依次尝试，要求整串都被消费，避免半截匹配。
     */
    private static final String[] TIME_PATTERNS = new String[] {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy年MM月dd日 HH:mm:ss",
            "yyyy年MM月dd日 HH:mm",
            "yyyy.MM.dd HH:mm:ss",
            "yyyy.MM.dd HH:mm"
    };

    private static Long parseTime(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return null;
        for (String pattern : TIME_PATTERNS) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.CHINA);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            java.util.Date parsed = format.parse(text, position);
            if (parsed != null && position.getIndex() == text.length()) {
                return parsed.getTime();
            }
        }
        return null;
    }

    /** 只认「支出 / 收入」；空值、转账、别的写法都返回 null，交给调用方计入 invalid。 */
    private static String directionOf(String text) {
        if ("支出".equals(text)) return "expense";
        if ("收入".equals(text)) return "income";
        return null;
    }

    /**
     * 按表头名定位列，而不是按固定下标。
     *
     * 手机端导出的是 8 列（时间,收支,金额,商户,账户,分类,用途,原始通知），
     * 网页端导出的是 7 列且顺序不同（时间,方向,金额,商户,分类,账户,来源/备注）。
     * 按名字取列，两份文件都能导入；用户在 Excel 里插一列也不会整体错位。
     *
     * @return 列名 → 下标；缺少「时间」或「金额」时返回 null（说明不是账本 CSV）。
     */
    private static Map<String, Integer> indexHeader(String[] header) {
        Map<String, Integer> columns = new HashMap<>();
        if (header == null) return null;
        for (int index = 0; index < header.length; index += 1) {
            String name = header[index] == null ? "" : header[index].replace("\uFEFF", "").trim();
            if (!name.isEmpty() && !columns.containsKey(name)) columns.put(name, index);
        }
        if (column(columns, "时间") < 0 || column(columns, "金额") < 0) return null;
        return columns;
    }

    /** 依次尝试几个别名，找不到返回 -1（取值时会得到空串）。 */
    private static int column(Map<String, Integer> columns, String... names) {
        for (String name : names) {
            Integer index = columns.get(name);
            if (index != null) return index;
        }
        return -1;
    }

    /**
     * 去重键：时间精确到秒 + 金额 + 方向 + 商户。
     *
     * 不用指纹：导出文件里没有指纹，重新算出来的指纹必须和原记录逐字节相同才有意义，
     * 而原记录的指纹还揉进了原始通知文本。用业务字段组合更稳，也更接近人的判断。
     */
    private static String dedupeKey(long occurredAtMillis, long amountCents, String direction, String merchant) {
        return (occurredAtMillis / 1000L) + "|" + amountCents + "|" + direction + "|" + (merchant == null ? "" : merchant.trim());
    }

    /**
     * 极简 CSV 分词：支持双引号包裹、引号内逗号与换行、"" 表示一个引号。
     * 不引入第三方库——格式是我们自己导出的，可预期，没必要背一个解析器的体积。
     */
    private static List<String[]> tokenize(String csv) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < csv.length(); index += 1) {
            char ch = csv.charAt(index);
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                        field.append('"');
                        index += 1;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row.toArray(new String[0]));
                row = new ArrayList<>();
            } else if (ch != '\r') {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }

    private static String cell(String[] row, int index) {
        // index 可能是 -1：表头里没有这一列（比如网页端导出没有「原始通知」）。
        if (row == null || index < 0 || index >= row.length || row[index] == null) return "";
        return row[index].trim();
    }

    /**
     * 还原导出时加的防公式前缀。
     *
     * LedgerCsvExporter 会给以 = + - @ 开头的字段加一个单引号，防止 Excel 把商户名当公式执行。
     * 导入时必须去掉，否则商户名会变成「'XXX」。
     */
    private static String unescapeCell(String value) {
        String text = value == null ? "" : value;
        if (text.length() > 1 && text.charAt(0) == '\'' && "=+-@".indexOf(text.charAt(1)) >= 0) {
            return text.substring(1);
        }
        return text;
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * 金额转分。用 BigDecimal 而不是 Double：0.1 + 0.2 那套误差在账本里是不可接受的。
     */
    private static long parseCents(String value) {
        String cleaned = value.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty()) return 0L;
        try {
            BigDecimal amount = new BigDecimal(cleaned);
            if (amount.signum() < 0) return 0L;
            return amount.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private static boolean isBlankRow(String[] row) {
        if (row == null || row.length == 0) return true;
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }
}
