import assert from "node:assert/strict";
import { detectAmount, parseTransaction, splitImportText } from "../parser.js";

const now = new Date("2026-06-06T10:00:00+08:00");

const cases = [
  {
    name: "支付宝日期不会被误识别成金额",
    text: "支付宝 06月06日 12:31 向 星巴克 付款 32.00元 付款方式 花呗",
    amount: 32,
    merchant: "星巴克",
    account: "支付宝",
    category: "餐饮",
    direction: "expense"
  },
  {
    name: "微信通知识别 ¥ 金额",
    text: "微信支付收款助手：你已向 美团外卖 支付 ¥46.80，支付方式 零钱，时间 2026-06-06 18:42",
    amount: 46.8,
    merchant: "美团外卖",
    account: "微信",
    category: "餐饮",
    direction: "expense"
  },
  {
    name: "银行短信忽略尾号并识别人民币金额",
    text: "【招商银行】您尾号 8899 的信用卡 06月06日 20:17 在 盒马鲜生 消费人民币 128.60 元。",
    amount: 128.6,
    merchant: "盒马鲜生",
    account: "银行卡",
    category: "购物",
    direction: "expense"
  },
  {
    name: "退款识别为收入方向",
    text: "微信支付：退款到账 19.90元，商户 腾讯视频，原交易已退回零钱。",
    amount: 19.9,
    merchant: "腾讯视频",
    account: "微信",
    category: "退款",
    direction: "income"
  },
  {
    name: "花呗消费识别交易对象",
    text: "支付宝 花呗 | 交易成功 07月07日 09:15 交易对象 滴滴出行 金额 23.50元",
    amount: 23.5,
    merchant: "滴滴出行",
    account: "支付宝",
    category: "交通",
    direction: "expense"
  },
  {
    name: "支付宝商家字段识别商户名",
    text: "支付宝 付款成功 付款金额 ￥12.34 商家：瑞幸咖啡 付款方式 花呗",
    amount: 12.34,
    merchant: "瑞幸咖啡",
    account: "支付宝",
    category: "餐饮",
    direction: "expense"
  },
  {
    name: "微信支付给格式识别商户名",
    text: "微信支付凭证 你已支付给瑞幸咖啡¥18.00 支付方式 零钱",
    amount: 18,
    merchant: "瑞幸咖啡",
    account: "微信",
    category: "餐饮",
    direction: "expense"
  },
  {
    name: "微信人民币格式仍归微信账户",
    text: "微信支付 你已向 麦当劳 支付人民币22.50元，支付方式 零钱",
    amount: 22.5,
    merchant: "麦当劳",
    account: "微信",
    category: "餐饮",
    direction: "expense"
  },
  {
    name: "工商银行快捷支付识别交易金额",
    text: "【工商银行】您尾号1234卡07月07日10:20快捷支付支出(美团)交易金额人民币56.70元，余额888.00元。",
    amount: 56.7,
    merchant: "美团",
    account: "银行卡",
    category: "餐饮",
    direction: "expense"
  },
  {
    name: "建设银行商户消费识别收款方",
    text: "【建设银行】您尾号6789账户07月07日11:05向 沃尔玛超市 支付人民币199.90元，当前余额1000.00元。",
    amount: 199.9,
    merchant: "沃尔玛超市",
    account: "银行卡",
    category: "购物",
    direction: "expense"
  },
  {
    name: "银行短信含财付通仍优先归银行卡",
    text: "【建设银行】您尾号1234账户07月07日12:30向 财付通支付科技有限公司 支付人民币45.60元，当前余额1000.00元。",
    amount: 45.6,
    merchant: "财付通支付科技有限公司",
    account: "银行卡",
    category: "其他",
    direction: "expense"
  },
  {
    name: "银行短信含支付宝快捷支付仍优先归银行卡",
    text: "【招商银行】您尾号8899信用卡07月07日13:20支付宝快捷支付消费人民币88.80元，商户名称淘宝，余额1000.00元。",
    amount: 88.8,
    merchant: "淘宝",
    account: "银行卡",
    category: "购物",
    direction: "expense"
  },
  {
    name: "短信银行快捷支付识别收款方和便利店分类",
    text: "【农业银行】您尾号5678账户07月07日13:20完成快捷支付支出人民币15.00元，收款方：便利蜂，余额1000.00元。",
    amount: 15,
    merchant: "便利蜂",
    account: "银行卡",
    category: "购物",
    direction: "expense"
  },
  {
    name: "银行短信商户名称格式不把余额当交易金额",
    text: "【建设银行】您尾号1234卡07月07日12:10消费人民币38.20元，商户名称肯德基，余额1000.00元。",
    amount: 38.2,
    merchant: "肯德基",
    account: "银行卡",
    category: "餐饮",
    direction: "expense"
  },
  {
    name: "银行短信识别千分位交易金额",
    text: "【招商银行】您尾号8899信用卡07月07日在山姆会员店消费人民币1,234.56元，余额12,345.67元。",
    amount: 1234.56,
    merchant: "山姆会员店",
    account: "银行卡",
    category: "购物",
    direction: "expense"
  },
  {
    name: "云闪付通知归为银行卡消费",
    text: "云闪付 支付成功：您向 永辉超市 支付 ¥86.50",
    amount: 86.5,
    merchant: "永辉超市",
    account: "银行卡",
    category: "购物",
    direction: "expense"
  }
];

for (const item of cases) {
  const parsed = parseTransaction(item.text, { now });
  assert.equal(parsed.amount, item.amount, item.name);
  assert.equal(detectAmount(item.text), item.amount, item.name);
  assert.equal(parsed.merchant, item.merchant, item.name);
  assert.equal(parsed.account, item.account, item.name);
  assert.equal(parsed.category, item.category, item.name);
  assert.equal(parsed.direction, item.direction, item.name);
}

console.log(`parser tests passed: ${cases.length}`);

/**
 * 用途推断：支付页/通知里的「商品说明」「转账说明」本来写着答案，
 * 解析器必须把它读成 note，而不是丢掉。
 */
const purposeCases = [
  { name: "支付页商品说明", text: "微信支付 支付成功 ¥32.00 瑞幸咖啡 商品说明 生椰拿铁 支付方式 零钱", note: "生椰拿铁", category: "餐饮" },
  { name: "转账说明", text: "转账成功 ¥100.00 张三 转账说明：午饭AA", note: "午饭AA", merchant: "张三" },
  { name: "商品说明带冒号", text: "支付宝 支付成功 ¥45.00 某某便利店 商品说明：关东煮", note: "关东煮", category: "购物" },
  { name: "订单标题", text: "微信支付 支付成功 ¥268.00 海底捞 订单标题 双人套餐 订单号 2026101722001", note: "双人套餐" },
  { name: "金额不能当用途", text: "微信支付 支付成功 ¥99.00 某某商店 商品说明 ¥99.00", note: "" },
  { name: "支付账户不能当用途", text: "微信支付 支付成功 ¥99.00 某某商店 备注 零钱", note: "" },
  { name: "用途等于商户时留空", text: "微信支付 支付成功 ¥32.00 瑞幸咖啡 商品说明 瑞幸咖啡", note: "" },
  { name: "词典认识杂牌以外的新消费", text: "微信支付 支付成功 ¥8.00 蜜雪冰城", note: "", category: "餐饮" },
  { name: "词典不会把商户名再切一遍", text: "微信支付 支付成功 ¥26.00 猫眼电影", note: "", category: "娱乐" },
  { name: "退款优先于词典", text: "微信支付 退款成功 ¥18.00 瑞幸咖啡", note: "", category: "退款" }
];

for (const item of purposeCases) {
  const parsed = parseTransaction(item.text, { now });
  assert.equal(parsed.note, item.note, item.name);
  if (item.category) assert.equal(parsed.category, item.category, item.name);
  if (item.merchant) assert.equal(parsed.merchant, item.merchant, item.name);
}

console.log(`purpose tests passed: ${purposeCases.length}`);

const alipayCsv = [
  "交易时间,交易分类,交易对方,商品说明,收/支,金额,支付方式",
  "2026-07-07 12:31:00,餐饮美食,星巴克,拿铁,支出,32.00,花呗"
].join("\n");
const wechatCsv = [
  "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式",
  "2026-07-07 18:42:00,商户消费,美团外卖,订单,支出,46.80,零钱"
].join("\n");
const commaAmountCsv = [
  "交易时间,交易分类,交易对方,商品说明,收/支,金额,支付方式",
  "2026-07-07 20:20:00,购物消费,山姆会员店,家庭采购,支出,\"1,234.56\",银行卡"
].join("\n");

const importCases = [
  {
    name: "支付宝导出 CSV 行识别交易对方",
    text: alipayCsv,
    amount: 32,
    merchant: "星巴克",
    account: "支付宝"
  },
  {
    name: "微信导出 CSV 行识别交易对方",
    text: wechatCsv,
    amount: 46.8,
    merchant: "美团外卖",
    account: "微信"
  },
  {
    name: "CSV 导入识别千分位金额",
    text: commaAmountCsv,
    amount: 1234.56,
    merchant: "山姆会员店",
    account: "银行卡"
  }
];

for (const item of importCases) {
  const rows = splitImportText(item.text);
  assert.equal(rows.length, 1, item.name);
  const parsed = parseTransaction(rows[0], { now });
  assert.equal(parsed.amount, item.amount, item.name);
  assert.equal(parsed.merchant, item.merchant, item.name);
  assert.equal(parsed.account, item.account, item.name);
  assert.equal(parsed.direction, "expense", item.name);
}

console.log(`import parser tests passed: ${importCases.length}`);
