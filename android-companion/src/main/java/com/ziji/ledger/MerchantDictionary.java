package com.ziji.ledger;

import java.util.Locale;

/**
 * 内置商户词典：把「这是哪家店」翻译成「这是什么消费」。
 *
 * 为什么需要它：支付通知里能可靠拿到的只有商户名，而分类只回答了「餐饮」这种粗粒度，
 * 用户真正想知道的是「瑞幸咖啡 → 咖啡」。有了这层映射，绝大多数账单在入库那一刻
 * 就自带用途，不需要事后补录。
 *
 * 匹配策略是「关键词命中 + 取最长命中」：
 * - 关键词都是完整品牌名或完整词，不用单字（单字「书」会把「秘书」也吃进来）。
 * - 同一笔文本命中多个品牌时取最长的那个，避免「咖啡」抢先于「瑞幸咖啡」。
 */
public final class MerchantDictionary {

    /** 分类名必须与 TransactionParser.detectCategory 的取值保持一致。 */
    private static final String[][] TABLE = new String[][] {
            // 餐饮 / 咖啡 / 茶饮 / 外卖
            {"餐饮", "瑞幸咖啡", "瑞幸", "星巴克", "starbucks", "库迪咖啡", "库迪", "manner", "tims",
                    "麦当劳", "mcdonald", "肯德基", "kfc", "汉堡王", "德克士", "必胜客", "披萨",
                    "海底捞", "西贝", "外婆家", "绿茶餐厅", "太二", "探鱼", "呷哺", "小龙坎",
                    "杨国福", "张亮麻辣烫", "麻辣烫", "沙县小吃", "兰州拉面", "黄焖鸡", "老乡鸡",
                    "和府捞面", "南城香", "真功夫", "吉野家", "康师傅", "面馆", "米粉", "饺子",
                    "喜茶", "奈雪的茶", "奈雪", "蜜雪冰城", "蜜雪", "茶百道", "古茗", "沪上阿姨",
                    "霸王茶姬", "书亦烧仙草", "益禾堂", "一点点", "coco", "甜啦啦", "茶颜悦色",
                    "美团外卖", "饿了么", "外卖", "食堂", "餐厅", "饭店", "火锅", "烧烤", "小吃",
                    "早餐", "豆浆", "包子", "粥", "面包", "烘焙", "蛋糕", "甜品", "冰淇淋"},

            // 交通 / 出行
            {"交通", "滴滴", "diDi", "曹操出行", "t3出行", "t3出行", "高德打车", "首汽约车", "如祺出行",
                    "地铁", "公交", "轨道交通", "一卡通", "交通卡", "12306", "铁路", "高铁", "火车票",
                    "机票", "航空", "国航", "东航", "南航", "海航", "春秋航空", "吉祥航空",
                    "加油", "中国石化", "中石化", "中国石油", "中石油", "壳牌", "bp", "充电桩",
                    "特来电", "星星充电", "停车", "etc", "高速", "哈啰", "青桔", "美团单车", "共享单车",
                    "出租车", "网约车", "打车"},

            // 购物 / 电商 / 商超
            {"购物", "淘宝", "天猫", "京东", "拼多多", "唯品会", "得物", "小红书", "抖音商城",
                    "快手小店", "苏宁", "国美", "优衣库", "uniqlo", "zara", "h&m", "耐克", "nike",
                    "阿迪达斯", "adidas", "李宁", "安踏", "特步", "名创优品", "miniso", "屈臣氏",
                    "丝芙兰", "山姆", "sam's", "costco", "沃尔玛", "家乐福", "永辉", "大润发",
                    "物美", "华润万家", "盒马", "七鲜", "钱大妈", "叮咚买菜", "朴朴超市",
                    "7-11", "711", "全家", "罗森", "便利蜂", "美宜佳", "便利店", "超市",
                    "商场", "百货", "免税", "奥特莱斯"},

            // 居家 / 生活缴费
            {"居家", "房租", "租金", "物业", "物业管理", "水费", "电费", "燃气费", "天然气",
                    "宽带", "有线电视", "话费", "中国移动", "中国联通", "中国电信", "国家电网",
                    "自来水", "供暖", "取暖", "家政", "保洁", "维修", "宜家", "ikea", "红星美凯龙",
                    "装修", "家具", "家电", "洗衣", "干洗", "快递", "顺丰", "菜鸟"},

            // 娱乐 / 订阅
            {"娱乐", "电影", "影城", "万达影城", "cgv", "猫眼", "淘票票", "steam", "playstation",
                    "switch", "腾讯视频", "爱奇艺", "优酷", "哔哩哔哩", "bilibili", "网易云音乐",
                    "qq音乐", "spotify", "apple music", "ktv", "剧本杀", "密室逃脱", "桌游",
                    "健身房", "乐刻", "超级猩猩", "游泳", "球馆", "演出", "演唱会", "话剧",
                    "游戏", "会员", "订阅"},

            // 医疗 / 健康
            {"医疗", "医院", "门诊", "挂号", "急诊", "体检", "药店", "药房", "大参林", "老百姓大药房",
                    "益丰", "叮当快药", "京东健康", "阿里健康", "口腔", "牙科", "眼科", "中医",
                    "诊所", "医保", "疫苗", "保健品"},

            // 学习 / 教育
            {"学习", "得到", "知乎盐选", "极客时间", "慕课", "coursera", "udemy", "当当", "新华书店",
                    "微信读书", "kindle", "粉笔", "新东方", "学而思", "猿辅导", "培训班", "课程",
                    "学费", "考试", "报名费", "书店", "图书"},

            // 人情 / 社交
            {"人情", "红包", "转账给", "份子钱", "礼金", "礼物", "花店", "鲜花", "蛋糕店", "婚礼",
                    "随礼", "生日", "拜年"},

            // 数码 / 硬件
            {"购物", "apple store", "苹果", "小米", "华为", "荣耀", "oppo", "vivo", "三星",
                    "数码", "电脑", "手机", "耳机", "充电器"}
    };

    private MerchantDictionary() {
    }

    /**
     * 查词典。
     *
     * @return null 表示没命中；命中的 Match 里 label 是可直接展示的用途词。
     */
    public static Match lookup(String text) {
        String haystack = safe(text).toLowerCase(Locale.ROOT);
        if (haystack.isEmpty()) return null;

        String bestKeyword = "";
        String bestCategory = "";
        for (String[] group : TABLE) {
            String category = group[0];
            for (int index = 1; index < group.length; index += 1) {
                String keyword = group[index];
                if (keyword.isEmpty()) continue;
                if (keyword.length() <= bestKeyword.length()) continue;
                if (haystack.contains(keyword)) {
                    bestKeyword = keyword;
                    bestCategory = category;
                }
            }
        }
        if (bestKeyword.isEmpty()) return null;
        return new Match(bestCategory, bestKeyword);
    }

    /** 只取分类，不关心用途词。 */
    public static String categoryOf(String text) {
        Match match = lookup(text);
        return match == null ? "" : match.category;
    }

    public static final class Match {
        public final String category;
        /** 命中的品牌原文，用来生成「用途」文案。 */
        public final String keyword;

        Match(String category, String keyword) {
            this.category = category;
            this.keyword = keyword;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
