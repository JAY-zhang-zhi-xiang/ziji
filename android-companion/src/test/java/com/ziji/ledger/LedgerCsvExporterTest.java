package com.ziji.ledger;

import java.util.Arrays;

public final class LedgerCsvExporterTest {
    public static void main(String[] args) {
        LedgerEntry entry = new LedgerEntry(
                "txn-1",
                1234L,
                "expense",
                "=HYPERLINK(\"http://evil.example\")",
                "微信",
                "餐饮",
                0L,
                "原始\"通知\""
        ).withAmountPurpose(
                1234L,
                "=HYPERLINK(\"http://evil.example\")",
                "微信",
                "餐饮",
                "和同事聚餐"
        );
        String csv = LedgerCsvExporter.export(Arrays.asList(entry));
        assertContains(csv, "时间,收支,金额,商户,账户,分类,用途,原始通知");
        assertContains(csv, "支出,12.34");
        assertContains(csv, "'=HYPERLINK(\"\"http://evil.example\"\")\"");
        assertContains(csv, "\"原始\"\"通知\"\"\"");
        // 导出的表格里必须带上用途，否则导出来又变回一条纯流水。
        assertContains(csv, ",餐饮,和同事聚餐,");
        System.out.println("ledger CSV exporter tests passed: 5");
    }

    private static void assertContains(String text, String expected) {
        if (!text.contains(expected)) {
            throw new AssertionError("expected CSV to contain: " + expected + "\nactual: " + text);
        }
    }
}
