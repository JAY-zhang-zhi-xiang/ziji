import assert from "node:assert/strict";
import { computeRefundOffset, merchantKey } from "../refund.js";

const DAY = 24 * 60 * 60 * 1000;
const BASE = new Date("2026-09-10T12:00:00+08:00").getTime();

function entry(id, amount, direction, merchant, category, time) {
  return {
    id,
    amount,
    direction,
    merchant,
    category,
    occurredAt: new Date(time).toISOString()
  };
}

let checks = 0;
function check(name, actual, expected) {
  checks += 1;
  assert.deepEqual(actual, expected, name);
}

// 没有退款：净支出等于毛支出。
{
  const result = computeRefundOffset([
    entry("a", 32, "expense", "瑞幸咖啡", "餐饮", BASE),
    entry("b", 15, "expense", "滴滴出行", "交通", BASE + DAY)
  ]);
  check("没有退款时毛支出", result.expenseCents, 4700);
  check("没有退款时净支出", result.netExpenseCents, 4700);
  check("没有退款时退款额", result.refundCents, 0);
  check("餐饮合计", result.categories.get("餐饮"), 3200);
}

// 全额退款：支出归零，餐饮项被移除。
{
  const result = computeRefundOffset([
    entry("a", 32, "expense", "瑞幸咖啡", "餐饮", BASE),
    entry("b", 32, "income", "瑞幸咖啡", "退款", BASE + DAY)
  ]);
  check("全额退款后净支出", result.netExpenseCents, 0);
  check("退款额", result.refundCents, 3200);
  check("餐饮应被扣到 0 并移除", result.categories.has("餐饮"), false);
}

// 部分退款：扣回原分类。
{
  const result = computeRefundOffset([
    entry("a", 32, "expense", "瑞幸咖啡", "餐饮", BASE),
    entry("b", 12.8, "income", "瑞幸咖啡", "退款", BASE + DAY)
  ]);
  check("部分退款后净支出", result.netExpenseCents, 1920);
  check("扣回餐饮", result.categories.get("餐饮"), 1920);
}

// 分两次退完。
{
  const result = computeRefundOffset([
    entry("a", 32, "expense", "瑞幸咖啡", "餐饮", BASE),
    entry("b", 16, "income", "瑞幸咖啡", "退款", BASE + DAY),
    entry("c", 16, "income", "瑞幸咖啡", "退款", BASE + 2 * DAY)
  ]);
  check("两次退完净支出", result.netExpenseCents, 0);
  check("餐饮清零", result.categories.has("餐饮"), false);
}

// 商户没识别出来：总额照样扣，单独挂在「退款」项。
{
  const result = computeRefundOffset([
    entry("a", 32, "expense", "星巴克", "餐饮", BASE),
    entry("b", 19.9, "income", "未知商户", "退款", BASE + DAY)
  ]);
  check("匹配不到原交易仍扣总额", result.netExpenseCents, 1210);
  check("退款独立成项", result.categories.get("退款"), -1990);
  check("原分类不动", result.categories.get("餐饮"), 3200);
}

// 退款不计入收入。
{
  const result = computeRefundOffset([
    entry("a", 32, "expense", "瑞幸咖啡", "餐饮", BASE),
    entry("b", 32, "income", "瑞幸咖啡", "退款", BASE + DAY),
    entry("c", 5000, "income", "工资", "收入", BASE + DAY)
  ]);
  check("收入不含退款", result.incomeCents, 500000);
  check("结余", result.incomeCents - result.netExpenseCents, 500000);
}

// 超过 30 天不认定同一笔。
{
  const result = computeRefundOffset([
    entry("a", 32, "expense", "瑞幸咖啡", "餐饮", BASE),
    entry("b", 32, "income", "瑞幸咖啡", "退款", BASE + 40 * DAY)
  ]);
  check("跨月退款仍扣总额", result.netExpenseCents, 0);
  check("跨月退款不归原分类", result.categories.get("退款"), -3200);
}

// 跨月退款：归到原交易所在的月份，而不是退款当月。
// 9/28 买 100、10/2 退 100，正确结果是 9 月净支出 0、10 月 0；
// 只拿当月切片找原交易，会得到「10 月 −100、9 月仍然 100」——两个月都错。
{
  const septemberExpense = entry("a", 100, "expense", "某某电器", "购物",
    new Date("2026-09-28T12:00:00+08:00").getTime());
  const octoberRefund = entry("b", 100, "income", "某某电器", "退款",
    new Date("2026-10-02T09:30:00+08:00").getTime());
  const all = [septemberExpense, octoberRefund];

  const sep = computeRefundOffset([septemberExpense], all);
  check("9 月净支出归零", sep.netExpenseCents, 0);
  check("9 月退款额", sep.refundCents, 10000);
  check("9 月购物被扣平", sep.categories.has("购物"), false);
  check("9 月收入不含退款", sep.incomeCents, 0);

  const oct = computeRefundOffset([octoberRefund], all);
  check("10 月不出现负支出", oct.netExpenseCents, 0);
  check("10 月不重复扣退款", oct.refundCents, 0);
}

// 退款时间早于支出，不是同一笔，不能拿来扣别人的分类。
{
  const result = computeRefundOffset([
    entry("b", 50, "income", "某某超市", "退款", BASE),
    entry("a", 50, "expense", "某某超市", "购物", BASE + DAY)
  ]);
  check("顺序颠倒时退款独立成项", result.categories.get("退款"), -5000);
  check("顺序颠倒时支出不被扣", result.categories.get("购物"), 5000);
}

// 商户归一化与 Android 端一致。
check("剥离门店括号", merchantKey("瑞幸咖啡(国贸店)"), "瑞幸咖啡");
check("剥离裸门店后缀", merchantKey("蜜雪冰城三里屯店"), "蜜雪冰城三里屯");
check("未知商户不可用", merchantKey("未知商户"), "");
check("纯金额不可用", merchantKey("¥28.00"), "");

console.log(`refund tests passed: ${checks}`);
