package com.ziji.ledger;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class LedgerCsvExporter {
    private LedgerCsvExporter() {
    }

    public static String export(List<LedgerEntry> entries) {
        StringBuilder csv = new StringBuilder("时间,收支,金额,商户,账户,分类,用途,原始通知\n");
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        for (LedgerEntry entry : entries) {
            csv.append(escape(dateFormat.format(new Date(entry.occurredAtMillis)))).append(',')
                    .append(escape(entry.isExpense() ? "支出" : "收入")).append(',')
                    .append(formatAmount(entry.amountCents)).append(',')
                    .append(escape(entry.merchant)).append(',')
                    .append(escape(entry.account)).append(',')
                    .append(escape(entry.category)).append(',')
                    .append(escape(entry.note)).append(',')
                    .append(escape(entry.rawText)).append('\n');
        }
        return csv.toString();
    }

    private static String formatAmount(long cents) {
        return String.format(Locale.ROOT, "%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
