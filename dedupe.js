/**
 * 「这两条是不是同一笔钱」——网页端的判定口径。
 *
 * 和手机端 `PaymentIdentity.java` 是同一套规则，改这里就必须改那边，
 * 否则同一笔账在 App 上是一条、在网页上是两条。
 *
 * 为什么不能只看「金额 + 商户 + 时间」：同一次支付被两条通道各记一次时，
 * 商户名经常对不上——页面通道认出「红姐包子-软景店」，通知通道只解析出
 * 「未知商户」，或者干脆抓到界面上的「搜索」。商户对不上，判重就永远不生效。
 */

/** 同一次付款动作只会触发一次，30 秒内不可能有第二笔真实支付。 */
export const SAME_INSTANT_WINDOW_MILLIS = 30 * 1000;
/** 不同采集通道对同一付款的短暂时间差；更长时间内的同店复购必须保留。 */
export const SAME_MERCHANT_WINDOW_MILLIS = 45 * 1000;
export const POSSIBLE_DUPLICATE_WINDOW_MILLIS = 10 * 60 * 1000;

const MERCHANT_NOISE = new Set([
  "搜索", "全部", "账单", "明细", "详情", "记录", "首页", "消息", "我的", "设置",
  "返回", "完成", "确定", "取消", "关闭", "查看", "更多", "筛选", "统计", "分类",
  "微信支付", "支付宝", "支付成功", "付款成功", "交易成功", "收款", "付款", "转账",
  "订单", "余额", "零钱", "花呗", "银行卡", "信用卡", "未知商户", "未知", "其他", "未分类"
]);

/** 商户名是否可信到可以作为「这是另一家店」的依据。 */
export function isReliableMerchant(merchant) {
  const value = String(merchant || "").trim();
  if (value.length < 2) return false;
  if (MERCHANT_NOISE.has(value.toLowerCase())) return false;
  return !value.includes("搜索") && !value.includes("支付成功") && !value.includes("付款成功");
}

/** 同一家店的两种写法：「红姐包子」与「红姐包子-软景店」。 */
export function sameMerchant(left, right) {
  const a = merchantKey(left);
  const b = merchantKey(right);
  if (!a || !b) return false;
  if (a === b) return true;
  const shorter = a.length <= b.length ? a : b;
  const longer = shorter === a ? b : a;
  return shorter.length >= 2 && longer.includes(shorter);
}

export function merchantKey(merchant) {
  let value = String(merchant || "").replace(/\s+/g, " ").trim();
  value = value.replace(/[（(][^）)]{0,20}[）)]/g, "").trim();
  value = value.replace(/(股份有限公司|有限责任公司|有限公司|分公司)$/, "").trim();
  value = value.replace(/(旗舰店|专卖店|分店|门店)$/, "").trim();
  if (value.length > 3 && value.endsWith("店")) value = value.slice(0, -1).trim();
  return value.replace(/^[\s\p{P}]+|[\s\p{P}]+$/gu, "").trim();
}

function millis(value) {
  const time = new Date(value).getTime();
  return Number.isFinite(time) ? time : NaN;
}

function amountOf(entry) {
  return Math.round(Number(entry?.amount || 0) * 100);
}

/**
 * @param {{amount:number,direction?:string,merchant?:string,occurredAt:string}} a
 * @param {{amount:number,direction?:string,merchant?:string,occurredAt:string}} b
 */
export function isSamePayment(a, b) {
  if (!a || !b) return false;
  const amountA = amountOf(a);
  const amountB = amountOf(b);
  if (amountA <= 0 || amountA !== amountB) return false;
  if ((a.direction || "expense") !== (b.direction || "expense")) return false;

  const timeA = millis(a.occurredAt);
  const timeB = millis(b.occurredAt);
  if (!Number.isFinite(timeA) || !Number.isFinite(timeB)) return false;
  const gap = Math.abs(timeA - timeB);

  const reliableA = isReliableMerchant(a.merchant);
  const reliableB = isReliableMerchant(b.merchant);
  const sameChannel = hasSameCaptureChannel(a, b);
  if (reliableA && reliableB) {
    if (!sameMerchant(a.merchant, b.merchant)) return false;
    return !sameChannel && (gap <= SAME_INSTANT_WINDOW_MILLIS
      || (gap <= SAME_MERCHANT_WINDOW_MILLIS && areDifferentCaptureChannels(a, b)));
  }

  return !sameChannel && gap <= SAME_INSTANT_WINDOW_MILLIS;
}

function hasSameCaptureChannel(a, b) {
  const left = String(a?.captureChannel || "").trim().toLowerCase();
  const right = String(b?.captureChannel || "").trim().toLowerCase();
  return Boolean(left && right && left === right);
}

function areDifferentCaptureChannels(a, b) {
  const left = String(a?.captureChannel || "").trim().toLowerCase();
  const right = String(b?.captureChannel || "").trim().toLowerCase();
  return Boolean(left && right && left !== right);
}

/**
 * Split imported rows into ordinary additions and similar records for review.
 * Similar rows that exceed review capacity are deferred, never silently accepted.
 */
export function partitionPossibleDuplicates(items, existingEntries, availableSlots) {
  const accepted = [];
  const candidates = [];
  let deferred = 0;
  const slots = Math.max(0, Number(availableSlots) || 0);

  for (const item of items || []) {
    const near = accepted.find((entry) => isPossibleDuplicatePair(entry, item))
      || (existingEntries || []).find((entry) => isPossibleDuplicatePair(entry, item));
    if (!near) {
      accepted.push(item);
    } else if (candidates.length < slots) {
      candidates.push({ existing: near, item });
    } else {
      deferred += 1;
    }
  }
  return { accepted, candidates, deferred };
}

export function isPossibleDuplicatePair(left, right) {
  const timeA = new Date(left?.occurredAt).getTime();
  const timeB = new Date(right?.occurredAt).getTime();
  if (!Number.isFinite(timeA) || !Number.isFinite(timeB)) return false;
  if (!(Number(left?.amount) > 0) || Math.round(Number(left.amount) * 100) !== Math.round(Number(right?.amount) * 100)) return false;
  if ((left.direction || "expense") !== (right.direction || "expense")) return false;
  if (Math.abs(timeA - timeB) > POSSIBLE_DUPLICATE_WINDOW_MILLIS) return false;
  if (isReliableMerchant(left.merchant) && isReliableMerchant(right.merchant)) {
    return sameMerchant(left.merchant, right.merchant);
  }
  return true;
}

/**
 * 来源是不是「用户自己填进来的」。
 *
 * 手动记账（manual）、CSV 导入（csv）每一条背后都有一次明确的人工动作，
 * 不存在「机器把同一笔看了两遍」。所以两条都是这类记录时永远不做自动合并——
 * 用户自己敲进去的两笔同额消费是两笔真账，合并等于替他改账。
 */
export function isExplicitSource(entry) {
  const value = String(entry?.source || "").trim().toLowerCase();
  return value === "manual" || value === "csv" || value === "import" || value === "share";
}

/** 两条记录是否都是人工录入——这种组合永远不做自动合并。 */
export function bothExplicitSources(a, b) {
  return isExplicitSource(a) && isExplicitSource(b);
}

/**
 * 判定为重复时，把更完整的信息补进已有那条。
 *
 * 页面通道有商户名但没用途，通知通道有用途但认不出商户——两条都记是重复，
 * 只留先到的那条又会丢信息。这里逐字段取更可信的一版，账本条数不变。
 *
 * @returns {boolean} 是否有字段被改动
 */
export function mergeDuplicate(existing, incoming) {
  if (!existing || !incoming) return false;
  const next = {};

  if (!isReliableMerchant(existing.merchant) && isReliableMerchant(incoming.merchant)) {
    next.merchant = incoming.merchant;
  }

  const placeholder = (category) => !category || category === "其他" || category === "未分类";
  if (placeholder(existing.category) && !placeholder(incoming.category)) {
    next.category = incoming.category;
  }

  if (!String(existing.note || "").trim() && String(incoming.note || "").trim()) {
    next.note = incoming.note;
  }

  const existingTime = millis(existing.occurredAt);
  const incomingTime = millis(incoming.occurredAt);
  if (Number.isFinite(incomingTime) && (!Number.isFinite(existingTime) || incomingTime < existingTime)) {
    next.occurredAt = incoming.occurredAt;
  }

  if (!Object.keys(next).length) return false;
  Object.assign(existing, next);
  return true;
}
