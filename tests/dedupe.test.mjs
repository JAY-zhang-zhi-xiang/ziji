import assert from "node:assert/strict";
import {
  bothExplicitSources,
  isSamePayment,
  isReliableMerchant,
  sameMerchant,
  mergeDuplicate,
  partitionPossibleDuplicates
} from "../dedupe.js";

const base = 1_760_000_000_000;
const at = (offset = 0) => new Date(base + offset).toISOString();
let checks = 0;

function ok(value, message) {
  checks += 1;
  assert.ok(value, message);
}

// 1. 用户截图里的那一笔：同金额、同一分钟，一条有商户名、一条未知。
ok(
  isSamePayment(
    { amount: 55, direction: "expense", merchant: "大连好利来", occurredAt: at() },
    { amount: 55, direction: "expense", merchant: "未知商户", occurredAt: at() }
  ),
  "同一分钟、同金额、一条商户未知，是同一笔"
);

// 2. 两家都识别出名字的店，即使同一分钟也是两笔。
ok(
  !isSamePayment(
    { amount: 14, direction: "expense", merchant: "红姐包子", occurredAt: at() },
    { amount: 14, direction: "expense", merchant: "瑞幸咖啡", occurredAt: at(5000) }
  ),
  "两个明确的商户名对不上，不能合并"
);

// 3. 隔一分钟的同金额消费是两笔；只差 20 秒则是同一笔。
ok(
  !isSamePayment(
    { amount: 8, direction: "expense", merchant: "未知商户", occurredAt: at() },
    { amount: 8, direction: "expense", merchant: "蜜雪冰城", occurredAt: at(60000) }
  ),
  "隔一分钟的同金额消费是两笔"
);
ok(
  isSamePayment(
    { amount: 8, direction: "expense", merchant: "未知商户", occurredAt: at() },
    { amount: 8, direction: "expense", merchant: "蜜雪冰城", occurredAt: at(20000) }
  ),
  "相差 20 秒的同金额记录是同一笔"
);

// 4. 同商户只有跨通道短时间内才自动判重；同店短时复购应保留。
ok(!isSamePayment(
  { amount: 14, direction: "expense", merchant: "红姐包子", occurredAt: at() },
  { amount: 14, direction: "expense", merchant: "红姐包子", occurredAt: at(6 * 60000) }
), "同商户 6 分钟后可能是复购，不自动合并");
ok(isSamePayment(
  { amount: 14, direction: "expense", merchant: "红姐包子", captureChannel: "notification", occurredAt: at() },
  { amount: 14, direction: "expense", merchant: "红姐包子", captureChannel: "accessibility", occurredAt: at(35000) }
), "不同通道、相同明确商户且间隔 45 秒内可自动去重");
ok(!isSamePayment(
  { amount: 14, direction: "expense", merchant: "红姐包子", captureChannel: "notification", occurredAt: at() },
  { amount: 14, direction: "expense", merchant: "红姐包子", captureChannel: "notification", occurredAt: at(35000) }
), "同通道超过强证据窗口时不自动合并");
ok(!isSamePayment(
  { amount: 14, direction: "expense", merchant: "红姐包子", captureChannel: "notification", occurredAt: at() },
  { amount: 14, direction: "expense", merchant: "红姐包子", captureChannel: "notification", occurredAt: at(20000) }
), "同通道 30 秒内的两次付款也不能自动合并");
ok(!isSamePayment(
  { amount: 14, direction: "expense", merchant: "未知商户", captureChannel: "notification", occurredAt: at() },
  { amount: 14, direction: "expense", merchant: "未知商户", captureChannel: "notification", occurredAt: at(20000) }
), "同通道未知商户记录不能只凭时间自动合并");
ok(
  !isSamePayment(
    { amount: 14, direction: "expense", merchant: "红姐包子", occurredAt: at() },
    { amount: 14, direction: "expense", merchant: "红姐包子", occurredAt: at(15 * 60000) }
  ),
  "同店超过强证据窗口可能是真实第二笔"
);

// 5. 金额 / 方向不同一定不是同一笔。
ok(
  !isSamePayment(
    { amount: 55, direction: "expense", merchant: "未知商户", occurredAt: at() },
    { amount: 56, direction: "expense", merchant: "未知商户", occurredAt: at() }
  ),
  "金额不同一定不是同一笔"
);
ok(
  !isSamePayment(
    { amount: 55, direction: "expense", merchant: "未知商户", occurredAt: at() },
    { amount: 55, direction: "income", merchant: "未知商户", occurredAt: at() }
  ),
  "方向不同一定不是同一笔"
);

// 6. 界面词不是商户。
ok(!isReliableMerchant("搜索"), "「搜索」是界面元素，不是商户");
ok(!isReliableMerchant("未知商户"), "占位商户名不可信");
ok(!isReliableMerchant(""), "空商户不可信");
ok(isReliableMerchant("大连好利来"), "真实商户名可信");
ok(
  isSamePayment(
    { amount: 14, direction: "expense", merchant: "搜索", occurredAt: at() },
    { amount: 14, direction: "expense", merchant: "红姐包子-软景店", occurredAt: at(3000) }
  ),
  "「搜索」不能当作两笔的依据"
);

// 7. 门店后缀归一。
ok(sameMerchant("红姐包子", "红姐包子-软景店"), "带门店后缀是同一家");
ok(sameMerchant("瑞幸咖啡", "瑞幸咖啡(国贸店)"), "带括号门店是同一家");
ok(!sameMerchant("瑞幸咖啡", "星巴克"), "不同品牌不是同一家");

// 8. 合并时取更完整的信息，且不改条数。
const existing = { amount: 55, direction: "expense", merchant: "未知商户", category: "其他", note: "", occurredAt: at(20000) };
const incoming = { amount: 55, direction: "expense", merchant: "大连好利来", category: "餐饮", note: "早餐", occurredAt: at() };
ok(mergeDuplicate(existing, incoming), "有更完整的信息才返回 true");
ok(existing.merchant === "大连好利来", "补上可信商户名");
ok(existing.category === "餐饮", "补上真实分类");
ok(existing.note === "早餐", "补上用途");
ok(existing.occurredAt === at(), "时间取更早的那个");

const untouched = { amount: 55, direction: "expense", merchant: "大连好利来", category: "餐饮", note: "早餐", occurredAt: at() };
ok(!mergeDuplicate(untouched, incoming), "没有新信息时不改动");

// 9. 两条人工录入不参与自动合并：用户自己记的两笔同额消费是两笔真账。
ok(
  bothExplicitSources({ source: "manual" }, { source: "manual" }),
  "两条手动记账属于「都是人工录入」"
);
ok(
  bothExplicitSources({ source: "manual" }, { source: "csv" }),
  "手动记账与 CSV 导入同样不合并"
);
ok(
  !bothExplicitSources({ source: "manual" }, { source: "bridge" }),
  "手动记账与自动采集不算「都是人工录入」，仍可判重"
);
ok(
  !bothExplicitSources({ source: "manual" }, {}),
  "没有来源字段的自动解析条目不算人工录入"
);

// 10. 上面的判定确实会拦住自动合并：同额同店同分钟的两条手动记录本应被判为同一笔。
const manualA = { source: "manual", amount: 28, direction: "expense", merchant: "瑞幸咖啡", occurredAt: at() };
const manualB = { source: "manual", amount: 28, direction: "expense", merchant: "瑞幸咖啡", occurredAt: at(30000) };
ok(isSamePayment(manualA, manualB), "单看金额与时间，这两条会被判成同一笔");
ok(bothExplicitSources(manualA, manualB), "但两条人工录入会被这条判定拦住，不参与合并");

// 11. CSV 疑似重复复核队列满时，不能把相近流水退化成普通入账。
const nearImport = {
  amount: 32,
  direction: "expense",
  merchant: "星巴克",
  occurredAt: at()
};
const existingImport = { ...nearImport, id: "existing", occurredAt: at(5000) };
const fullQueuePlan = partitionPossibleDuplicates([nearImport], [existingImport], 0);
ok(fullQueuePlan.accepted.length === 0, "队列已满时疑似重复 CSV 行不直接入账");
ok(fullQueuePlan.candidates.length === 0 && fullQueuePlan.deferred === 1, "队列已满时明确计为待处理");

const availableQueuePlan = partitionPossibleDuplicates([nearImport], [existingImport], 1);
ok(availableQueuePlan.candidates.length === 1 && availableQueuePlan.accepted.length === 0, "队列有空间时疑似重复仍进入复核");

console.log(`dedupe tests passed: ${checks}`);
