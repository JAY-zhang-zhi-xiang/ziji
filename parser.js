/**
 * 分类表与 Android 端 LedgerCategories 保持一致。
 *
 * 顺序就是支出分类在手机网格里的顺序，最后是收入/退款两个兜底项。
 * 之前这里只有 11 个，手机端有 22 个支出分类：手机记的「零食 / 穿搭 / 日用」
 * 到了网页上既选不到、也不在分类图表里，同一笔钱两端看到的是两套账。
 */
export const CATEGORIES = [
  "餐饮", "娱乐", "购物", "穿搭", "零食", "交通", "日用", "人情",
  "宠物", "美容", "运动", "医疗", "服务", "买菜", "居家", "爱车",
  "红包", "转账", "学习", "虚拟", "烟酒", "其他",
  "收入", "退款"
];

export const EXAMPLES = [
  "支付宝 06月06日 12:31 向 星巴克 付款 32.00元 付款方式 花呗",
  "微信支付收款助手：你已向 美团外卖 支付 ¥46.80，支付方式 零钱，时间 2026-06-06 18:42",
  "【招商银行】您尾号 8899 的信用卡 06月06日 20:17 在 盒马鲜生 消费人民币 128.60 元。",
  "微信支付：退款到账 19.90元，商户 腾讯视频，原交易已退回零钱。"
];

const BUILT_IN_RULES = [
  { category: "餐饮", keywords: ["餐", "饭", "咖啡", "奶茶", "外卖", "美团", "饿了么", "星巴克", "瑞幸", "肯德基", "麦当劳", "盒饭"] },
  { category: "交通", keywords: ["地铁", "公交", "滴滴", "打车", "出租", "高铁", "火车", "机票", "停车", "加油", "充电桩"] },
  { category: "购物", keywords: ["淘宝", "天猫", "京东", "拼多多", "超市", "便利店", "便利蜂", "盒马", "山姆", "商场", "优衣库"] },
  { category: "居家", keywords: ["房租", "物业", "水费", "电费", "燃气", "宽带", "话费", "移动", "联通", "电信"] },
  { category: "娱乐", keywords: ["电影", "游戏", "会员", "音乐", "演出", "爱奇艺", "腾讯视频", "网易云", "Steam"] },
  { category: "医疗", keywords: ["医院", "药", "门诊", "挂号", "体检", "医保"] },
  { category: "学习", keywords: ["课程", "书", "得到", "知识星球", "培训", "考试"] },
  { category: "人情", keywords: ["红包", "礼物", "转账", "份子", "生日"] }
];

// 银行卡优先：很多微信/支付宝通知里会说"人民币 xx 元"但实际是移动支付，不能用"人民币"做银行判据。
const ACCOUNT_RULES = [
  { account: "银行卡", keywords: ["银行", "信用卡", "储蓄卡", "银行卡", "尾号", "云闪付", "银联", "UnionPay", "招商", "工商", "农业", "建设", "中国银行", "交通银行", "浦发", "中信", "广发", "平安"] },
  { account: "微信", keywords: ["微信", "财付通", "零钱", "微信支付"] },
  { account: "支付宝", keywords: ["支付宝", "花呗", "余额宝", "借呗"] }
];

/**
 * 用途字段的标签。
 *
 * 支付成功页和支付通知里本来就写着「商品说明 生椰拿铁」「转账说明 午饭AA」，
 * 这些字段的值就是「这笔钱花在哪」。之前解析器把它们当噪声丢掉了，
 * 结果账本里只剩金额和「其他」，跟微信流水没有区别。
 */
const PURPOSE_STRONG_LABELS = ["商品说明", "商品名称", "订单标题", "订单名称", "交易说明", "付款说明", "转账说明", "转账备注", "款项", "消费项目", "用途"];
const PURPOSE_WEAK_LABELS = ["商品", "备注", "摘要", "说明", "订单备注", "留言"];
const PURPOSE_MAX_LENGTH = 40;

/** 这些值不是用途，是状态、金额、时间、单号或支付账户。 */
const NON_PURPOSE_PATTERN = /^(?:支付成功|付款成功|交易成功|转账成功|收款成功|退款成功|已完成|完成|成功|失败|处理中|待支付)|^[¥￥$]?\s*[-+]?\d[\d,]*\.?\d*\s*元?$|^\d{4}[-/年]\d{1,2}[-/月]\d{1,2}|^\d{1,2}:\d{2}|^\d{10,}$|^(?:零钱|零钱通|余额宝|花呗|银行卡|信用卡|储蓄卡|微信支付|支付宝|云闪付|余额|积分|优惠)/;
const PURPOSE_NOISY_PATTERN = /支付成功|付款成功|订单号|交易单号|商户单号|付款方式|支付方式|实付|合计/;

/**
 * 商户词典：把「这是哪家店」翻译成「这是什么消费」。
 *
 * 与 Android 端 MerchantDictionary 保持同一份分类口径，
 * 否则同一笔账在 App 和网页上会显示不同的分类。
 */
const MERCHANT_DICTIONARY = [
  { category: "餐饮", keywords: ["瑞幸咖啡", "瑞幸", "星巴克", "starbucks", "库迪咖啡", "库迪", "manner", "tims", "麦当劳", "mcdonald", "肯德基", "kfc", "汉堡王", "德克士", "必胜客", "披萨", "海底捞", "西贝", "外婆家", "绿茶餐厅", "太二", "探鱼", "呷哺", "小龙坎", "杨国福", "张亮麻辣烫", "麻辣烫", "沙县小吃", "兰州拉面", "黄焖鸡", "老乡鸡", "和府捞面", "南城香", "真功夫", "吉野家", "康师傅", "面馆", "米粉", "饺子", "喜茶", "奈雪的茶", "奈雪", "蜜雪冰城", "蜜雪", "茶百道", "古茗", "沪上阿姨", "霸王茶姬", "书亦烧仙草", "益禾堂", "一点点", "coco", "甜啦啦", "茶颜悦色", "美团外卖", "饿了么", "外卖", "食堂", "餐厅", "饭店", "火锅", "烧烤", "小吃", "早餐", "豆浆", "包子", "粥", "面包", "烘焙", "蛋糕", "甜品", "冰淇淋"] },
  { category: "交通", keywords: ["滴滴", "曹操出行", "t3出行", "高德打车", "首汽约车", "如祺出行", "地铁", "公交", "轨道交通", "一卡通", "交通卡", "12306", "铁路", "高铁", "火车票", "机票", "航空", "国航", "东航", "南航", "海航", "春秋航空", "吉祥航空", "加油", "中国石化", "中石化", "中国石油", "中石油", "壳牌", "充电桩", "特来电", "星星充电", "停车", "etc", "高速", "哈啰", "青桔", "美团单车", "共享单车", "出租车", "网约车", "打车"] },
  { category: "购物", keywords: ["淘宝", "天猫", "京东", "拼多多", "唯品会", "得物", "小红书", "抖音商城", "快手小店", "苏宁", "国美", "优衣库", "uniqlo", "zara", "h&m", "耐克", "nike", "阿迪达斯", "adidas", "李宁", "安踏", "特步", "名创优品", "miniso", "屈臣氏", "丝芙兰", "山姆", "sam's", "costco", "沃尔玛", "家乐福", "永辉", "大润发", "物美", "华润万家", "盒马", "七鲜", "钱大妈", "叮咚买菜", "朴朴超市", "7-11", "711", "全家", "罗森", "便利蜂", "美宜佳", "便利店", "超市", "商场", "百货", "免税", "奥特莱斯", "apple store", "苹果", "小米", "华为", "荣耀", "oppo", "vivo", "三星", "数码", "电脑", "手机", "耳机", "充电器"] },
  { category: "居家", keywords: ["房租", "租金", "物业", "物业管理", "水费", "电费", "燃气费", "天然气", "宽带", "有线电视", "话费", "中国移动", "中国联通", "中国电信", "国家电网", "自来水", "供暖", "取暖", "家政", "保洁", "维修", "宜家", "ikea", "红星美凯龙", "装修", "家具", "家电", "洗衣", "干洗", "快递", "顺丰", "菜鸟"] },
  { category: "娱乐", keywords: ["电影", "影城", "万达影城", "cgv", "猫眼", "淘票票", "steam", "playstation", "switch", "腾讯视频", "爱奇艺", "优酷", "哔哩哔哩", "bilibili", "网易云音乐", "qq音乐", "spotify", "apple music", "ktv", "剧本杀", "密室逃脱", "桌游", "健身房", "乐刻", "超级猩猩", "游泳", "球馆", "演出", "演唱会", "话剧", "游戏", "会员", "订阅"] },
  { category: "医疗", keywords: ["医院", "门诊", "挂号", "急诊", "体检", "药店", "药房", "大参林", "老百姓大药房", "益丰", "叮当快药", "京东健康", "阿里健康", "口腔", "牙科", "眼科", "中医", "诊所", "医保", "疫苗", "保健品"] },
  { category: "学习", keywords: ["得到", "知乎盐选", "极客时间", "慕课", "coursera", "udemy", "当当", "新华书店", "微信读书", "kindle", "粉笔", "新东方", "学而思", "猿辅导", "培训班", "课程", "学费", "考试", "报名费", "书店", "图书"] },
  { category: "人情", keywords: ["红包", "转账给", "份子钱", "礼金", "礼物", "花店", "鲜花", "蛋糕店", "婚礼", "随礼", "生日", "拜年"] }
];

function lookupDictionary(text) {
  const haystack = String(text || "").toLowerCase();
  if (!haystack) return null;
  let best = "";
  let category = "";
  for (const group of MERCHANT_DICTIONARY) {
    for (const keyword of group.keywords) {
      if (keyword.length <= best.length) continue;
      if (haystack.includes(keyword)) {
        best = keyword;
        category = group.category;
      }
    }
  }
  return best ? { category, keyword: best } : null;
}

/**
 * 从一段文本里推断用途。返回 { note, source }，没有线索时 note 为空串。
 *
 * 优先级：页面标签值 > 词典品牌词。跟 Android 端一致。
 */
export function detectPurpose(text, merchant) {
  const source = String(text || "");
  const merchantValue = String(merchant || "").trim();

  for (const label of [...PURPOSE_STRONG_LABELS, ...PURPOSE_WEAK_LABELS]) {
    const value = captureAfterLabel(source, label);
    if (!value) continue;
    const accepted = acceptPurpose(value, merchantValue, label);
    if (accepted) return { note: accepted, source: "label" };
  }

  const match = lookupDictionary(`${merchantValue} ${source}`);
  // 词典命中的词如果本来就出现在商户名里（「猫眼电影」命中「电影」），
  // 写成用途只是把商户名再切一遍，没有额外信息，交给分类（娱乐）表达即可。
  if (match && !merchantValue.toLowerCase().includes(match.keyword.toLowerCase())) {
    return { note: match.keyword, source: "dictionary" };
  }
  return { note: "", source: "" };
}

function captureAfterLabel(text, label) {
  let from = 0;
  while (from <= text.length) {
    const at = text.indexOf(label, from);
    if (at < 0) return "";
    let cursor = at + label.length;
    // 支付页上标签和值常只有空格分隔，所以「跳过空白」本身就是有效分隔。
    let separated = false;
    while (cursor < text.length && (text[cursor] === " " || text[cursor] === "\t")) {
      cursor += 1;
      separated = true;
    }
    if (cursor < text.length && (text[cursor] === ":" || text[cursor] === "：")) {
      cursor += 1;
      separated = true;
      while (cursor < text.length && (text[cursor] === " " || text[cursor] === "\t")) cursor += 1;
    }
    if (!separated) {
      from = at + label.length;
      continue;
    }
    let end = cursor;
    while (end < text.length && end - cursor < PURPOSE_MAX_LENGTH) {
      const ch = text[end];
      if (ch === " " || ch === "," || ch === ";" || ch === "." || ch === "|" || ch === "\n" || ch === "，" || ch === "。") break;
      end += 1;
    }
    if (end > cursor) return text.slice(cursor, end).trim();
    from = at + label.length;
  }
  return "";
}

function acceptPurpose(rawValue, merchant, label) {
  const value = String(rawValue || "").trim();
  if (value.length < 2 || value.length > PURPOSE_MAX_LENGTH) return "";
  if (NON_PURPOSE_PATTERN.test(value)) return "";
  if (PURPOSE_NOISY_PATTERN.test(value)) return "";
  if (/^[\p{P}\s]+$/u.test(value)) return "";
  if (merchant && value.toLowerCase() === merchant.toLowerCase()) return "";
  if (value.startsWith(label)) return "";
  return value;
}

export function parseTransaction(source, options = {}) {
  const normalized = normalizeText(source);
  const direction = detectDirection(normalized);
  const amount = detectAmount(normalized);
  const merchant = detectMerchant(normalized);
  const account = detectAccount(normalized);
  const occurredAt = detectDate(normalized, options.now);
  const category = detectCategory(normalized, merchant, direction, options.userRules || []);
  const purpose = detectPurpose(normalized, merchant);
  const confidence = scoreConfidence({ amount, merchant, account, occurredAt, category, note: purpose.note });

  return {
    id: createId(),
    source,
    amount,
    direction,
    merchant,
    account,
    category,
    note: purpose.note,
    purposeSource: purpose.source,
    occurredAt,
    confidence,
    createdAt: new Date().toISOString(),
    autoParsed: true
  };
}

export function splitImportText(text) {
  const trimmed = String(text || "").trim();
  if (!trimmed) return [];

  const jsonRows = parseJsonRows(trimmed);
  if (jsonRows.length) return jsonRows;

  const delimitedRows = parseDelimitedImportRows(trimmed);
  if (delimitedRows.length) return delimitedRows;

  const rows = trimmed
    .split(/\n+/)
    .map((row) => row.trim())
    .filter(Boolean);

  if (rows.length <= 1) return [trimmed];

  return rows
    .map((row) => row.replace(/\t/g, " "))
    .filter((row) => /\d/.test(row) && /(元|¥|￥|RMB|CNY|支付|付款|消费|扣款|收入|退款|转账)/i.test(row));
}

export function detectAmount(text) {
  return collectAmountCandidates(text)
    .filter((candidate) => candidate.value > 0 && candidate.value < 1000000)
    .sort((a, b) => b.score - a.score)[0]?.value || 0;
}

function parseJsonRows(text) {
  try {
    const data = JSON.parse(text);
    const rows = Array.isArray(data) ? data : [data];
    return rows.map((item) => Object.values(item).join(" ")).filter(Boolean);
  } catch {
    return [];
  }
}

function parseDelimitedImportRows(text) {
  const lines = text.split(/\n+/).map((row) => row.trim()).filter(Boolean);
  if (lines.length < 2) return [];

  const delimiter = lines[0].includes("\t") ? "\t" : lines[0].includes(",") ? "," : "";
  if (!delimiter) return [];

  const headers = parseDelimitedLine(lines[0], delimiter).map(normalizeHeader);
  if (!looksLikeBillTable(headers)) return [];

  return lines
    .slice(1)
    .map((line) => buildImportRow(headers, parseDelimitedLine(line, delimiter)))
    .filter(Boolean);
}

function parseDelimitedLine(line, delimiter) {
  const cells = [];
  let current = "";
  let inQuotes = false;

  for (let i = 0; i < line.length; i += 1) {
    const char = line[i];
    if (char === "\"") {
      if (inQuotes && line[i + 1] === "\"") {
        current += "\"";
        i += 1;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (char === delimiter && !inQuotes) {
      cells.push(current.trim());
      current = "";
    } else {
      current += char;
    }
  }

  cells.push(current.trim());
  return cells;
}

function normalizeHeader(header) {
  return String(header || "").replace(/^\uFEFF/, "").replace(/\s+/g, "").trim();
}

function looksLikeBillTable(headers) {
  const joined = headers.join("|");
  return /交易时间|时间/.test(joined) && /金额/.test(joined) && /(交易对方|对方|商户|收款方|商品)/.test(joined);
}

function buildImportRow(headers, cells) {
  const record = Object.fromEntries(headers.map((header, index) => [header, cells[index] || ""]));
  const amount = normalizeAmount(getField(record, ["金额"]));
  if (!amount) return "";

  const direction = normalizeDirection(getField(record, ["收/支", "收支", "收入/支出"]));
  if (direction === "不计收支") return "";

  const merchant = getField(record, ["交易对方", "对方", "商户名称", "交易商户", "商户", "收款方"]);
  const product = getField(record, ["商品说明", "商品", "说明", "备注"]);
  const time = getField(record, ["交易时间", "时间"]);
  const payment = getField(record, ["支付方式", "付款方式", "账户"]);
  const category = getField(record, ["交易分类", "交易类型", "分类"]);

  return [
    time,
    direction,
    merchant ? `交易对象：${merchant}` : "",
    product ? `商品：${product}` : "",
    `金额 ${amount}元`,
    payment ? `支付方式 ${payment}` : "",
    category
  ].filter(Boolean).join("，");
}

function getField(record, names) {
  for (const [header, value] of Object.entries(record)) {
    if (names.some((name) => header.includes(name))) return String(value || "").trim();
  }
  return "";
}

function normalizeAmount(raw) {
  const amount = String(raw || "").replace(/[^\d.]/g, "");
  return amount && Number(amount) > 0 ? amount : "";
}

function normalizeDirection(raw) {
  const value = String(raw || "");
  if (/不计/.test(value)) return "不计收支";
  if (/收入|收/.test(value)) return "收入";
  if (/支出|支/.test(value)) return "支出";
  return "";
}

function normalizeText(text) {
  return text
    .replace(/\s+/g, " ")
    .replace(/，/g, ",")
    .replace(/。/g, ".")
    .replace(/；/g, ";")
    .trim();
}

function detectDirection(text) {
  // 与 Android 端 TransactionParser.detectDirection 保持同一判定顺序：先判「转出」。
  // 转账成功页写着「对方已收款」「等待对方确认收款」——这里的「收款」说的是对方，
  // 钱是从我这里付出去的；先判收入就会把转出记成收入，双端还会各判各的。
  if (/(已转账给|转账给|付款给|支付给|向\s*[^,.;，。；]{1,24}\s*转账|对方已收款|对方已确认收款|对方收款|对方已领取|对方已接收|等待对方确认收款|等待对方收款|待对方确认收款|等待对方|待对方)/.test(text)) return "expense";
  if (/(退款到账|退款|退回|收入到账|转入|入账)/.test(text)) return "income";
  if (/(你已向|已向|向\s*[^,.;，。；]{1,32}\s*(?:支付|付款|转账)|在\s*[^,.;，。；]{1,32}\s*(?:消费|支付|付款|扣款)|付款|消费|扣款|支出|支付(?!宝))/.test(text)) return "expense";
  if (/(收入|到账|收款)/.test(text)) return "income";
  return "expense";
}

function collectAmountCandidates(text) {
  const candidates = [];
  const amountPattern = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?";
  const moneyPattern = new RegExp(`(?:人民币|RMB|CNY|¥|￥)\\s*(${amountPattern})\\s*(?:元|块)?|(${amountPattern})\\s*(?:元|块)`, "gi");
  const actionPattern = new RegExp(`(?:^|[^\\u4e00-\\u9fffA-Za-z])(?:支付(?!宝)|付款|消费|扣款|支出|转账|提现|还款|交易金额|金额|收入|到账|退款|退回)[^0-9¥￥]{0,18}(?:人民币|RMB|CNY|¥|￥)?\\s*(${amountPattern})(?:\\s*(?:元|块))?`, "giu");

  for (const match of text.matchAll(moneyPattern)) {
    const numberText = match[1] || match[2];
    candidates.push(buildAmountCandidate(text, match, numberText, "currency"));
  }

  for (const match of text.matchAll(actionPattern)) {
    candidates.push(buildAmountCandidate(text, match, match[1], "action"));
  }

  return dedupeAmountCandidates(candidates);
}

function buildAmountCandidate(text, match, numberText, kind) {
  const index = match.index + match[0].lastIndexOf(numberText);
  const candidate = {
    value: Number(numberText.replace(/,/g, "")),
    numberText,
    index,
    raw: match[0],
    kind,
    score: kind === "currency" ? 60 : 35
  };

  const context = text.slice(Math.max(0, index - 28), Math.min(text.length, index + numberText.length + 28));
  if (/(付款|消费|扣款|支出|转账|提现|还款|交易金额|金额|收入|到账|退款|退回|支付(?!宝))/.test(context)) candidate.score += 30;
  if (/(¥|￥|人民币|RMB|CNY|元|块)/i.test(candidate.raw)) candidate.score += 20;
  if (numberText.includes(".")) candidate.score += 6;
  const beforeNumber = text.slice(Math.max(0, index - 8), index);
  if (/(尾号|卡号|账号)\s*$/.test(beforeNumber)) candidate.score -= 80;
  if (kind === "action" && /(\d{1,2}月\d{1,2}日|\d{1,2}:\d{2})/.test(context)) candidate.score -= 70;

  return candidate;
}

function dedupeAmountCandidates(candidates) {
  const seen = new Set();
  return candidates.filter((candidate) => {
    const key = `${candidate.index}:${candidate.value}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function detectMerchant(text) {
  const patterns = [
    // 「商品」「说明」「摘要」是用途字段，不是商户字段。留在这里会让
    // 「商品说明 生椰拿铁」把整个商品名解析成商户，商户和用途就都错了。
    /(?:商户名称|商户全称|交易商户|商户|商家|收款方|交易对象|对方)[:：\s]*([^,.;，。；]{2,24})/,
    /(?:支出|消费|扣款|支付)\(([^)（）]{2,24})\)/,
    /(?:支出|消费|扣款|支付)（([^)（）]{2,24})）/,
    /(?:支付给|付款给)\s*([^,.;，。；¥￥\d]{2,24}?)(?:\s*(?:¥|￥|人民币|RMB|CNY|\d)|$)/i,
    /向\s*([^,.;，。；]{2,32}?)\s+(?:付款|支付|转账)(?:人民币|RMB|CNY|¥|￥|\d)/i,
    /向\s*([^,.;，。；]{2,24}?)\s*(?:付款|支付|转账)/,
    /在\s*([^,.;，。；]{2,24}?)\s*(?:消费|支付|付款|扣款)/,
    /(?:来自|由)\s*([^,.;，。；]{2,24}?)\s*(?:退款|转入|到账)/
  ];

  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match?.[1]) {
      const cleaned = cleanMerchant(match[1]);
      if (cleaned !== "未知商户") return cleaned;
    }
  }

  const candidate = text
    .split(/[ ,.;，。；]/)
    .map((part) => part.trim())
    .find((part) => part.length >= 2 && part.length <= 12
      && !/\d/.test(part)
      // 状态词不是商户：「转账成功」被当成商户名，账本上就会出现一个叫「转账」的商家。
      && !/(支付|付款|消费|银行|微信|支付宝|人民币|时间|方式|转账|收款|退款|收入|余额|成功|失败|完成|订单)/.test(part));

  return candidate ? cleanMerchant(candidate) : "未知商户";
}

/** 商户名后面常常紧跟下一个字段，字段之间只有空格。不截断就会把字段名吃进商户。 */
const MERCHANT_FIELD_BOUNDARY = /(付款方式|支付方式|付款金额|支付金额|交易金额|订单金额|实付金额|实付|订单号|交易单号|商户单号|流水号|支付时间|交易时间|转账说明|转账备注|添加备注|备注|立减|优惠|折扣|手续费|余额|卡号|尾号|金额)/;

function cleanMerchant(value) {
  let cleaned = String(value || "").trim();
  const boundary = cleaned.search(MERCHANT_FIELD_BOUNDARY);
  if (boundary > 0) cleaned = cleaned.slice(0, boundary);
  cleaned = cleaned
    .replace(/^(?:全称|名称|姓名)\s*/, "")
    .replace(/(付款方式|支付方式|金额|人民币|成功|已|您|的信用卡|的储蓄卡).*$/, "")
    .replace(/[:：]/g, "")
    .trim();
  return cleaned || "未知商户";
}

function detectAccount(text) {
  const matched = ACCOUNT_RULES.find((rule) => rule.keywords.some((keyword) => text.includes(keyword)));
  return matched?.account || "未识别账户";
}

function detectDate(text, fallbackNow) {
  const now = fallbackNow ? new Date(fallbackNow) : new Date();
  const full = text.match(/(20\d{2})[-年\/.](\d{1,2})[-月\/.](\d{1,2})日?\s*(\d{1,2})?:?(\d{2})?/);
  if (full) return buildDate(full[1], full[2], full[3], full[4], full[5]);

  const monthDay = text.match(/(\d{1,2})月(\d{1,2})日?\s*(\d{1,2})?:?(\d{2})?/);
  if (monthDay) return buildDate(now.getFullYear(), monthDay[1], monthDay[2], monthDay[3], monthDay[4]);

  const today = text.match(/今天\s*(\d{1,2})?:?(\d{2})?/);
  if (today) return buildDate(now.getFullYear(), now.getMonth() + 1, now.getDate(), today[1], today[2]);

  const yesterday = text.match(/昨天\s*(\d{1,2})?:?(\d{2})?/);
  if (yesterday) {
    const date = new Date(now);
    date.setDate(date.getDate() - 1);
    return buildDate(date.getFullYear(), date.getMonth() + 1, date.getDate(), yesterday[1], yesterday[2]);
  }

  return now.toISOString();
}

function buildDate(year, month, day, hour = "12", minute = "00") {
  const date = new Date(Number(year), Number(month) - 1, Number(day), Number(hour || 12), Number(minute || 0));
  return Number.isNaN(date.getTime()) ? new Date().toISOString() : date.toISOString();
}

function detectCategory(text, merchant, direction, userRules) {
  if (direction === "income") {
    if (/(退款|退回)/.test(text)) return "退款";
    return "收入";
  }

  const merged = `${text} ${merchant}`;
  const custom = userRules.find((rule) => merged.includes(rule.keyword));
  if (custom) return custom.category;

  // 词典优先于内置关键词：它认识「蜜雪冰城」「名创优品」这类内置规则覆盖不到的品牌，
  // 是「其他」占比过高的主要解法。
  const dictionary = lookupDictionary(merged);
  if (dictionary) return dictionary.category;

  const builtIn = BUILT_IN_RULES.find((rule) => rule.keywords.some((keyword) => merged.includes(keyword)));
  return builtIn?.category || "其他";
}

function scoreConfidence(item) {
  let score = 0;
  if (item.amount > 0) score += 35;
  if (item.merchant && item.merchant !== "未知商户") score += 25;
  if (item.account && item.account !== "未识别账户") score += 15;
  if (item.category && item.category !== "其他") score += 15;
  if (item.note) score += 5;
  if (item.occurredAt) score += 10;
  return Math.min(score, 98);
}

function createId() {
  return globalThis.crypto?.randomUUID?.() || `txn-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
