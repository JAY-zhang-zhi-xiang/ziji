/**
 * 账单 CSV 的解析规则。
 *
 * 单独成模块有两个原因：
 * 1. app.js 依赖 DOM，在 node 里跑不起来，CSV 解析这段核心逻辑没法单测；
 * 2. 判据必须和 Android 的 LedgerCsvImporter 一致——两端各自实现一份，
 *    就会出现「手机能导、网页不能导」这种各自都「测试通过」的假象。
 *
 * 这里只做「文本 → 行数据」，不碰状态。补分类、解析账户 id 交给调用方。
 */

export const CSV_HEADER = ["时间", "方向", "金额", "商户", "分类", "账户", "来源/备注"];

/**
 * CSV 里的时间统一写成 yyyy-MM-dd HH:mm:ss。
 *
 * 以前用 toLocaleString("zh-CN")，产出「2026/10/17 12:31」——斜杠、没有秒。
 * 手机端按 yyyy-MM-dd HH:mm:ss 严格解析，网页导出的表一行都导不进去。
 */
export function formatCsvTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (number) => String(number).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/**
 * @param {string} text CSV 全文，可带 UTF-8 BOM
 * @param {{existing?: Array}} options existing 用于去重，只读 occurredAt/amount/direction/merchant
 * @returns {{rows: Array, duplicates: number, invalid: number, error: string|null}}
 */
export function parseLedgerCsv(text, { existing = [] } = {}) {
  const rows = tokenizeCsv(String(text || "").replace(/^\uFEFF/, ""));
  if (!rows.length) return { rows: [], duplicates: 0, invalid: 0, error: "文件是空的" };

  const header = rows[0].map((cell) => String(cell || "").trim());
  const timeColumn = columnIndex(header, ["时间"]);
  const amountColumn = columnIndex(header, ["金额"]);
  const directionColumn = columnIndex(header, ["收支", "方向"]);
  if (timeColumn < 0 || amountColumn < 0) {
    return { rows: [], duplicates: 0, invalid: 0, error: "这不是账单 CSV：第一行缺少「时间」或「金额」列" };
  }
  // 没有方向列时不能猜：整份文件按收入导入，金额分毫不差，但每笔钱的方向都反了。
  if (directionColumn < 0) {
    return { rows: [], duplicates: 0, invalid: 0, error: "缺少「收支」（或「方向」）列，无法判断支出还是收入" };
  }
  const merchantColumn = columnIndex(header, ["商户"]);
  const accountColumn = columnIndex(header, ["账户"]);
  const categoryColumn = columnIndex(header, ["分类"]);
  const noteColumn = columnIndex(header, ["用途", "备注", "来源/备注"]);

  const seen = new Set((existing || []).map((item) =>
    csvDedupeKey(item.occurredAt, item.amount, item.direction, item.merchant)));
  const parsed = [];
  let duplicates = 0;
  let invalid = 0;

  for (let index = 1; index < rows.length; index += 1) {
    const row = rows[index];
    if (!row || row.every((cell) => !String(cell || "").trim())) continue;
    const cell = (column) => (column >= 0 && column < row.length ? String(row[column] || "").trim() : "");

    const occurredAt = parseCsvTime(cell(timeColumn));
    if (!occurredAt) {
      invalid += 1;
      continue;
    }
    const amount = Number(cell(amountColumn).replace(/[^0-9.\-]/g, ""));
    if (!Number.isFinite(amount) || amount <= 0) {
      invalid += 1;
      continue;
    }
    const directionText = cell(directionColumn);
    const direction = directionText === "支出" ? "expense" : directionText === "收入" ? "income" : null;
    // 「转账」在 Android 账本里没有对应概念，空值说明文件不完整——都计入 invalid，不硬塞。
    if (!direction) {
      invalid += 1;
      continue;
    }

    const merchant = unescapeCsvCell(cell(merchantColumn)) || "未知商户";
    const account = unescapeCsvCell(cell(accountColumn)) || "其他";
    const category = unescapeCsvCell(cell(categoryColumn)) || "其他";
    const note = unescapeCsvCell(cell(noteColumn));

    const key = csvDedupeKey(occurredAt, amount, direction, merchant);
    if (seen.has(key)) {
      duplicates += 1;
      continue;
    }
    seen.add(key);

    parsed.push({
      occurredAt: occurredAt.toISOString(),
      amount,
      direction,
      merchant,
      account,
      category,
      note
    });
  }
  return { rows: parsed, duplicates, invalid, error: null };
}

/** 极简 CSV 分词：双引号包裹、引号内逗号与换行、"" 表示一个引号。 */
export function tokenizeCsv(text) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === '"') {
        if (text[index + 1] === '"') {
          field += '"';
          index += 1;
        } else {
          quoted = false;
        }
      } else {
        field += char;
      }
      continue;
    }
    if (char === '"') quoted = true;
    else if (char === ",") {
      row.push(field);
      field = "";
    } else if (char === "\n") {
      row.push(field);
      field = "";
      rows.push(row);
      row = [];
    } else if (char !== "\r") {
      field += char;
    }
  }
  if (field.length || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

function columnIndex(header, names) {
  for (const name of names) {
    const index = header.indexOf(name);
    if (index >= 0) return index;
  }
  return -1;
}

/** 支持 2026-10-17 12:31(:05)、2026/10/17 12:31、2026年10月17日 12:31。 */
export function parseCsvTime(value) {
  const text = String(value || "").trim();
  const match = text.match(/^(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})日?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!match) return null;
  const [, year, month, day, hour, minute, second] = match;
  const date = new Date(
    Number(year), Number(month) - 1, Number(day),
    Number(hour), Number(minute), Number(second || 0)
  );
  if (Number.isNaN(date.getTime())) return null;
  // 2 月 30 日会被 Date 顺延成 3 月 2 日，反查一次挡掉。
  if (date.getMonth() !== Number(month) - 1 || date.getDate() !== Number(day)) return null;
  return date;
}

export function csvDedupeKey(occurredAt, amount, direction, merchant) {
  const seconds = Math.floor(new Date(occurredAt).getTime() / 1000);
  const cents = Math.round(Number(amount) * 100);
  return `${seconds}|${cents}|${direction}|${String(merchant || "").trim()}`;
}

/** 还原导出时加的防公式前缀（'=、'+、'-、'@）。 */
export function unescapeCsvCell(value) {
  const text = String(value || "");
  return /^'[=+\-@]/.test(text) ? text.slice(1) : text;
}
