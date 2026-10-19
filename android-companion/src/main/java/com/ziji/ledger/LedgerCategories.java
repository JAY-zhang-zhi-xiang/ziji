package com.ziji.ledger;

/**
 * 分类的唯一来源。
 *
 * 之前出过一个很难发现的问题：解析器会产出「居家」「医疗」「学习」「其他」，
 * 而编辑弹窗的分类网格是另抄的一份列表——里面是「住房」「教育」，没有医疗和其他。
 * 于是一笔自动归成「医疗」的账，点开编辑时 20 个格子一个都不亮，用户以为分类丢了，
 * 随手点一个最像的，原来的分类就被改名，同一类花销从此拆成两个统计桶。
 *
 * 修法不是给两边各打补丁，而是让「网格」和「解析器」引用同一张表：
 * 这份表就是解析器允许产出的全部取值，网格只能从这里来。
 *
 * 加分类的规矩：
 * 1. 先在这里加一行（图标 + 名字）；
 * 2. 如果要让自动识别也产出它，再去 TransactionParser 或 MerchantDictionary 加规则。
 * 反过来说，解析器不允许输出这张表以外的名字——测试会守住这条。
 */
public final class LedgerCategories {

    /** 支出分类。顺序即网格里的排列顺序，最后一项固定是兜底分类。 */
    public static final String[][] EXPENSE = new String[][] {
            {"ic_cat_exp_utensils_crossed", "餐饮"},
            {"ic_cat_exp_gamepad_2", "娱乐"},
            {"ic_cat_exp_shopping_bag", "购物"},
            {"ic_cat_exp_shirt", "穿搭"},
            {"ic_cat_exp_cookie", "零食"},
            {"ic_cat_exp_bus_front", "交通"},
            {"ic_cat_exp_spray_can", "日用"},
            {"ic_cat_exp_gift", "人情"},
            {"ic_cat_exp_paw_print", "宠物"},
            {"ic_cat_exp_sparkles", "美容"},
            {"ic_cat_exp_dumbbell", "运动"},
            {"ic_cat_exp_heart_pulse", "医疗"},
            {"ic_cat_exp_droplets", "服务"},
            {"ic_cat_exp_carrot", "买菜"},
            {"ic_cat_exp_house", "居家"},
            {"ic_cat_exp_car", "爱车"},
            {"ic_cat_exp_hand_coins", "红包"},
            {"ic_cat_exp_arrow_left_right", "转账"},
            {"ic_cat_exp_graduation_cap", "学习"},
            {"ic_cat_exp_dices", "虚拟"},
            {"ic_cat_exp_wine", "烟酒"},
            {"ic_cat_exp_circle_ellipsis", "其他"}
    };

    /** 收入分类。兜底项同样是最后一项。 */
    public static final String[][] INCOME = new String[][] {
            {"ic_cat_inc_briefcase_business", "工资"},
            {"ic_cat_inc_bike", "兼职"},
            {"ic_cat_inc_chart_line", "投资理财"},
            {"ic_cat_inc_heart_handshake", "人情社交"},
            {"ic_cat_inc_trophy", "奖金补贴"},
            {"ic_cat_inc_receipt_text", "报销"},
            {"ic_cat_inc_store", "生意"},
            {"ic_cat_inc_tag", "卖二手"},
            {"ic_cat_inc_hand_coins", "生活费"},
            {"ic_cat_inc_ticket", "中奖"},
            {"ic_cat_inc_gift", "收红包"},
            {"ic_cat_inc_arrow_down_left", "收转账"},
            {"ic_cat_inc_shield_check", "保险理赔"},
            {"ic_cat_inc_rotate_ccw", "退款"},
            {"ic_wallet", "收入"},
            {"ic_cat_inc_settings", "管理"},
            {"ic_cat_inc_circle_ellipsis", "其他"}
    };

    /** 解析器认不出支出分类时的兜底值。 */
    public static final String FALLBACK_EXPENSE = "其他";
    /** 解析器认不出收入分类时的兜底值。 */
    public static final String FALLBACK_INCOME = "收入";

    /**
     * 旧名字 → 新名字。
     *
     * 早期网格里写的是「住房」「教育」，解析器写的是「居家」「学习」，同一个概念两套名字，
     * 统计里会被算成两个分类。这里做一次归一，历史数据靠迁移方法改写成新名字。
     */
    private static final String[][] ALIASES = new String[][] {
            {"住房", "居家"},
            {"教育", "学习"},
            {"医药", "医疗"},
            {"药品", "医疗"}
    };

    private LedgerCategories() {
    }

    public static String[][] optionsFor(String direction) {
        return isIncome(direction) ? INCOME : EXPENSE;
    }

    public static String fallbackFor(String direction) {
        return isIncome(direction) ? FALLBACK_INCOME : FALLBACK_EXPENSE;
    }

    public static boolean isIncome(String direction) {
        return "income".equals(direction);
    }

    /** 这个名字在当前方向的分类表里存不存在。 */
    public static boolean contains(String direction, String category) {
        if (category == null || category.trim().isEmpty()) return false;
        String wanted = category.trim();
        for (String[] option : optionsFor(direction)) {
            if (option[1].equals(wanted)) return true;
        }
        return false;
    }

    /** 把旧名字归一到当前使用的名字；本来就不在别名表里的原样返回。 */
    public static String canonical(String category) {
        if (category == null) return "";
        String value = category.trim();
        for (String[] alias : ALIASES) {
            if (alias[0].equals(value)) return alias[1];
        }
        return value;
    }

    /**
     * 得到一个「网格里一定有这么一项、且会被选中」的分类名。
     *
     * 用于编辑弹窗：如果账本里的分类不在当前方向的网格里（历史别名、跨方向改过、
     * 手输的错误值），就落到兜底项，而不是让网格全暗着。
     */
    public static String selectable(String direction, String category) {
        String value = canonical(category);
        if (contains(direction, value)) return value;
        return fallbackFor(direction);
    }

    /** 分类对应的图标资源名。找不到时给兜底图标，不会出现空白格子。 */
    public static String iconOf(String direction, String category) {
        String value = canonical(category);
        for (String[] option : optionsFor(direction)) {
            if (option[1].equals(value)) return option[0];
        }
        return optionsFor(direction)[optionsFor(direction).length - 1][0];
    }

    /** 全部合法分类名，供测试与迁移使用。 */
    public static String[] names(String direction) {
        String[][] options = optionsFor(direction);
        String[] result = new String[options.length];
        for (int index = 0; index < options.length; index += 1) {
            result[index] = options[index][1];
        }
        return result;
    }
}
