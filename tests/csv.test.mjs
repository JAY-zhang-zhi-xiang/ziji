import assert from "node:assert/strict";
import { CSV_HEADER, formatCsvTime, parseCsvTime, parseLedgerCsv, tokenizeCsv } from "../csv.js";

let checks = 0;
function check(name, actual, expected) {
  checks += 1;
  assert.deepEqual(actual, expected, name);
}

// --- 时间格式：两端必须互相认得 ---
{
  // 手机端导出的标准格式
  check("标准格式", formatCsvTime(new Date(2026, 9, 17, 12, 31, 5).toISOString()), "2026-10-17 12:31:05");
  // 网页端以前用 toLocaleString，产出的是斜杠、无秒——手机端一行都导不进去
  check("斜杠格式", parseCsvTime("2026/10/17 12:31")?.getTime(), new Date(2026, 9, 17, 12, 31).getTime());
  check("中文年月日", parseCsvTime("2026年10月17日 12:31")?.getTime(), new Date(2026, 9, 17, 12, 31).getTime());
  check("非法日期", parseCsvTime("2026-02-30 09:00"), null);
  check("纯时间不是日期", parseCsvTime("12:31"), null);
}

// --- 手机端 8 列格式 ---
{
  const csv = "时间,收支,金额,商户,账户,分类,用途,原始通知\n"
    + "2026-10-17 12:31:05,支出,32.00,瑞幸咖啡,微信,餐饮,生椰拿铁,微信支付\n"
    + "2026-10-16 09:05:00,收入,15000.00,某某公司,银行卡,工资,9 月工资,\n";
  const result = parseLedgerCsv(csv);
  check("手机格式行数", result.rows.length, 2);
  check("手机格式无错误", result.error, null);
  check("手机格式金额", result.rows[0].amount, 32);
  check("手机格式方向", result.rows[0].direction, "expense");
  check("手机格式用途", result.rows[0].note, "生椰拿铁");
  check("手机格式收入", result.rows[1].direction, "income");
}

// --- 网页端 7 列格式（真实产出：斜杠、无秒） ---
{
  const csv = "时间,方向,金额,商户,分类,账户,来源/备注\n"
    + "\"2026/10/17 12:31\",\"支出\",\"32\",\"星巴克\",\"餐饮\",\"微信\",\"支付宝\"\n";
  const result = parseLedgerCsv(csv);
  check("网页格式行数", result.rows.length, 1);
  check("网页格式金额", result.rows[0].amount, 32);
  check("网页格式商户", result.rows[0].merchant, "星巴克");
  check("网页格式分类", result.rows[0].category, "餐饮");
  check("网页格式账户", result.rows[0].account, "微信");
}

// --- 缺方向列：整份拒绝，不能把支出导成收入 ---
{
  const result = parseLedgerCsv("时间,金额,商户\n2026-10-17 12:31:05,32.00,星巴克\n");
  assert.ok(result.error && (result.error.includes("收支") || result.error.includes("方向")), "缺方向列要报错");
  check("缺方向列不产出", result.rows.length, 0);
  checks += 1;
}

// --- 方向值只认支出/收入 ---
{
  const csv = "时间,收支,金额,商户,账户,分类,用途,原始通知\n"
    + "2026-10-17 12:31:05,支出,32.00,正常,微信,餐饮,,\n"
    + "2026-10-17 12:32:05,,18.00,空方向,微信,餐饮,,\n"
    + "2026-10-17 12:33:05,转账,20.00,转账行,微信,餐饮,,\n";
  const result = parseLedgerCsv(csv);
  check("只收明确方向的", result.rows.length, 1);
  check("空值与转账计入 invalid", result.invalid, 2);
}

// --- 去重：同一份文件导两次不翻倍 ---
{
  const csv = "时间,收支,金额,商户,账户,分类,用途,原始通知\n"
    + "2026-10-17 12:31:05,支出,32.00,瑞幸咖啡,微信,餐饮,,\n";
  const first = parseLedgerCsv(csv);
  const second = parseLedgerCsv(csv, { existing: first.rows });
  check("第一次导入", first.rows.length, 1);
  check("第二次判重", second.duplicates, 1);
  check("第二次不新增", second.rows.length, 0);
}

// --- 引号内的逗号与换行不能被当成分隔符 ---
{
  const csv = "时间,收支,金额,商户,账户,分类,用途,原始通知\n"
    + "2026-10-17 12:31:05,支出,12.00,\"A,B 便利店\",微信,购物,\"买了 A,B\n两件\",\n";
  const rows = tokenizeCsv(csv);
  check("引号内换行仍是一行", rows.length, 2);
  const result = parseLedgerCsv(csv);
  check("商户内逗号", result.rows[0].merchant, "A,B 便利店");
  check("用途内换行", result.rows[0].note, "买了 A,B\n两件");
}

// --- 防公式前缀要还原 ---
{
  const csv = "时间,收支,金额,商户,账户,分类,用途,原始通知\n"
    + "2026-10-17 12:31:05,支出,5.00,'+86 便利店,微信,购物,,\n";
  const result = parseLedgerCsv(csv);
  check("去掉防公式前缀", result.rows[0].merchant, "+86 便利店");
}

// --- BOM 与空行 ---
{
  const csv = "\uFEFF" + CSV_HEADER.join(",") + "\n"
    + "2026-10-17 12:31:05,支出,32.00,瑞幸咖啡,微信,餐饮,,\n"
    + "\n"
    + "2026-10-17 12:32:05,支出,18.00,星巴克,微信,餐饮,,\n";
  const result = parseLedgerCsv(csv);
  check("BOM 不影响表头识别", result.rows.length, 2);
  check("空行被跳过", result.invalid, 0);
}

// --- 空文件与非账单 CSV ---
{
  check("空文件", parseLedgerCsv("").error, "文件是空的");
  assert.ok(parseLedgerCsv("a,b,c\n1,2,3").error, "非账单 CSV 要报错");
  checks += 1;
}

console.log(`csv tests passed: ${checks}`);
