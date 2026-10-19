/**
 * 退款冲抵：网页端与 Android 端共用同一套口径。
 *
 * 退款在账本里是一条 direction=income、category=退款 的记录，但它不是收入，
 * 而是某笔支出的回滚。只按 direction 汇总的话，32 块的咖啡退回来之后
 * 本月支出仍然是 32，收入还多出 32。
 *
 * 做法：按「同商户 + 30 天内 + 原支出金额足够」找回被退的那一笔，
 * 从它所属的分类里扣；找不到原交易的（跨月、商户没识别出来）挂在「退款」项下，
 * 保证总支出依然对得上。
 */

const MATCH_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;
const REFUND_CATEGORY = "退款";

/** 与 Android 端 TransactionParser.merchantKey 保持同一套归一化规则。 */
export function merchantKey(value) {
  let text = String(value || "").replace(/\s+/g, " ").trim();
  if (!text) return "";
  text = text.replace(/[（(][^）)]{0,20}[）)]/g, "").trim();
  text = text.replace(/(股份有限公司|有限责任公司|有限公司|分公司)$/, "").trim();
  text = text.replace(/(旗舰店|专卖店|分店|门店)$/, "").trim();
  if (text.length > 3 && text.endsWith("店")) text = text.slice(0, -1).trim();
  text = text.replace(/^[\s\p{P}]+|[\s\p{P}]+$/gu, "").trim();
  if (text.length < 2) return "";
  if (/^[\d\s¥￥.,，。:：+-]*$/.test(text)) return "";
  if (/^(未知商户|其他|未知|转账|收款|付款|支付|消费|红包|退款|收入)$/.test(text)) return "";
  return text;
}

function toCents(value) {
  const amount = Number(value || 0);
  return Number.isFinite(amount) ? Math.round(amount * 100) : 0;
}

function occurredAt(item) {
  const time = new Date(item.occurredAt || item.createdAt || 0).getTime();
  return Number.isFinite(time) ? time : 0;
}

/**
 * @param {Array} entries    要统计的记录（通常已按月份切好）
 * @param {Array} allEntries 全量账本，只用于「找原交易」
 *
 * 为什么要分成两个参数：9 月 28 日买 100、10 月 2 日退 100，
 * 如果只在 10 月的切片里找原交易，那笔支出根本不在里面，退款就只能挂在 10 月，
 * 结果是「10 月支出 −100、9 月仍然 100」——两个月都错。
 * 用全量账本归因、只对 entries 聚合，退款就会回到它退的那笔支出所在的月份。
 *
 * @returns {{expenseCents:number, refundCents:number, netExpenseCents:number,
 *            incomeCents:number, categories:Map<string, number>,
 *            refundedByOrigin:Map<string, number>, unmatchedRefunds:Array}}
 */
export function computeRefundOffset(entries, allEntries = entries) {
  const list = Array.isArray(entries) ? entries : [];
  const pool = Array.isArray(allEntries) ? allEntries : list;
  const expenses = [];
  const refunds = [];
  const categories = new Map();
  let expenseCents = 0;
  let incomeCents = 0;

  // 归因池：全量账本里的支出与退款。
  for (const item of pool) {
    const cents = toCents(item.amount);
    if (cents <= 0) continue;
    if (item.direction === "expense") {
      expenses.push({ item, cents, time: occurredAt(item) });
    } else if (item.direction === "income" && item.category === REFUND_CATEGORY) {
      refunds.push({ item, cents, time: occurredAt(item) });
    }
  }

  // 每笔原支出还剩多少可退，支持「一笔支出分两次退」。
  const remaining = new Map();
  for (const origin of expenses) remaining.set(origin.item.id, origin.cents);

  // 从最近的退款往前处理：短期内同商户多笔时，退的通常是最近那笔。
  refunds.sort((a, b) => b.time - a.time);
  const originByRefundId = new Set();
  const refundedByOrigin = new Map();
  const unmatchedRefunds = [];
  for (const refund of refunds) {
    const origin = matchOrigin(refund, expenses, remaining);
    if (!origin) {
      unmatchedRefunds.push(refund.item);
      continue;
    }
    originByRefundId.add(refund.item.id);
    remaining.set(origin.item.id, remaining.get(origin.item.id) - refund.cents);
    refundedByOrigin.set(origin.item.id, (refundedByOrigin.get(origin.item.id) || 0) + refund.cents);
  }

  let refundCents = 0;
  for (const item of list) {
    const cents = toCents(item.amount);
    if (cents <= 0) continue;
    if (item.direction === "expense") {
      const refunded = refundedByOrigin.get(item.id) || 0;
      expenseCents += cents;
      refundCents += refunded;
      categories.set(item.category, (categories.get(item.category) || 0) + cents - refunded);
      continue;
    }
    if (item.direction !== "income") continue;
    if (item.category === REFUND_CATEGORY) {
      // 已归因到原交易：它的影响已经算在原交易那个月，这里不再重复扣。
      if (originByRefundId.has(item.id)) continue;
      refundCents += cents;
      categories.set(REFUND_CATEGORY, (categories.get(REFUND_CATEGORY) || 0) - cents);
      continue;
    }
    incomeCents += cents;
  }

  for (const [key, value] of [...categories]) {
    if (value === 0) categories.delete(key);
  }

  return {
    expenseCents,
    refundCents,
    netExpenseCents: expenseCents - refundCents,
    incomeCents,
    categories,
    refundedByOrigin,
    unmatchedRefunds
  };
}

function matchOrigin(refund, expenses, remaining) {
  const key = merchantKey(refund.item.merchant);
  if (!key) return null;
  let best = null;
  let bestGap = Infinity;
  for (const candidate of expenses) {
    const left = remaining.get(candidate.item.id) || 0;
    if (left < refund.cents) continue;
    // 退款不可能发生在支出之前；留 5 分钟容差，两端时间精度不同时不至于漏配。
    if (candidate.time > refund.time + 5 * 60 * 1000) continue;
    if (merchantKey(candidate.item.merchant) !== key) continue;
    const gap = refund.time - candidate.time;
    if (gap > MATCH_WINDOW_MS) continue;
    if (gap < bestGap) {
      bestGap = gap;
      best = candidate;
    }
  }
  return best;
}
