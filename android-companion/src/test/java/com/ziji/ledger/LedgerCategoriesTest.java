package com.ziji.ledger;

import java.util.HashSet;
import java.util.Set;

/**
 * 分类表的守门测试。
 *
 * 这份断言存在的理由很具体：网格和解析器曾经各有一份分类列表，
 * 解析器产出「居家/医疗/学习/其他」，网格里却是「住房/教育」且没有医疗和其他。
 * 结果每一笔自动归成医疗的账，点开编辑时 20 个格子一个都不亮。
 *
 * 所以这里不测「网格长什么样」，只测一条契约：
 * 解析器可能产出的每一个分类，网格里都必须存在，而且能被选中。
 */
public final class LedgerCategoriesTest {
    private static int checks = 0;

    public static void main(String[] args) {
        parserOutputsAreSelectable();
        aliasNamesCollapse();
        unknownFallsBackToLastOption();
        namesAreUnique();
        iconsAreResolvable();

        System.out.println("ledger categories tests passed: " + checks);
    }

    /** TransactionParser.detectCategory 与 MerchantDictionary 实际会产出的取值。 */
    private static void parserOutputsAreSelectable() {
        String[] expenseOutputs = {
                "餐饮", "交通", "购物", "居家", "娱乐", "医疗", "学习", "人情", "红包", "其他"
        };
        for (String name : expenseOutputs) {
            assertTrue(LedgerCategories.contains("expense", name),
                    "支出网格缺少解析器会产出的分类：" + name);
            assertEquals(name, LedgerCategories.selectable("expense", name),
                    "解析器产出的分类必须能被原样选中：" + name);
        }

        // 「收入」是解析器对所有非退款收入给出的唯一取值。
        // 以前收入网格里只有工资/兼职/报销……没有「收入」，于是每一笔自动收入都是空选。
        String[] incomeOutputs = {"收入", "退款"};
        for (String name : incomeOutputs) {
            assertTrue(LedgerCategories.contains("income", name),
                    "收入网格缺少解析器会产出的分类：" + name);
            assertEquals(name, LedgerCategories.selectable("income", name),
                    "解析器产出的收入分类必须能被原样选中：" + name);
        }
    }

    private static void aliasNamesCollapse() {
        assertEquals("居家", LedgerCategories.canonical("住房"), "「住房」应归一到「居家」");
        assertEquals("学习", LedgerCategories.canonical("教育"), "「教育」应归一到「学习」");
        assertEquals("医疗", LedgerCategories.canonical("医药"), "「医药」应归一到「医疗」");
        assertEquals("餐饮", LedgerCategories.canonical("餐饮"), "本来合规的名字不该被改");
        assertEquals("", LedgerCategories.canonical(null), "null 归一为空串");

        // 归一之后必须能在网格里选中，否则改了名还是空选。
        assertEquals("居家", LedgerCategories.selectable("expense", "住房"), "旧名归一后要能选中");
        assertEquals("学习", LedgerCategories.selectable("expense", "教育"), "旧名归一后要能选中");
    }

    private static void unknownFallsBackToLastOption() {
        assertEquals("其他", LedgerCategories.selectable("expense", "手工乱填的"),
                "无法识别的支出分类应落到「其他」");
        assertEquals("其他", LedgerCategories.selectable("expense", ""),
                "空分类应落到「其他」");
        assertEquals("收入", LedgerCategories.selectable("income", "手工乱填的"),
                "无法识别的收入分类应落到「收入」");
        // 兜底项必须是网格里真实存在的一项，不然「兜底」也是空选。
        assertTrue(LedgerCategories.contains("expense", LedgerCategories.FALLBACK_EXPENSE),
                "兜底支出分类必须在网格里");
        assertTrue(LedgerCategories.contains("income", LedgerCategories.FALLBACK_INCOME),
                "兜底收入分类必须在网格里");
    }

    private static void namesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (String name : LedgerCategories.names("expense")) {
            assertTrue(seen.add(name), "支出网格出现重复分类：" + name);
        }
        seen.clear();
        for (String name : LedgerCategories.names("income")) {
            assertTrue(seen.add(name), "收入网格出现重复分类：" + name);
        }
    }

    private static void iconsAreResolvable() {
        for (String name : LedgerCategories.names("expense")) {
            String icon = LedgerCategories.iconOf("expense", name);
            assertTrue(icon.startsWith("ic_"), "支出分类「" + name + "」没有可用图标：" + icon);
        }
        for (String name : LedgerCategories.names("income")) {
            String icon = LedgerCategories.iconOf("income", name);
            assertTrue(icon.startsWith("ic_"), "收入分类「" + name + "」没有可用图标：" + icon);
        }
        // 未知分类也要给兜底图标，不能出现空白格子。
        assertTrue(LedgerCategories.iconOf("expense", "乱填的").startsWith("ic_"),
                "未知分类应返回兜底图标");
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
