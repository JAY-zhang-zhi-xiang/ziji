/**
 * 网页端端到端检查：真浏览器、真点击、真上传。
 *
 * 为什么需要它：单元测试只看得到 `parseLedgerCsv` 这类纯函数。
 * 「导入按钮有没有接上 change 事件」「导进来的账户是不是被悄悄归并成其他」
 * 「430px 屏上第 5 个 tab 会不会被裁掉」——这些都只存在于 DOM 与布局里，
 * 单测永远发现不了。之前「两端 CSV 互通」就是在这种盲区里各自测试通过的。
 *
 * 用法：
 *   1. 另开一个终端：npm start（默认 5199 端口）
 *   2. node scripts/verify-pwa.mjs
 *
 * 可用环境变量：
 *   APP_URL=http://127.0.0.1:5199/  被测地址
 *   CHROME_PATH=...                 浏览器路径（默认自动找 Chrome / Edge / Chromium）
 *   KEEP_PROFILE=1                  保留临时 profile，便于复查
 */
import { spawn } from "node:child_process";
import { mkdtemp, rm, writeFile, access } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const APP_URL = process.env.APP_URL || "http://127.0.0.1:5199/";
const PORT = Number(process.env.CDP_PORT || 9333);
const KEEP_PROFILE = process.env.KEEP_PROFILE === "1";

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
  "/usr/bin/chromium-browser"
].filter(Boolean);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function findChrome() {
  for (const path of CHROME_CANDIDATES) {
    if (existsSync(path)) return path;
  }
  return null;
}

class Cdp {
  constructor(url) {
    this.url = url;
    this.nextId = 0;
    this.pending = new Map();
    this.events = new Map();
  }

  async open() {
    this.socket = new WebSocket(this.url);
    await new Promise((resolve, reject) => {
      this.socket.addEventListener("open", resolve, { once: true });
      this.socket.addEventListener("error", reject, { once: true });
    });
    this.socket.addEventListener("message", (event) => {
      const data = JSON.parse(event.data);
      const pending = data.id ? this.pending.get(data.id) : null;
      if (pending) {
        this.pending.delete(data.id);
        if (data.error) pending.reject(new Error(`${data.error.message} (${data.error.code})`));
        else pending.resolve(data.result);
        return;
      }
      for (const handler of this.events.get(data.method) || []) handler(data.params);
    });
  }

  send(method, params = {}, timeout = 15000) {
    this.nextId += 1;
    const id = this.nextId;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.socket.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (!this.pending.has(id)) return;
        this.pending.delete(id);
        reject(new Error(`${method} 超时`));
      }, timeout);
    });
  }

  on(method, handler) {
    if (!this.events.has(method)) this.events.set(method, []);
    this.events.get(method).push(handler);
  }

  async evaluate(expression) {
    const result = await this.send("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
      userGesture: true
    });
    if (result.exceptionDetails) {
      const detail = result.exceptionDetails;
      throw new Error(`页面异常：${detail.text} ${detail.exception ? detail.exception.description : ""}`);
    }
    return result.result.value;
  }

  click(selector) {
    return this.evaluate(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      if (!el) return "MISSING";
      el.click();
      return "OK";
    })()`);
  }

  /** 把 CSV 文本塞进 file input 并触发 change，等价于用户选文件。 */
  upload(csvText, filename) {
    return this.evaluate(`(() => {
      const input = document.getElementById("importCsv");
      if (!input) return "MISSING_INPUT";
      const file = new File([${JSON.stringify(csvText)}], ${JSON.stringify(filename)}, { type: "text/csv" });
      const transfer = new DataTransfer();
      transfer.items.add(file);
      input.files = transfer.files;
      input.dispatchEvent(new Event("change", { bubbles: true }));
      return "OK";
    })()`);
  }

  async screenshot(filePath) {
    const { data } = await this.send("Page.captureScreenshot", { format: "png" });
    await writeFile(filePath, Buffer.from(data, "base64"));
  }
}

const results = [];
const check = (label, ok, detail = "") => {
  results.push({ ok, label, detail });
  console.log(`${ok ? "PASS" : "FAIL"} ${label}${detail ? ` — ${detail}` : ""}`);
};

async function waitForDevtools() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      if (response.ok) return;
    } catch {
      // 还没起来
    }
    await sleep(250);
  }
  throw new Error("DevTools 端口没有就绪");
}

const ANDROID_CSV = [
  "时间,收支,金额,商户,账户,分类,用途,原始通知",
  "2026-10-15 12:31:05,支出,32.00,星巴克,零钱,餐饮,,微信支付 星巴克 32.00",
  "2026-10-16 09:05:00,支出,8.50,地铁,零钱,交通,通勤,支付宝 地铁 8.50",
  "2026-10-16 18:20:00,收入,12000.00,工资,银行卡,收入,10 月工资,银行入账 12000.00"
].join("\n");

const LEGACY_PWA_CSV = [
  "时间,方向,金额,商户,分类,账户,来源/备注",
  "2026/10/17 12:31,支出,18.00,便利店,日用,零钱,"
].join("\n");

const NO_DIRECTION_CSV = "时间,金额,商户\n2026-10-17 10:00:00,10.00,测试";

const chromePath = findChrome();
if (!chromePath) {
  console.error("找不到 Chrome / Edge。用 CHROME_PATH 环境变量指定浏览器路径。");
  process.exit(2);
}

const profileDir = await mkdtemp(join(tmpdir(), "ziji-verify-"));
const chrome = spawn(chromePath, [
  "--headless=new",
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profileDir}`,
  "--no-first-run",
  "--no-default-browser-check",
  "about:blank"
], { stdio: "ignore" });

let cdp = null;
try {
  await waitForDevtools();
  const target = await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: "PUT" }).then((r) => r.json());
  cdp = new Cdp(target.webSocketDebuggerUrl);
  await cdp.open();
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");

  const consoleErrors = [];
  cdp.on("Runtime.consoleAPICalled", (event) => {
    if (event.type === "error") {
      consoleErrors.push(event.args.map((arg) => arg.value || arg.description || arg.type).join(" "));
    }
  });
  cdp.on("Runtime.exceptionThrown", (event) => {
    const detail = event.exceptionDetails;
    consoleErrors.push(`EXCEPTION ${detail.exception ? detail.exception.description : detail.text}`);
  });
  // 导入确认用的是 confirm()：原生对话框会阻塞渲染进程，必须显式应答
  cdp.on("Page.javascriptDialogOpening", async () => {
    try {
      await cdp.send("Page.handleJavaScriptDialog", { accept: true });
    } catch {
      // 已关闭
    }
  });

  // 430 × 932：常见手机视口。注意 headless 的 --window-size 在 Windows 上会被钳到 512，
  // 所以必须用设备模拟，否则测出来的「裁切」其实是截图工具的锅。
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: 430, height: 932, deviceScaleFactor: 2, mobile: true
  });
  await cdp.send("Page.navigate", { url: APP_URL });
  await sleep(2200);

  const boot = await cdp.evaluate(`(() => {
    const tabs = [...document.querySelectorAll(".tab-button")];
    const category = document.getElementById("manualCategory");
    return {
      innerWidth: innerWidth,
      tabCount: tabs.length,
      lastTabRight: tabs.length ? Math.round(tabs[tabs.length - 1].getBoundingClientRect().right) : -1,
      active: (tabs.find((b) => b.classList.contains("active")) || {}).dataset?.tab,
      categories: category ? category.options.length : -1,
      importCsv: Boolean(document.getElementById("importCsv")),
      overflow: document.documentElement.scrollWidth > innerWidth
    };
  })()`);
  check("430px：底部 5 个 tab 都在屏内", boot.tabCount === 5 && boot.lastTabRight <= boot.innerWidth, `lastTabRight=${boot.lastTabRight}/${boot.innerWidth}`);
  check("430px：没有横向溢出", boot.overflow === false);
  check("JS 初始化完成（分类下拉已填充）", boot.categories >= 24, `options=${boot.categories}`);
  check("导入 CSV 入口存在", boot.importCsv === true);
  check("初始停在收件箱", boot.active === "home", String(boot.active));

  await cdp.click('.tab-button[data-tab="setup"]');
  await sleep(400);
  const setup = await cdp.evaluate(`(() => {
    const input = document.getElementById("importCsv");
    const label = input.closest("label");
    const rect = label.getBoundingClientRect();
    const active = [...document.querySelectorAll(".tab-button")].find((b) => b.classList.contains("active"));
    return { active: active ? active.dataset.tab : null, visible: rect.width > 0 && rect.height > 0 };
  })()`);
  check("点击可以切到「自动化」", setup.active === "setup", String(setup.active));
  check("「导入 CSV」按钮可见", setup.visible === true);

  await cdp.upload(ANDROID_CSV, "android-export.csv");
  await sleep(1600);
  const afterAndroid = await cdp.evaluate(`(() => {
    const list = document.getElementById("ledgerList");
    return { rows: list.children.length, text: list.textContent };
  })()`);
  check("手机端导出的 8 列 CSV 能导入", afterAndroid.rows >= 3 && afterAndroid.text.includes("星巴克"), `rows=${afterAndroid.rows}`);

  await cdp.upload(LEGACY_PWA_CSV, "legacy-pwa.csv");
  await sleep(1500);
  const afterLegacy = await cdp.evaluate(`(() => {
    const list = document.getElementById("ledgerList");
    return { rows: list.children.length, text: list.textContent };
  })()`);
  check("旧版网页 CSV（斜杠时间）能导入", afterLegacy.rows > afterAndroid.rows && afterLegacy.text.includes("便利店"), `rows=${afterLegacy.rows}`);

  await cdp.upload(ANDROID_CSV, "android-export.csv");
  await sleep(1500);
  const afterRepeat = await cdp.evaluate("document.querySelectorAll('#ledgerList > *').length");
  check("同一份文件重复导入不重复入账", afterRepeat === afterLegacy.rows, `rows=${afterRepeat}`);

  await cdp.upload(NO_DIRECTION_CSV, "no-direction.csv");
  await sleep(1200);
  const afterBad = await cdp.evaluate("document.querySelectorAll('#ledgerList > *').length");
  check("缺「收支」列整份拒绝，账本不变", afterBad === afterRepeat, `rows=${afterBad}`);

  await cdp.click('.tab-button[data-tab="ledger"]');
  await sleep(600);
  const ledger = await cdp.evaluate(`(() => {
    const list = document.getElementById("ledgerList");
    const text = list.textContent;
    return {
      rows: list.children.length,
      keepsCash: text.includes("零钱"),
      keepsBank: text.includes("银行卡"),
      caption: text.replace(/\\s+/g, " ").slice(0, 120)
    };
  })()`);
  check("账本页展示导入的记录", ledger.rows >= 4, `rows=${ledger.rows}`);
  check("账户归属保留（零钱 / 银行卡没被归并成「其他」）", ledger.keepsCash && ledger.keepsBank, ledger.caption);

  await cdp.click('.tab-button[data-tab="rules"]');
  await sleep(500);
  const categories = await cdp.evaluate("document.querySelectorAll('#categoryList > *').length");
  check("分类表与手机端一致（24 项）", categories >= 24, `子项=${categories}`);

  check("控制台没有报错", consoleErrors.length === 0, consoleErrors.slice(0, 2).join(" | "));
} catch (error) {
  check("执行过程没有异常", false, error.message);
} finally {
  try {
    await cdp?.send("Browser.close", {}, 3000);
  } catch {
    // 已经关了
  }
  chrome.kill();
  if (!KEEP_PROFILE) {
    await rm(profileDir, { recursive: true, force: true }).catch(() => {});
  }
}

const failed = results.filter((item) => !item.ok);
console.log(`\n${results.length - failed.length}/${results.length} 项通过`);
process.exit(failed.length === 0 ? 0 : 1);
