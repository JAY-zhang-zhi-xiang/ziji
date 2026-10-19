package com.ziji.ledger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 导出 → 导入的往返测试。
 *
 * 这里的断言针对一个很实际的问题：账本以前只能进不能出，用户换手机就全丢。
 * 补上导出之后必须证明「导出再导入」是无损的，否则备份只是一堆看得见、回不去的文本。
 */
public final class LedgerCsvImporterTest {
    private static int checks = 0;
    /** 秒级对齐，导出的时间格式精确到秒，毫秒部分往返必然丢失。 */
    private static final long BASE = 1_759_000_000_000L;

    public static void main(String[] args) {
        roundTripKeepsEveryField();
        reimportIsIdempotent();
        rejectsFileThatIsNotOurCsv();
        skipsBrokenRowsInsteadOfDroppingEverything();
        restoresFormulaGuardPrefix();
        handlesCommaInsideField();
        acceptsWebExportLayout();
        acceptsPwaExportLayout();
        rejectsFilesWithoutDirectionColumn();
        rejectsRowsWithUnknownDirection();

        System.out.println("ledger CSV importer tests passed: " + checks);
    }

    private static void roundTripKeepsEveryField() {
        List<LedgerEntry> origin = list(
                entry("瑞幸咖啡", "餐饮", "生椰拿铁", 3200L, "expense", BASE),
                entry("某某超市", "购物", "", 8800L, "expense", BASE + 60_000L),
                entry("某某公司", "工资", "9 月工资", 1_500_000L, "income", BASE + 120_000L)
        );
        String csv = LedgerCsvExporter.export(origin);
        LedgerCsvImporter.Result result = LedgerCsvImporter.parse(csv, null);

        assertTrue(result.isUsable(), "自己的导出文件必须能被识别：" + result.error);
        assertEquals(3, result.entries.size(), "三条都应导入");
        assertEquals(0, result.invalid, "不应有格式错误的行");
        assertEquals(0, result.duplicates, "空账本导入不应判重");

        for (int index = 0; index < origin.size(); index += 1) {
            LedgerEntry before = origin.get(index);
            LedgerEntry after = result.entries.get(index);
            assertEquals(before.amountCents, after.amountCents, "金额往返后应一致");
            assertEquals(before.direction, after.direction, "方向往返后应一致");
            assertEquals(before.merchant, after.merchant, "商户往返后应一致");
            assertEquals(before.account, after.account, "账户往返后应一致");
            assertEquals(before.category, after.category, "分类往返后应一致");
            assertEquals(before.note, after.note, "用途往返后应一致");
            assertEquals(before.occurredAtMillis / 1000L, after.occurredAtMillis / 1000L,
                    "时间往返后应精确到同一秒");
        }
    }

    /** 恢复数据时用户很容易多点一次导入，账本不能因此翻倍。 */
    private static void reimportIsIdempotent() {
        List<LedgerEntry> origin = list(
                entry("瑞幸咖啡", "餐饮", "生椰拿铁", 3200L, "expense", BASE),
                entry("滴滴出行", "交通", "打车", 2600L, "expense", BASE + 60_000L)
        );
        String csv = LedgerCsvExporter.export(origin);
        LedgerCsvImporter.Result first = LedgerCsvImporter.parse(csv, null);
        LedgerCsvImporter.Result second = LedgerCsvImporter.parse(csv, first.entries);

        assertEquals(0, second.entries.size(), "第二次导入不应新增记录");
        assertEquals(2, second.duplicates, "两笔都应被识别为重复");
    }

    private static void rejectsFileThatIsNotOurCsv() {
        LedgerCsvImporter.Result result = LedgerCsvImporter.parse("a,b,c\n1,2,3", null);
        assertTrue(!result.isUsable(), "缺表头的文件应直接拒绝");
        assertTrue(result.error != null && result.error.contains("时间"),
                "错误信息要说清缺哪一列，实际：" + result.error);

        assertTrue(!LedgerCsvImporter.parse("", null).isUsable(), "空文件应拒绝");
    }

    /** 一行坏了不能连累其他行——CSV 经常被 Excel 动过手脚。 */
    private static void skipsBrokenRowsInsteadOfDroppingEverything() {
        String csv = "时间,收支,金额,商户,账户,分类,用途,原始通知\n"
                + "2026-09-28 12:31:00,支出,32.00,瑞幸咖啡,微信,餐饮,生椰拿铁,\n"
                + "不是时间,支出,18.00,坏行,微信,餐饮,,\n"
                + "2026-09-28 18:02:00,支出,88.00,某某超市,微信,购物,,\n";
        LedgerCsvImporter.Result result = LedgerCsvImporter.parse(csv, null);
        assertEquals(2, result.entries.size(), "两条正常记录应导入");
        assertEquals(1, result.invalid, "坏掉的那一行应计入 invalid");
    }

    /** 导出时给 = + - @ 开头加了防公式的单引号，导入必须还原，否则商户名会多一个撇号。 */
    private static void restoresFormulaGuardPrefix() {
        List<LedgerEntry> origin = list(
                entry("+86 便利店", "购物", "", 500L, "expense", BASE)
        );
        String csv = LedgerCsvExporter.export(origin);
        assertTrue(csv.contains("'+86 便利店"), "导出应加防公式前缀");

        LedgerCsvImporter.Result result = LedgerCsvImporter.parse(csv, null);
        assertEquals("+86 便利店", result.entries.get(0).merchant, "导入应去掉防公式前缀");
    }

    private static void handlesCommaInsideField() {
        List<LedgerEntry> origin = list(
                entry("A,B 便利店", "购物", "买了 A,B 两件", 1200L, "expense", BASE)
        );
        String csv = LedgerCsvExporter.export(origin);
        LedgerCsvImporter.Result result = LedgerCsvImporter.parse(csv, null);
        assertEquals("A,B 便利店", result.entries.get(0).merchant, "字段内的逗号不能被当成分隔符");
        assertEquals("买了 A,B 两件", result.entries.get(0).note, "用途里的逗号也要保住");
    }

    /** 网页端导出的是另一套表头（7 列、顺序不同），也必须能导进手机。 */
    private static void acceptsWebExportLayout() {
        String csv = "时间,方向,金额,商户,分类,账户,来源/备注\n"
                + "2026-09-28 12:31:00,支出,32.00,瑞幸咖啡,餐饮,微信,微信支付通知\n";
        LedgerCsvImporter.Result result = LedgerCsvImporter.parse(csv, null);
        assertEquals(1, result.entries.size(), "网页端格式也要能导入");
        LedgerEntry entry = result.entries.get(0);
        assertEquals(3200L, entry.amountCents, "金额要按表头取，不能按固定下标");
        assertEquals("餐饮", entry.category, "分类列不能错位成账户");
        assertEquals("微信", entry.account, "账户列要按表头取");
        assertEquals("瑞幸咖啡", entry.merchant, "商户列要按表头取");
    }

    /**
     * 网页端真实导出的时间格式：toLocaleString("zh-CN") → 「2026/10/17 12:31」。
     *
     * 上一版测试用的是手写的「2026-09-28 12:31:00」，守的是一个现实中不存在的格式，
     * 结果真实的网页 CSV 每一行都被判成格式错误，用户看到「导入 0 条」。
     */
    private static void acceptsPwaExportLayout() {
        String csv = "时间,方向,金额,商户,分类,账户,来源/备注\n"
                + "\"2026/10/17 12:31\",\"支出\",\"32\",\"星巴克\",\"餐饮\",\"微信\",\"支付宝\"\n"
                + "\"2026/10/16 09:05\",\"支出\",\"12.5\",\"瑞幸咖啡\",\"餐饮\",\"微信\",\"手动记账\"\n";
        LedgerCsvImporter.Result result = LedgerCsvImporter.parse(csv, null);
        assertTrue(result.isUsable(), "网页格式必须可识别：" + result.error);
        assertEquals(2, result.entries.size(), "两行都应导入");
        assertEquals(0, result.invalid, "不应有格式错误的行");
        assertEquals(3200L, result.entries.get(0).amountCents, "整数金额按元转分");
        assertEquals(1250L, result.entries.get(1).amountCents, "一位小数也要正确定价");
    }

    /** 没有方向列时不能默认成收入——那会把整份支出表变成收入表。 */
    private static void rejectsFilesWithoutDirectionColumn() {
        String csv = "时间,金额,商户\n\"2026-10-17 12:31:00\",\"32.00\",\"星巴克\"\n";
        LedgerCsvImporter.Result result = LedgerCsvImporter.parse(csv, null);
        assertTrue(!result.isUsable(), "缺方向列应整体拒绝");
        assertTrue(result.error != null && (result.error.contains("收支") || result.error.contains("方向")),
                "错误信息要说清缺的是方向，实际：" + result.error);
        assertEquals(0, result.entries.size(), "不能把支出导成收入");
    }

    /** 方向值只认「支出 / 收入」；空值和「转账」都算无法识别，不猜。 */
    private static void rejectsRowsWithUnknownDirection() {
        String csv = "时间,收支,金额,商户,账户,分类,用途,原始通知\n"
                + "2026-09-28 12:31:00,支出,32.00,正常行,微信,餐饮,,\n"
                + "2026-09-28 12:32:00,,18.00,空方向,微信,餐饮,,\n"
                + "2026-09-28 12:33:00,转账,20.00,转账行,微信,餐饮,,\n";
        LedgerCsvImporter.Result result = LedgerCsvImporter.parse(csv, null);
        assertEquals(1, result.entries.size(), "只有明确写了支出的那行能导入");
        assertEquals(2, result.invalid, "空方向和转账都要计入 invalid");
    }

    private static LedgerEntry entry(
            String merchant,
            String category,
            String note,
            long amountCents,
            String direction,
            long occurredAt
    ) {
        return new LedgerEntry(
                "id-" + occurredAt + "-" + amountCents,
                amountCents,
                direction,
                merchant,
                "微信",
                category,
                occurredAt,
                merchant + " 原始通知",
                "com.tencent.mm",
                "notification",
                LedgerCodec.fingerprint("seed"),
                occurredAt,
                occurredAt,
                0L,
                true,
                note
        );
    }

    private static List<LedgerEntry> list(LedgerEntry... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        checks += 1;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        checks += 1;
        if (!condition) throw new AssertionError(message);
    }
}
