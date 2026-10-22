import { CATEGORIES, parseTransaction as parseBillTransaction, splitImportText } from "./parser.js?v=15";
import { computeRefundOffset } from "./refund.js?v=2";
import { CSV_HEADER, formatCsvTime, parseLedgerCsv } from "./csv.js?v=1";
import {
  bothExplicitSources,
  isExplicitSource,
  isPossibleDuplicatePair,
  isReliableMerchant,
  isSamePayment,
  mergeDuplicate,
  partitionPossibleDuplicates,
  POSSIBLE_DUPLICATE_WINDOW_MILLIS,
  sameMerchant
} from "./dedupe.js?v=4";

const STORAGE_KEY = "ziji-ledger-v1";
const BRIDGE_TOKEN_KEY = "ziji-bridge-token-v1";
const DEFAULT_ACCOUNT_NAMES = ["微信", "支付宝", "银行卡", "现金", "其他"];
const STORAGE_DB = "ziji-ledger-storage";
/** 分类表的版本：2 = 与 Android 的 22 个支出分类对齐。只在升级时补一次。 */
const CATEGORY_SCHEMA_VERSION = 2;

const CATEGORY_ICON = {
  "餐饮": "icon-cat-exp-utensils-crossed",
  "娱乐": "icon-cat-exp-gamepad-2",
  "购物": "icon-cat-exp-shopping-bag",
  "穿搭": "icon-cat-exp-shirt",
  "零食": "icon-cat-exp-cookie",
  "交通": "icon-cat-exp-bus-front",
  "日用": "icon-cat-exp-spray-can",
  "人情": "icon-cat-exp-gift",
  "宠物": "icon-cat-exp-paw-print",
  "美容": "icon-cat-exp-sparkles",
  "运动": "icon-cat-exp-dumbbell",
  "医疗": "icon-cat-inc-shield-check",
  "服务": "icon-cat-exp-droplets",
  "买菜": "icon-cat-exp-carrot",
  "居家": "icon-cat-exp-house",
  "住房": "icon-cat-exp-house",
  "爱车": "icon-cat-exp-car",
  "红包": "icon-cat-exp-hand-coins",
  "转账": "icon-transfer",
  "学习": "icon-cat-exp-graduation-cap",
  "教育": "icon-cat-exp-graduation-cap",
  "虚拟": "icon-cat-exp-dices",
  "烟酒": "icon-cat-exp-wine",
  "工资": "icon-cat-inc-briefcase-business",
  "兼职": "icon-cat-inc-bike",
  "投资理财": "icon-cat-inc-chart-line",
  "人情社交": "icon-cat-inc-heart-handshake",
  "奖金补贴": "icon-cat-inc-trophy",
  "报销": "icon-cat-inc-receipt-text",
  "生意": "icon-cat-inc-store",
  "卖二手": "icon-cat-inc-tag",
  "生活费": "icon-cat-inc-hand-coins",
  "中奖": "icon-cat-inc-ticket",
  "收红包": "icon-cat-inc-gift",
  "收转账": "icon-cat-inc-arrow-down-left",
  "保险理赔": "icon-cat-inc-shield-check",
  "退款": "icon-cat-inc-rotate-ccw",
  "收入": "icon-wallet",
  "管理": "icon-cat-inc-settings",
  "其他": "icon-cat-inc-circle-ellipsis"
};

let state = migrate({});
let bridgeToken = "";
let storageReadFailed = false;
let activeTab = "home";
let activeFilter = "全部";
let directionFilter = "all";
let ledgerQuery = "";
let toastTimer = null;
let toastDeadline = 0;
let toastRemaining = 0;
let autoBridgeConnected = false;
let editModalTarget = null;
let editingTransferId = null;
let lastFocusedElement = null;
let reportMonthOffset = 0;
let persistChain = Promise.resolve();
const MAX_DUPLICATE_CANDIDATES = 500;
const announcedBridgeIssues = new Set();
const unparsedBridgeCaptureIds = new Set();
const deferredBridgeCaptureIds = new Set();

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));
const money = new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY" });

const elements = {
  restoreJson: $("#restoreJson"),
  importCsv: $("#importCsv"),
  ledgerList: $("#ledgerList"),
  monthSpend: $("#monthSpend"),
  todaySpend: $("#todaySpend"),
  topCategory: $("#topCategory"),
  autoRate: $("#autoRate"),
  budgetHint: $("#budgetHint"),
  budgetInput: $("#budgetInput"),
  budgetBar: $("#budgetBar"),
  budgetCopy: $("#budgetCopy"),
  categoryChart: $("#categoryChart"),
  categoryFilters: $("#categoryFilters"),
  directionFilters: $("#directionFilters"),
  ledgerSearch: $("#ledgerSearch"),
  ruleKeyword: $("#ruleKeyword"),
  ruleCategory: $("#ruleCategory"),
  ruleList: $("#ruleList"),
  accountList: $("#accountList"),
  newAccountName: $("#newAccountName"),
  newAccountBalance: $("#newAccountBalance"),
  addAccount: $("#addAccount"),
  categoryList: $("#categoryList"),
  newCategoryName: $("#newCategoryName"),
  addCategory: $("#addCategory"),
  transferFrom: $("#transferFrom"),
  transferTo: $("#transferTo"),
  transferAmount: $("#transferAmount"),
  transferNote: $("#transferNote"),
  saveTransfer: $("#saveTransfer"),
  cancelTransferEdit: $("#cancelTransferEdit"),
  manualEntryForm: $("#manualEntryForm"),
  manualAmount: $("#manualAmount"),
  manualMerchant: $("#manualMerchant"),
  manualCategory: $("#manualCategory"),
  manualAccount: $("#manualAccount"),
  manualOccurredAt: $("#manualOccurredAt"),
  manualNote: $("#manualNote"),
  reportPeriodLabel: $("#reportPeriodLabel"),
  reportPrevMonth: $("#reportPrevMonth"),
  reportNextMonth: $("#reportNextMonth"),
  reportExpense: $("#reportExpense"),
  reportIncome: $("#reportIncome"),
  reportNet: $("#reportNet"),
  reportMeta: $("#reportMeta"),
  reportCategoryChart: $("#reportCategoryChart"),
  reportTrend: $("#reportTrend"),
  reportNetWorth: $("#reportNetWorth"),
  reportBalances: $("#reportBalances"),
  toast: $("#toast"),
  syncState: $("#syncState"),
  openAutomation: $("#openAutomation"),
  autopilotCallout: $("#autopilotCallout"),
  autopilotTitle: $("#autopilotTitle"),
  autopilotCopy: $("#autopilotCopy")
};

init();

async function init() {
  try {
    state = await loadState();
  } catch (error) {
    // 账本读不出来：进入只读安全模式，不渲染空账本、不落盘，避免覆盖原数据。
    // 用户能看到明确提示，而不是看到空账本误以为数据丢了、再一保存真丢了。
    storageReadFailed = true;
    state = migrate({});
    renderStorageFailure();
    return;
  }
  bridgeToken = await loadBridgeToken();
  await hydrateBridgeToken();
  registerServiceWorker();
  startAutoCaptureBridge();
  wireEvents();
  initEditModal();
  render();
}

function renderStorageFailure() {
  document.body.innerHTML = "";
  const box = document.createElement("div");
  box.style.cssText =
    "max-width:420px;margin:18vh auto 0;padding:28px 24px;background:var(--paper-light);" +
    "border:1px solid var(--line-strong);border-radius:16px;font-family:var(--font-sans);" +
    "color:var(--ink);text-align:left;";
  box.innerHTML =
    '<div style="font-size:17px;font-weight:700;margin-bottom:12px;">账本数据读取失败</div>' +
    '<div style="font-size:14px;line-height:1.7;color:var(--ink-soft);margin-bottom:16px;">' +
    "浏览器里保存的账本没有成功读出来。这通常是存储空间被清理、隐私模式限制，或数据损坏导致的。" +
    "为避免覆盖，本次已停止自动保存。<strong>请先尝试恢复：</strong>刷新页面，或通过「自动化 → 本地数据」" +
    "里的备份/导入功能找回数据。</div>" +
    '<div style="font-size:13px;color:var(--ink-faint);">你的原数据仍保存在本机，没有被删除。</div>';
  document.body.appendChild(box);
}

async function hydrateBridgeToken() {
  const params = new URLSearchParams(location.search);
  const token = params.get("bridgeToken") || params.get("token");
  if (!token) return;

  bridgeToken = token;
  await writeStorage(BRIDGE_TOKEN_KEY, token);
  params.delete("bridgeToken");
  params.delete("token");
  const nextUrl = `${location.pathname}${params.toString() ? `?${params.toString()}` : ""}${location.hash}`;
  history.replaceState({}, document.title, nextUrl);
}

function createDefaultAccounts() {
  return DEFAULT_ACCOUNT_NAMES.map((name, index) => ({
    id: crypto.randomUUID(),
    name,
    sourceTag: name,
    type: "other",
    startingBalance: 0,
    archived: false,
    order: index,
    createdAt: new Date().toISOString()
  }));
}

function createDefaultCategories() {
  return CATEGORIES.map((name, index) => ({
    id: crypto.randomUUID(),
    name,
    builtin: true,
    order: index
  }));
}

/** 补齐缺失的内置分类；用户自建的分类原样保留，重复名字不重复添加。 */
function ensureBuiltinCategories(categories) {
  const list = Array.isArray(categories) ? [...categories] : [];
  const known = new Set(list.map((category) => category && category.name).filter(Boolean));
  let order = list.reduce((max, category) => Math.max(max, Number(category.order) || 0), 0);
  for (const name of CATEGORIES) {
    if (known.has(name)) continue;
    order += 1;
    list.push({ id: crypto.randomUUID(), name, builtin: true, order });
    known.add(name);
  }
  return list;
}

function migrate(parsed) {
  const defaults = {
    version: 4,
    entries: [],
    pending: [],
    duplicateCandidates: [],
    userRules: [],
    accounts: createDefaultAccounts(),
    categories: createDefaultCategories(),
    settings: {
      monthlyBudget: 0,
      autoConfirm: true,
      autoConfirmConfidence: 85
    },
    stats: {
      parsedCount: 0,
      acceptedCount: 0,
      manualCount: 0
    }
  };

  const merged = {
    ...defaults,
    ...parsed,
    settings: { ...defaults.settings, ...(parsed.settings || {}) },
    stats: { ...defaults.stats, ...(parsed.stats || {}) }
  };
  merged.duplicateCandidates = Array.isArray(merged.duplicateCandidates) ? merged.duplicateCandidates : [];

  if (Number(parsed.version || 0) < 4 && parsed.settings && parsed.settings.autoConfirm === false) {
    merged.settings.autoConfirm = true;
    merged.settings.autoConfirmConfidence = 85;
  }

  merged.categories = Array.isArray(parsed.categories) && parsed.categories.length ? parsed.categories : defaults.categories;
  // 只补一次：老账本的分类表里缺少手机端那 11 个支出分类，不补齐的话，
  // 从手机导入的「零食」在网页上选不到、也不进分类图表。
  // 用版本号守着，是因为补完就不能再补——否则用户主动删掉的分类下次开页又冒出来。
  if (Number(merged.settings.categorySchema || 0) < CATEGORY_SCHEMA_VERSION) {
    merged.categories = ensureBuiltinCategories(merged.categories);
    merged.settings.categorySchema = CATEGORY_SCHEMA_VERSION;
  }

  if (Array.isArray(parsed.accounts) && parsed.accounts.length) {
    merged.accounts = parsed.accounts;
  } else {
    const accounts = defaults.accounts;
    const knownTags = new Set(accounts.map((a) => a.sourceTag));
    let order = accounts.length;

    for (const item of [...merged.entries, ...merged.pending]) {
      const tag = item.account;
      if (!tag || knownTags.has(tag)) continue;
      knownTags.add(tag);
      accounts.push({
        id: crypto.randomUUID(),
        name: tag,
        sourceTag: tag,
        type: "other",
        startingBalance: 0,
        archived: false,
        order: order++,
        createdAt: new Date().toISOString()
      });
    }

    merged.accounts = accounts;
    merged.entries = merged.entries.map((item) =>
      item.accountId ? item : { ...item, accountId: resolveAccountIdFrom(item.account, accounts) }
    );
    merged.pending = merged.pending.map((item) =>
      item.accountId ? item : { ...item, accountId: resolveAccountIdFrom(item.account, accounts) }
    );
  }

  // Older versions held low-confidence captures in a confirmation queue. Automatic
  // capture is now direct-to-ledger, so promote those legacy rows once and clear it.
  const legacyPending = Array.isArray(merged.pending) ? merged.pending : [];
  let promoted = 0;
  for (const item of legacyPending) {
    const duplicate = merged.entries.some((entry) =>
      entry.id === item.id || (item.duplicateOf && entry.id === item.duplicateOf)
    );
    if (duplicate) continue;
    merged.entries.push({
      ...item,
      confirmedAt: item.confirmedAt || item.capturedAt || new Date().toISOString(),
      autoConfirmed: true
    });
    promoted += 1;
  }
  merged.pending = [];
  if (promoted > 0) {
    merged.stats.parsedCount = Math.max(Number(merged.stats.parsedCount || 0), merged.entries.length);
    merged.stats.acceptedCount = Number(merged.stats.acceptedCount || 0) + promoted;
  }

  merged.version = 4;
  return merged;
}

function resolveAccountIdFrom(name, accounts) {
  const bySourceTag = name && accounts.find((a) => a.sourceTag === name);
  if (bySourceTag) return bySourceTag.id;
  const byName = name && accounts.find((a) => a.name === name);
  if (byName) return byName.id;
  const fallback = accounts.find((a) => a.sourceTag === "其他") || accounts[0];
  return fallback ? fallback.id : null;
}

function resolveAccountId(name) {
  return resolveAccountIdFrom(name, state.accounts);
}

function resolveAccountName(item) {
  const account = state.accounts.find((a) => a.id === item.accountId);
  return account ? account.name : item.account;
}

function computeAccountBalances(entries, accounts) {
  const balances = new Map(accounts.map((a) => [a.id, amountToCents(a.startingBalance)]));
  for (const item of entries) {
    const cents = amountToCents(item.amount);
    if (item.direction === "income" && balances.has(item.accountId)) {
      balances.set(item.accountId, balances.get(item.accountId) + cents);
    } else if (item.direction === "expense" && balances.has(item.accountId)) {
      balances.set(item.accountId, balances.get(item.accountId) - cents);
    } else if (item.direction === "transfer") {
      if (balances.has(item.fromAccountId)) {
        balances.set(item.fromAccountId, balances.get(item.fromAccountId) - cents);
      }
      if (balances.has(item.toAccountId)) {
        balances.set(item.toAccountId, balances.get(item.toAccountId) + cents);
      }
    }
  }
  return accounts.map((a) => ({ ...a, balance: (balances.get(a.id) ?? amountToCents(a.startingBalance)) / 100 }));
}

function categoryIconId(category) {
  return CATEGORY_ICON[category] || "icon-cat-other";
}

function resolveAccountNameById(id) {
  const account = state.accounts.find((a) => a.id === id);
  return account ? account.name : "未知账户";
}

function renderTransferForm() {
  const accounts = [...state.accounts].sort((a, b) => a.order - b.order);
  const options = accounts
    .map((a) => `<option value="${escapeHtml(a.id)}">${escapeHtml(a.name)}${a.archived ? "（已归档）" : ""}</option>`)
    .join("");
  const prevFrom = elements.transferFrom.value;
  const prevTo = elements.transferTo.value;
  elements.transferFrom.innerHTML = options;
  elements.transferTo.innerHTML = options;
  if (accounts.some((a) => a.id === prevFrom)) elements.transferFrom.value = prevFrom;
  if (accounts.some((a) => a.id === prevTo)) elements.transferTo.value = prevTo;
  elements.saveTransfer.textContent = editingTransferId ? "保存修改" : "转账";
  elements.cancelTransferEdit.hidden = !editingTransferId;
}

function renderManualEntryForm() {
  const categories = [...state.categories].sort((a, b) => a.order - b.order);
  const accounts = [...state.accounts].filter((account) => !account.archived).sort((a, b) => a.order - b.order);
  const previousCategory = elements.manualCategory.value;
  const previousAccount = elements.manualAccount.value;

  elements.manualCategory.innerHTML = categories
    .map((category) => `<option value="${escapeHtml(category.name)}">${escapeHtml(category.name)}</option>`)
    .join("");
  elements.manualAccount.innerHTML = accounts
    .map((account) => `<option value="${escapeHtml(account.id)}">${escapeHtml(account.name)}</option>`)
    .join("");

  if (categories.some((category) => category.name === previousCategory)) {
    elements.manualCategory.value = previousCategory;
  }
  if (accounts.some((account) => account.id === previousAccount)) {
    elements.manualAccount.value = previousAccount;
  }

  if (!elements.manualOccurredAt.value) {
    const now = new Date();
    now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
    elements.manualOccurredAt.value = now.toISOString().slice(0, 16);
  }
}

function resetManualEntryForm() {
  elements.manualEntryForm.reset();
  renderManualEntryForm();
}

function saveManualEntry(event) {
  event.preventDefault();
  const direction = elements.manualEntryForm.querySelector("input[name='manualDirection']:checked")?.value || "expense";
  const amount = Number(elements.manualAmount.value);
  const merchant = elements.manualMerchant.value.trim();
  const category = elements.manualCategory.value;
  const accountId = elements.manualAccount.value;
  const occurredAt = new Date(elements.manualOccurredAt.value);
  const note = elements.manualNote.value.trim();
  const account = state.accounts.find((item) => item.id === accountId);
  const normalizedCents = amountToCents(amount);

  if (!Number.isFinite(amount) || amount <= 0 || normalizedCents <= 0) {
    showToast("请输入至少 0.01 元的有效金额。", "error");
    elements.manualAmount.focus();
    return;
  }
  if (!merchant) {
    showToast("请填写商户或收入来源。", "error");
    elements.manualMerchant.focus();
    return;
  }
  if (!category || !state.categories.some((item) => item.name === category)) {
    showToast("请选择有效分类。", "error");
    return;
  }
  if (!account || account.archived) {
    showToast("请选择有效账户。", "error");
    return;
  }
  if (Number.isNaN(occurredAt.getTime())) {
    showToast("请选择有效日期时间。", "error");
    elements.manualOccurredAt.focus();
    return;
  }

  const normalizedAmount = normalizedCents / 100;
  const timestamp = occurredAt.toISOString();
  const entry = {
    id: crypto.randomUUID(),
    source: "manual",
    amount: normalizedAmount,
    direction,
    merchant,
    account: account.sourceTag || account.name,
    accountId,
    category,
    note,
    occurredAt: timestamp,
    confidence: 100,
    autoParsed: false,
    manual: true,
    createdAt: new Date().toISOString(),
    confirmedAt: new Date().toISOString()
  };
  state.entries.unshift(entry);
  state.stats.manualCount = (state.stats.manualCount || 0) + 1;
  saveState();
  resetManualEntryForm();
  showToast("已记账，账单已进入账本。", "success", {
    duration: 6500,
    onUndo: () => {
      const index = state.entries.findIndex((item) => item.id === entry.id);
      if (index >= 0) state.entries.splice(index, 1);
      state.stats.manualCount = Math.max((state.stats.manualCount || 1) - 1, 0);
      saveState();
      showToast("已撤销这笔手动账单。", "success");
    }
  });
}

function submitTransfer() {
  const fromAccountId = elements.transferFrom.value;
  const toAccountId = elements.transferTo.value;
  const amount = Number(elements.transferAmount.value);
  const note = elements.transferNote.value.trim();

  if (!fromAccountId || !toAccountId || fromAccountId === toAccountId) {
    showToast("请选择两个不同的账户。", "error");
    return;
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    showToast("请输入有效的转账金额。", "error");
    return;
  }

  if (editingTransferId) {
    const item = state.entries.find((entry) => entry.id === editingTransferId);
    if (item) Object.assign(item, { fromAccountId, toAccountId, amount, note });
    editingTransferId = null;
    showToast("转账已更新。", "success");
  } else {
    state.entries.unshift({
      id: crypto.randomUUID(),
      direction: "transfer",
      fromAccountId,
      toAccountId,
      amount,
      note,
      occurredAt: new Date().toISOString(),
      createdAt: new Date().toISOString()
    });
    showToast("转账已记录。", "success");
  }

  elements.transferAmount.value = "";
  elements.transferNote.value = "";
  saveState();
}

function editTransfer(id) {
  const item = state.entries.find((entry) => entry.id === id && entry.direction === "transfer");
  if (!item) return;
  editingTransferId = id;
  renderTransferForm();
  elements.transferFrom.value = item.fromAccountId;
  elements.transferTo.value = item.toAccountId;
  elements.transferAmount.value = item.amount;
  elements.transferNote.value = item.note || "";
  elements.transferFrom.scrollIntoView({ behavior: "smooth", block: "center" });
}

function cancelTransferEditHandler() {
  editingTransferId = null;
  elements.transferAmount.value = "";
  elements.transferNote.value = "";
  renderTransferForm();
}

function deleteTransfer(id) {
  const index = state.entries.findIndex((entry) => entry.id === id);
  if (index === -1) return;
  const [removed] = state.entries.splice(index, 1);
  if (editingTransferId === id) {
    editingTransferId = null;
    elements.transferAmount.value = "";
    elements.transferNote.value = "";
  }
  saveState();
  showToast("转账已删除。", "info", {
    duration: 7000,
    onUndo: () => {
      state.entries.splice(Math.min(index, state.entries.length), 0, removed);
      saveState();
      showToast("已撤销删除。", "success");
    }
  });
}

async function loadState() {
  try {
    const stored = await readStorage(STORAGE_KEY);
    if (stored) return migrate(JSON.parse(stored));
  } catch (error) {
    // 读到东西但解析/解密失败：这是「数据损坏」而非「没有数据」，绝不能当作
    // 空账本继续跑——否则下次 saveState 会把空账本写回，覆盖掉还能抢救的原数据。
    throw new Error("ledger_read_failed");
  }

  try {
    const legacy = localStorage.getItem(STORAGE_KEY);
    if (legacy) {
      const migrated = migrate(JSON.parse(legacy));
      await writeStorage(STORAGE_KEY, JSON.stringify(migrated));
      localStorage.removeItem(STORAGE_KEY);
      return migrated;
    }
  } catch (error) {
    throw new Error("ledger_migrate_failed");
  }
  // 只有「确实没有任何数据」才返回空账本。
  return migrate({});
}

async function loadBridgeToken() {
  try {
    const stored = await readStorage(BRIDGE_TOKEN_KEY);
    if (stored) return stored;
  } catch (error) {
    console.warn("Unable to read bridge token", error);
  }
  return localStorage.getItem(BRIDGE_TOKEN_KEY) || "";
}

function saveState() {
  // 账本读取失败进入安全模式后，任何写盘都可能用空账本覆盖原数据，必须拦住。
  if (storageReadFailed) return Promise.resolve(false);
  persistChain = persistChain.then(() => persistState()).catch((error) => {
    console.error("Unable to queue ledger state", error);
    return false;
  });
  render();
  return persistChain;
}

async function persistState() {
  if (storageReadFailed) return false;
  try {
    await writeStorage(STORAGE_KEY, JSON.stringify(state));
    return true;
  } catch (error) {
    console.error("Unable to save ledger state", error);
    showToast("无法保存本地账本。请先导出备份并检查浏览器存储空间。", "error");
    return false;
  }
}

function openStorage() {
  if (!("indexedDB" in window)) return Promise.resolve(null);
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(STORAGE_DB, 1);
    request.onupgradeneeded = () => request.result.createObjectStore("kv");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("storage_open_failed"));
  });
}

async function readStorage(key) {
  const db = await openStorage();
  if (!db) return localStorage.getItem(key);
  return new Promise((resolve, reject) => {
    const request = db.transaction("kv", "readonly").objectStore("kv").get(key);
    request.onsuccess = async () => {
      try {
        resolve(request.result && typeof request.result === "object" ? await decryptStorageValue(db, request.result) : request.result || "");
      } catch (error) {
        reject(error);
      }
    };
    request.onerror = () => reject(request.error || new Error("storage_read_failed"));
  });
}

async function writeStorage(key, value) {
  const db = await openStorage();
  if (!db) {
    localStorage.setItem(key, value);
    return;
  }
  value = await encryptStorageValue(db, value);
  await new Promise((resolve, reject) => {
    const request = db.transaction("kv", "readwrite").objectStore("kv").put(value, key);
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error || new Error("storage_write_failed"));
  });
}

async function getStorageKey(db) {
  const existing = await new Promise((resolve, reject) => {
    const request = db.transaction("kv", "readonly").objectStore("kv").get("__encryption_key__");
    request.onsuccess = () => resolve(request.result || null);
    request.onerror = () => reject(request.error || new Error("storage_key_read_failed"));
  });
  if (existing) return existing;

  const key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
  await new Promise((resolve, reject) => {
    const request = db.transaction("kv", "readwrite").objectStore("kv").put(key, "__encryption_key__");
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error || new Error("storage_key_write_failed"));
  });
  return key;
}

async function encryptStorageValue(db, value) {
  if (!crypto?.subtle) return { encrypted: false, value: String(value) };
  const key = await getStorageKey(db);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encoded = new TextEncoder().encode(String(value));
  const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, encoded);
  return { encrypted: true, iv: Array.from(iv), data: Array.from(new Uint8Array(encrypted)) };
}

async function decryptStorageValue(db, record) {
  if (!record.encrypted) return String(record.value || "");
  const key = await getStorageKey(db);
  const decrypted = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: new Uint8Array(record.iv) },
    key,
    new Uint8Array(record.data)
  );
  return new TextDecoder().decode(decrypted);
}

async function removeStorage(key) {
  const db = await openStorage();
  if (!db) {
    localStorage.removeItem(key);
    return;
  }
  await new Promise((resolve, reject) => {
    const request = db.transaction("kv", "readwrite").objectStore("kv").delete(key);
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error || new Error("storage_delete_failed"));
  });
}

function wireEvents() {
  $$(".tab-button").forEach((button) => {
    button.addEventListener("click", () => switchTab(button.dataset.tab));
    button.addEventListener("keydown", handleTabKeydown);
  });

  elements.openAutomation.addEventListener("click", () => {
    switchTab("setup");
    $("#tab-setup").focus();
  });
  elements.restoreJson.addEventListener("change", (event) => restoreBackup(event.target.files?.[0]));
  elements.importCsv.addEventListener("change", (event) => importCsvFile(event.target.files?.[0]));
  $("#exportCsv").addEventListener("click", exportCsv);
  $("#exportJson").addEventListener("click", exportJson);
  $("#mergeDuplicates").addEventListener("click", mergeDuplicateEntries);
  $("#clearAll").addEventListener("click", clearAllData);
  $("#saveRule").addEventListener("click", saveUserRule);
  $("#saveBudget").addEventListener("click", saveBudget);
  elements.ledgerSearch.addEventListener("input", () => {
    ledgerQuery = elements.ledgerSearch.value.trim();
    renderLedger();
  });
  $("#duplicateReview").addEventListener("click", handleDuplicateReviewClick);

  elements.addAccount.addEventListener("click", addAccount);
  elements.accountList.addEventListener("click", handleAccountListClick);
  elements.accountList.addEventListener("focusout", handleAccountRenameCommit);
  elements.accountList.addEventListener("keydown", handleEntityInputKeydown);

  elements.addCategory.addEventListener("click", addCategory);
  elements.categoryList.addEventListener("click", handleCategoryListClick);
  elements.categoryList.addEventListener("focusout", handleCategoryRenameCommit);
  elements.categoryList.addEventListener("keydown", handleEntityInputKeydown);

  elements.saveTransfer.addEventListener("click", submitTransfer);
  elements.cancelTransferEdit.addEventListener("click", cancelTransferEditHandler);
  elements.manualEntryForm.addEventListener("submit", saveManualEntry);
  elements.reportPrevMonth.addEventListener("click", () => {
    reportMonthOffset -= 1;
    renderReports();
  });
  elements.reportNextMonth.addEventListener("click", () => {
    reportMonthOffset = Math.min(reportMonthOffset + 1, 0);
    renderReports();
  });

  elements.toast.addEventListener("mouseenter", pauseToastTimer);
  elements.toast.addEventListener("focusin", pauseToastTimer);
  elements.toast.addEventListener("mouseleave", resumeToastTimer);
  elements.toast.addEventListener("focusout", resumeToastTimer);

  $("#editConfirm").addEventListener("click", confirmEditModal);
  $("#editCancel").addEventListener("click", closeEditModal);
  $("#editDeleteEntry").addEventListener("click", deleteFromModal);
  $("#editModal").addEventListener("click", (event) => {
    if (event.target === event.currentTarget) closeEditModal();
  });
}

function handleEntityInputKeydown(event) {
  if (event.key === "Enter" && event.target.matches(".entity-name-input")) event.target.blur();
}

function switchTab(tab) {
  activeTab = tab;
  $$(".tab-button").forEach((button) => {
    const isActive = button.dataset.tab === tab;
    button.classList.toggle("active", isActive);
    button.setAttribute("aria-selected", String(isActive));
    button.tabIndex = isActive ? 0 : -1;
  });
  $$(".screen").forEach((screen) => screen.classList.toggle("active", screen.id === `tab-${tab}`));
}

function handleTabKeydown(event) {
  const tabs = $$(".tab-button");
  const current = tabs.indexOf(event.currentTarget);
  if (current < 0) return;
  let next = current;
  if (event.key === "ArrowRight" || event.key === "ArrowDown") next = (current + 1) % tabs.length;
  if (event.key === "ArrowLeft" || event.key === "ArrowUp") next = (current - 1 + tabs.length) % tabs.length;
  if (event.key === "Home") next = 0;
  if (event.key === "End") next = tabs.length - 1;
  if (next === current) return;
  event.preventDefault();
  tabs[next].focus();
  switchTab(tabs[next].dataset.tab);
}

function registerServiceWorker() {
  if (!("serviceWorker" in navigator)) {
    setSyncState("本地模式");
    return;
  }

  window.addEventListener("load", async () => {
    try {
      await navigator.serviceWorker.register("./service-worker.js");
      setSyncState(autoBridgeConnected ? "自动捕获已连接 · 已缓存" : "已缓存 · 离线可用");
    } catch {
      setSyncState(autoBridgeConnected ? "自动捕获已连接 · 本地模式" : "本地模式");
    }
  });
}

function setSyncState(message) {
  elements.syncState.textContent = message;
  renderAutopilotStatus();
}

function queueParsedItems(rows, metadata = {}) {
  const parsedRows = rows.map((row) => ({
    ...parseTransaction(row),
    ...metadata
  }));
  const withAmount = parsedRows.filter((item) => item.amount > 0);
  const withAccountId = withAmount.map((item) => ({ ...item, accountId: resolveAccountId(item.account) }));

  const newItems = [];
  const duplicateItems = [];
  const possibleItems = [];
  let deferredReview = 0;
  for (const item of withAccountId) {
    const duplicateOf = findDuplicate(item);
    if (duplicateOf) {
      // 判为同一笔时不再新增，但要把这条里更全的商户名/分类/用途补进去，
      // 否则「通知先到、页面后到」的那一半信息会被直接丢掉。
      mergeIntoDuplicate(duplicateOf, item);
      duplicateItems.push({ ...item, duplicateOf: duplicateOf.id, duplicateHint: true });
    } else {
      const possibleDuplicate = findPossibleDuplicate(item);
      if (possibleDuplicate) {
        const alreadyQueued = state.duplicateCandidates.some((candidate) =>
          candidate.existingId === possibleDuplicate.id
          && Boolean(item.captureId)
          && candidate.item?.captureId === item.captureId
          && normalizeCaptureText(candidate.item?.source) === normalizeCaptureText(item.source)
        );
        if (!alreadyQueued) {
          if (state.duplicateCandidates.length >= MAX_DUPLICATE_CANDIDATES) {
            deferredReview += 1;
            continue;
          }
          state.duplicateCandidates.unshift({
            id: crypto.randomUUID(),
            existingId: possibleDuplicate.id,
            item,
            createdAt: new Date().toISOString()
          });
          possibleItems.push(item);
        }
      } else {
        newItems.push(item);
      }
    }
  }

  state.entries.unshift(
    ...newItems.map((item) => ({
      ...item,
      confirmedAt: new Date().toISOString(),
      autoConfirmed: true
    }))
  );
  state.pending = [];
  state.stats.parsedCount += newItems.length + duplicateItems.length + possibleItems.length;
  state.stats.acceptedCount += newItems.length;
  const persisted = saveState();
  return {
    added: newItems.length + duplicateItems.length + possibleItems.length,
    pending: 0,
    confirmed: newItems.length,
    duplicates: duplicateItems.length,
    possibleDuplicates: possibleItems.length,
    withoutAmount: parsedRows.length - withAmount.length,
    deferredReview,
    persisted
  };
}

async function startAutoCaptureBridge() {
  const bridge = await checkAutoCaptureBridge();
  if (!bridge.connected) {
    if (bridge.requiresToken) setSyncState("自动捕获需要 token");
    if (bridge.invalidToken) setSyncState("自动捕获 token 不正确");
    setInterval(async () => {
      if (autoBridgeConnected) return;
      const retry = await checkAutoCaptureBridge();
      if (retry.connected) {
        autoBridgeConnected = true;
        setSyncState("自动捕获已连接 · 离线可用");
        await syncAutoCaptures({ announce: true });
      }
    }, 15000);
    return;
  }

  autoBridgeConnected = true;
  setSyncState("自动捕获已连接 · 离线可用");
  await syncAutoCaptures({ announce: false });
  setInterval(() => syncAutoCaptures({ announce: true }), 8000);
}

async function checkAutoCaptureBridge() {
  try {
    const response = await fetch("./api/health", { cache: "no-store", headers: bridgeHeaders() });
    if (!response.ok) return { connected: false };
    const data = await response.json();
    if (data.requiresToken && !getBridgeToken()) return { connected: false, requiresToken: true };
    if (data.requiresToken && data.authorized === false) return { connected: false, invalidToken: true };
    return { connected: data.ok === true };
  } catch {
    return { connected: false };
  }
}

async function syncAutoCaptures({ announce }) {
  try {
    const response = await fetch("./api/captures", { cache: "no-store", headers: bridgeHeaders() });
    if (response.status === 401) {
      setSyncState("自动捕获 token 不正确");
      return;
    }
    if (!response.ok) return;

    const data = await response.json();
    const captures = Array.isArray(data.captures) ? data.captures : [];
    if (!captures.length) return;

    let totalNew = 0;
    let totalDuplicates = 0;
    let totalPossible = 0;
    let newUnparsed = 0;
    let newDeferred = 0;
    for (const capture of captures) {
      if (unparsedBridgeCaptureIds.has(capture.id) || deferredBridgeCaptureIds.has(capture.id)) continue;
      const result = queueParsedItems(splitImportText(capture.text || capture.sourceText || ""), {
        captureSource: formatCaptureSource(capture.source),
        captureChannel: capture.source || "",
        captureId: capture.id || "",
        capturedAt: capture.createdAt || new Date().toISOString()
      });
      if (!(await result.persisted)) {
        // Only acknowledge a bridge event after the local ledger is durable.
        continue;
      }
      totalNew += result.confirmed;
      totalDuplicates += result.duplicates;
      totalPossible += result.possibleDuplicates;
      if (result.withoutAmount > 0) {
        unparsedBridgeCaptureIds.add(capture.id);
        if (rememberBridgeIssue(`unparsed:${capture.id}`)) newUnparsed += result.withoutAmount;
        continue;
      }
      if (result.deferredReview > 0) {
        deferredBridgeCaptureIds.add(capture.id);
        if (rememberBridgeIssue(`review-full:${capture.id}`)) newDeferred += result.deferredReview;
        continue;
      }
      await claimAutoCapture(capture.id);
    }

    if (newUnparsed > 0) {
      showToast(`有 ${newUnparsed} 条桥接内容暂时无法识别金额，已保留在待同步队列，没有丢弃。`, "info");
    } else if (newDeferred > 0) {
      showToast("疑似重复复核列表已满，剩余账单仍保留在桥接队列。", "info");
    } else if (announce && totalPossible > 0) {
      showToast(`有 ${totalPossible} 笔账单与已有记录相近，已放入“疑似重复”待确认。`, "info");
      renderAutopilotStatus();
    } else if (announce && totalNew > 0) {
      showToast(`自动捕获 ${totalNew} 条新账单，已按规则处理。`, "success");
      renderAutopilotStatus();
    } else if (announce && totalDuplicates > 0 && totalNew === 0) {
      showToast("自动捕获到的账单已经在账本里了。", "info");
    }
  } catch {
    autoBridgeConnected = false;
    setSyncState("本地优先 · 桥接未连接");
  }
}

function rememberBridgeIssue(key) {
  if (!key || announcedBridgeIssues.has(key)) return false;
  announcedBridgeIssues.add(key);
  if (announcedBridgeIssues.size > 500) {
    announcedBridgeIssues.delete(announcedBridgeIssues.values().next().value);
  }
  return true;
}

async function claimAutoCapture(id) {
  if (!id) return;
  try {
    await fetch(`./api/captures/${encodeURIComponent(id)}/claim`, {
      method: "POST",
      headers: bridgeHeaders(),
      cache: "no-store"
    });
  } catch {
    // Keep the capture on the bridge; dedupe protects the local queue on retry.
  }
}

function bridgeHeaders() {
  const token = getBridgeToken();
  return token ? { "X-Ziji-Token": token } : {};
}

function getBridgeToken() {
  return bridgeToken;
}

function parseTransaction(source) {
  return parseBillTransaction(source, { userRules: state.userRules });
}

function formatCaptureSource(source) {
  const value = String(source || "").toLowerCase();
  if (value.includes("android-notification-retry")) return "Android 补发";
  if (value.includes("android-notification")) return "Android 通知";
  if (value.includes("android-accessibility")) return "Android 页面识别";
  if (value.includes("android-companion-test")) return "Android 测试";
  if (value.includes("android-share")) return "Android 分享";
  if (value.includes("shortcut")) return "快捷指令";
  if (value.includes("bridge")) return "桥接";
  return "自动捕获";
}

/**
 * 「这两条是不是同一笔钱」。
 *
 * 旧实现要求「金额 + 商户 + 10 分钟内」，看起来比手机端宽松，实际上更糟：
 * 同一次支付在两条通道里拿到的商户名经常不一样——页面通道认出「红姐包子-软景店」，
 * 通知通道只解析出「未知商户」或界面上的「搜索」。商户对不上，这条判断就永远 false，
 * 账本上于是同金额同时间出现两条。
 *
 * 规则收在 dedupe.js，和手机端 PaymentIdentity 是同一套。
 */
function findDuplicate(item) {
  return state.entries.find((current) => {
    if (current.id === item.id) return false;
    if (isExplicitSource(current) || isExplicitSource(item)) return false;
    return isSamePayment(current, item);
  });
}

function findPossibleDuplicate(item) {
  return state.entries.find((current) => {
    return current.id !== item.id
      && !isExplicitSource(current)
      && !isExplicitSource(item)
      && !isSamePayment(current, item)
      && isPossibleDuplicatePair(current, item);
  }) || null;
}

function normalizeCaptureText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function handleDuplicateReviewClick(event) {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  const candidate = state.duplicateCandidates.find((item) => item.id === button.dataset.id);
  if (!candidate) return;
  const existing = state.entries.find((item) => item.id === candidate.existingId);
  if (button.dataset.action === "merge-candidate" && !existing) {
    showToast("原账单已不存在，候选仍保留；请选择保留这笔账。", "info");
    return;
  }

  if (button.dataset.action === "merge-candidate") {
    mergeDuplicate(existing, candidate.item);
    if (candidate.entryId) {
      state.entries = state.entries.filter((entry) => entry.id !== candidate.entryId);
    }
  } else if (button.dataset.action === "keep-candidate") {
    if (candidate.entryId) {
      const reviewedEntry = state.entries.find((entry) => entry.id === candidate.entryId);
      if (reviewedEntry) {
        reviewedEntry.duplicateReviewed = "keep-both";
      } else {
        state.entries.unshift({
          ...candidate.item,
          confirmedAt: new Date().toISOString(),
          autoConfirmed: true,
          duplicateReviewed: "keep-both"
        });
        state.stats.acceptedCount += 1;
      }
    } else {
      state.entries.unshift({
        ...candidate.item,
        confirmedAt: new Date().toISOString(),
        autoConfirmed: true,
        duplicateReviewed: "keep-both"
      });
      state.stats.acceptedCount += 1;
    }
  } else {
    return;
  }

  state.duplicateCandidates = state.duplicateCandidates.filter((item) => item.id !== candidate.id);
  deferredBridgeCaptureIds.clear();
  saveState();
}

function mergeIntoDuplicate(existing, incoming) {
  return mergeDuplicate(existing, incoming);
}

function openEditModal(type, id) {
  const items = state.entries;
  const item = items.find((entry) => entry.id === id);
  if (!item) return;

  editModalTarget = { type, id };
  lastFocusedElement = document.activeElement;
  populateEditModalOptions();
  $("#editMerchant").value = item.merchant;
  $("#editAmount").value = item.amount;
  $("#editCategory").value = item.category;
  $("#editAccount").value = item.accountId || resolveAccountId(item.account);
  $("#editDeleteEntry").hidden = false;
  $("#editModal").removeAttribute("hidden");
  setBackgroundInert(true);
  handleViewportResize();
  setTimeout(() => $("#editMerchant").focus(), 50);
}

function populateEditModalOptions() {
  $("#editCategory").innerHTML = [...state.categories]
    .sort((a, b) => a.order - b.order)
    .map((c) => `<option value="${escapeHtml(c.name)}">${escapeHtml(c.name)}</option>`)
    .join("");

  $("#editAccount").innerHTML = [...state.accounts]
    .sort((a, b) => a.order - b.order)
    .map((a) => `<option value="${a.id}">${escapeHtml(a.name)}${a.archived ? "（已归档）" : ""}</option>`)
    .join("");
}

function closeEditModal() {
  editModalTarget = null;
  $("#editModal").setAttribute("hidden", "");
  setBackgroundInert(false);
  const card = $("#editModal").querySelector(".modal-card");
  if (card) card.style.removeProperty("--viewport-max-height");
  if (lastFocusedElement && document.body.contains(lastFocusedElement)) {
    lastFocusedElement.focus();
  }
  lastFocusedElement = null;
}

function initEditModal() {
  $("#editModal").addEventListener("keydown", trapModalFocus);
  window.addEventListener("resize", handleViewportResize);
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", handleViewportResize);
  }
}

function setBackgroundInert(isInert) {
  const shell = document.querySelector(".app-shell");
  if (!shell) return;
  if ("inert" in shell) {
    shell.inert = isInert;
  } else if (isInert) {
    shell.setAttribute("aria-hidden", "true");
  } else {
    shell.removeAttribute("aria-hidden");
  }
}

function handleViewportResize() {
  if (!editModalTarget) return;
  const card = $("#editModal").querySelector(".modal-card");
  if (!card) return;
  const height = window.visualViewport ? window.visualViewport.height : window.innerHeight;
  card.style.setProperty("--viewport-max-height", `${Math.round(height * 0.92)}px`);
}

function trapModalFocus(event) {
  if (!editModalTarget) return;
  if (event.key === "Escape") {
    event.preventDefault();
    closeEditModal();
    return;
  }
  if (event.key !== "Tab") return;

  const focusable = Array.from(
    $("#editModal").querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')
  ).filter((el) => !el.disabled && el.offsetParent !== null);
  if (!focusable.length) return;

  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function confirmEditModal() {
  if (!editModalTarget) return;

  const merchant = $("#editMerchant").value.trim();
  const amount = Number($("#editAmount").value);
  const category = $("#editCategory").value;
  const accountId = $("#editAccount").value;

  if (!merchant) {
    showToast("商户名不能为空。", "error");
    return;
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    showToast("请输入有效金额。", "error");
    return;
  }

  const account = state.accounts.find((a) => a.id === accountId);
  const item = state.entries.find((entry) => entry.id === editModalTarget.id);
  if (item) {
    Object.assign(item, {
      merchant,
      amount,
      category,
      accountId,
      account: account ? account.sourceTag : item.account,
      confidence: 100
    });
  }

  closeEditModal();
  saveState();
  showToast("已修改。", "success");
}

function deleteFromModal() {
  if (!editModalTarget || editModalTarget.type !== "entry") return;

  const id = editModalTarget.id;
  const index = state.entries.findIndex((entry) => entry.id === id);
  if (index === -1) return;
  const [removed] = state.entries.splice(index, 1);
  closeEditModal();
  saveState();
  showToast("已删除。", "info", {
    duration: 7000,
    onUndo: () => {
      state.entries.splice(Math.min(index, state.entries.length), 0, removed);
      saveState();
      showToast("已撤销删除。", "success");
    }
  });
}

function saveUserRule() {
  const keyword = elements.ruleKeyword.value.trim();
  const category = elements.ruleCategory.value;

  if (!keyword) {
    showToast("先填一个关键词，比如“瑞幸”。", "error");
    return;
  }

  const existing = state.userRules.find((rule) => rule.keyword === keyword);
  if (existing) {
    existing.category = category;
  } else {
    state.userRules.push({ id: crypto.randomUUID(), keyword, category });
  }

  // 规则只对以后生效的话，用户教了一次还得把历史几十笔再点一遍，那不叫学会。
  const backfilled = backfillCategoryForKeyword(keyword, category);

  elements.ruleKeyword.value = "";
  saveState();
  showToast(
    backfilled > 0
      ? `规则已保存，历史 ${backfilled} 笔也一并归类了。`
      : "规则已保存，下次会优先使用。",
    "success"
  );
}

/**
 * 把包含这个关键词、且分类还停在「其他」的记录归到新分类。
 * 只动「其他/未分类」：用户明确改过的分类不该被规则覆盖。
 */
function backfillCategoryForKeyword(keyword, category) {
  const needle = String(keyword || "").trim().toLowerCase();
  if (!needle || !category || category === "其他") return 0;
  let changed = 0;
  for (const item of state.entries) {
    const current = String(item.category || "").trim();
    if (current && current !== "其他" && current !== "未分类") continue;
    const haystack = `${item.merchant || ""} ${item.source || ""} ${item.note || ""}`.toLowerCase();
    if (!haystack.includes(needle)) continue;
    item.category = category;
    item.updatedAt = new Date().toISOString();
    changed += 1;
  }
  return changed;
}

function deleteRule(id) {
  state.userRules = state.userRules.filter((rule) => rule.id !== id);
  saveState();
}

function saveBudget() {
  const budget = Number(elements.budgetInput.value);
  if (!Number.isFinite(budget) || budget < 0) {
    showToast("预算要填一个大于等于 0 的数字。", "error");
    return;
  }

  state.settings.monthlyBudget = budget;
  saveState();
  showToast("预算已更新。", "success");
}

function exportCsv() {
  const rows = state.entries.map((item) =>
    item.direction === "transfer"
      ? [
          formatCsvTime(item.occurredAt),
          "转账",
          item.amount,
          "",
          "",
          `${resolveAccountNameById(item.fromAccountId)} → ${resolveAccountNameById(item.toAccountId)}`,
          item.note || ""
        ]
      : [
          formatCsvTime(item.occurredAt),
          item.direction === "income" ? "收入" : "支出",
          item.amount,
          item.merchant,
          item.category,
          resolveAccountName(item),
          item.manual ? item.note || "手动记账" : item.source
        ]
  );
  downloadFile("ziji-ledger.csv", toCsv([CSV_HEADER, ...rows]), "text/csv;charset=utf-8");
}

/**
 * 导入手机端导出的 8 列 CSV，或本页导出的 7 列 CSV。
 *
 * 之前网页只有导出没有导入：手机导出的表在网页上打不开，网页导出的表手机又认不了，
 * 两端之间没有任何一条可用通路。解析规则在 csv.js，和手机端同一套判据。
 */
async function importCsvFile(file) {
  if (!file) return;
  if (file.size > 8 * 1024 * 1024) {
    showToast("CSV 超过 8MB，请先按月拆分再导入。", "error");
    elements.importCsv.value = "";
    return;
  }
  try {
    const result = parseLedgerCsv(await readCsvFile(file), { existing: state.entries });
    if (result.error) {
      showToast(result.error, "error");
    } else if (!result.rows.length) {
      showToast(
        result.invalid > 0
          ? `没有可导入的记录，${result.invalid} 行无法识别。`
          : "这份 CSV 里没有新账单。",
        "info"
      );
    } else {
      const planned = planCsvImport(result.rows);
      const nearHint = planned.candidates.length
        ? `，另有 ${planned.candidates.length} 条与已有记录相近、导入后待确认`
        : "";
      const deferredHint = planned.deferred
        ? `，另有 ${planned.deferred} 条相近记录因复核队列已满暂不导入`
        : "";
      if (!confirm(
        `将导入 ${planned.accepted.length} 条账单，跳过 ${result.duplicates} 条重复、${result.invalid} 条无法识别${nearHint}${deferredHint}，继续吗？`
      )) {
        elements.importCsv.value = "";
        return;
      }
      // 手机端有 22 个支出分类，网页端默认只有 11 个。缺的直接补上，
      // 否则「零食」这类账在网页上既选不到、也会被分类图表漏掉。
      result.rows.forEach((row) => ensureCategory(row.category));
      state.entries.unshift(...planned.accepted);
      state.duplicateCandidates.push(...planned.candidates);
      state.stats.parsedCount += result.rows.length;
      state.stats.acceptedCount += planned.accepted.length;
      saveState();
      showToast(
        planned.deferred
          ? `已导入 ${planned.accepted.length} 条；${planned.deferred} 条相近记录未导入，请处理待复核记录后重新导入。`
          : planned.candidates.length
            ? `已导入 ${planned.accepted.length} 条；另 ${planned.candidates.length} 条与已有记录相近，放在收件箱顶部待确认。`
            : `已导入 ${planned.accepted.length} 条账单。`,
        planned.deferred ? "info" : "success"
      );
    }
  } catch {
    showToast("CSV 读取失败，请确认文件没有损坏。", "error");
  }
  elements.importCsv.value = "";
}

/**
 * 把待导入的行分成「直接入账」和「与已有记录相近、需要确认」两组。
 *
 * CSV 是对账单，不是用户逐条敲进去的账：银行流水的时间和商户名与自动捕获
 * 常常差几秒、差几个字，csv.js 的精确判重拦不住——直接导入就会把同一笔钱
 * 记两遍。这里沿用「机器不确定就别替人拍板」的口径：相近的放进收件箱等确认，
 * 既不静默重复，也不静默丢弃。人工手动记账不走这条路，仍然是点了就入账。
 */
function planCsvImport(rows) {
  const eligible = [];
  for (const row of rows) {
    const item = toImportedEntry(row);
    // 同一份文件导入两次时，已在待确认里的行不能再排一次队。
    const alreadyQueued = state.duplicateCandidates.some((candidate) =>
      candidate.item
      && Number(candidate.item.amount) === Number(item.amount)
      && (candidate.item.direction || "expense") === (item.direction || "expense")
      && String(candidate.item.occurredAt) === String(item.occurredAt)
      && String(candidate.item.merchant || "") === String(item.merchant || "")
    );
    if (alreadyQueued) continue;
    eligible.push(item);
  }
  // 同一份文件内部两次相近也算；队列满时必须延期，不能改走直接入账。
  const partition = partitionPossibleDuplicates(
    eligible,
    state.entries,
    MAX_DUPLICATE_CANDIDATES - state.duplicateCandidates.length
  );
  return {
    accepted: partition.accepted,
    candidates: partition.candidates.map(({ existing, item }) => ({
      id: crypto.randomUUID(),
      existingId: existing.id,
      item,
      createdAt: new Date().toISOString()
    })),
    deferred: partition.deferred
  };
}

/** 网页导出时「来源/备注」列写的是来源标签，不该在导入后变成用户备注。 */const CSV_SOURCE_LABELS = new Set([
  "手动记账", "自动捕获", "桥接", "快捷指令",
  "Android 通知", "Android 页面识别", "Android 补发", "Android 分享", "Android 测试"
]);

function toImportedEntry(row) {
  const now = new Date().toISOString();
  return {
    id: crypto.randomUUID(),
    source: "csv",
    amount: row.amount,
    direction: row.direction,
    merchant: row.merchant,
    account: row.account,
    accountId: ensureAccount(row.account),
    category: row.category,
    note: CSV_SOURCE_LABELS.has(row.note) ? "" : row.note,
    occurredAt: row.occurredAt,
    confidence: 100,
    autoParsed: false,
    manual: false,
    imported: true,
    createdAt: now,
    confirmedAt: now
  };
}

/** 先按 UTF-8 解；出现替换字符说明是中文版 Excel 存出来的 GBK。 */
async function readCsvFile(file) {
  const buffer = await file.arrayBuffer();
  const utf8 = new TextDecoder("utf-8").decode(buffer);
  if (!utf8.includes("\uFFFD")) return utf8;
  try {
    return new TextDecoder("gbk").decode(buffer);
  } catch {
    return utf8;
  }
}

function ensureCategory(name) {
  const value = String(name || "").trim();
  if (!value || state.categories.some((category) => category.name === value)) return;
  const order = state.categories.reduce((max, category) => Math.max(max, category.order || 0), 0) + 1;
  state.categories.push({ id: crypto.randomUUID(), name: value, builtin: false, order });
}

/**
 * 账户不像分类那样「找不到就归到其他」。
 *
 * 手机端导出的账户是自由文本（零钱、微信零钱、花呗），网页端默认只有几个内置账户。
 * 以前统一落到「其他」：金额没错，但每一笔的账户归属全丢了，分账户余额直接失真，
 * 而且没有任何提示。这里改成缺什么补什么——宁可多一个账户，也不静默改数据。
 */
function ensureAccount(name) {
  const value = String(name || "").trim();
  if (!value) return null;
  const existing = state.accounts.find((account) => account.name === value || account.sourceTag === value);
  if (existing) return existing.id;
  const account = {
    id: crypto.randomUUID(),
    name: value,
    sourceTag: value,
    type: "other",
    startingBalance: 0,
    archived: false,
    order: state.accounts.reduce((max, item) => Math.max(max, item.order || 0), 0) + 1,
    createdAt: new Date().toISOString()
  };
  state.accounts.push(account);
  return account.id;
}

async function exportJson() {
  try {
    const payload = {
      format: "ziji-ledger-backup",
      version: 4,
      generatedAt: new Date().toISOString(),
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
      checksum: await checksumJson(state),
      payload: state
    };
    downloadFile("ziji-ledger-backup.json", JSON.stringify(payload, null, 2), "application/json");
  } catch {
    showToast("备份导出失败，请稍后重试。", "error");
  }
}

function restoreBackup(file) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = async () => {
    try {
      const backup = JSON.parse(String(reader.result || ""));
      const payload = extractBackupPayload(backup);
      if (!payload) throw new Error("Invalid backup");
      if (backup.checksum && backup.checksum !== (await checksumJson(payload))) {
        throw new Error("Checksum mismatch");
      }
      const nextState = migrate(payload);
      if (!confirm(`将替换当前账本中的 ${state.entries.length} 条记录为备份中的 ${nextState.entries.length} 条记录，继续吗？`)) {
        elements.restoreJson.value = "";
        return;
      }
      Object.assign(state, nextState);
      saveState();
      showToast("备份已恢复。", "success");
    } catch {
      showToast("这个 JSON 备份格式不对。", "error");
    }
    elements.restoreJson.value = "";
  };
  reader.readAsText(file, "utf-8");
}

/** 找出历史上的疑似重复并逐笔交给用户决定，不直接改写账本。 */
function mergeDuplicateEntries() {
  if (state.entries.length < 2) {
    showToast("账本里还没有足够多的记录。", "info");
    return;
  }

  const sorted = [...state.entries].sort((a, b) => new Date(a.occurredAt) - new Date(b.occurredAt));
  const buckets = new Map();
  let added = 0;
  for (const entry of sorted) {
    const amountCents = Math.round(Number(entry.amount || 0) * 100);
    const time = new Date(entry.occurredAt).getTime();
    if (amountCents <= 0 || !Number.isFinite(time)) continue;
    const key = `${amountCents}|${entry.direction || "expense"}`;
    const bucket = buckets.get(key) || [];
    for (let index = bucket.length - 1; index >= 0; index -= 1) {
      const existing = bucket[index];
      const gap = time - new Date(existing.occurredAt).getTime();
      if (gap > POSSIBLE_DUPLICATE_WINDOW_MILLIS) break;
      if (bothExplicitSources(existing, entry) || !isPossibleDuplicatePair(existing, entry)) continue;
      const alreadyQueued = state.duplicateCandidates.some((candidate) =>
        (candidate.existingId === existing.id && candidate.entryId === entry.id)
        || (candidate.existingId === entry.id && candidate.entryId === existing.id)
      );
      if (alreadyQueued) continue;
      state.duplicateCandidates.push({
        id: crypto.randomUUID(),
        existingId: existing.id,
        entryId: entry.id,
        item: entry,
        createdAt: new Date().toISOString()
      });
      added += 1;
      if (state.duplicateCandidates.length >= 100) break;
    }
    if (!bucket.some((item) => item.id === entry.id)) bucket.push(entry);
    buckets.set(key, bucket);
    if (state.duplicateCandidates.length >= 100) break;
  }

  if (!added && !state.duplicateCandidates.length) {
    showToast("没有发现需要复核的相近账单。", "info");
    return;
  }
  saveState();
  // 复核卡片挂在收件箱（#tab-home）顶部，不是账本页——跳到账本会让用户
  // 点完按钮什么都看不到，以为没找到。
  switchTab("home");
  showToast(
    added
      ? `找到 ${added} 组疑似重复，已列在收件箱顶部，请逐笔确认。`
      : "疑似重复都在收件箱顶部，请逐笔确认。",
    "info"
  );
}

function clearAllData() {
  if (!confirm("清空会删除本机账本、规则和账户设置，确定继续吗？")) return;

  const snapshot = {
    entries: state.entries,
    pending: state.pending,
    userRules: state.userRules,
    accounts: state.accounts,
    categories: state.categories,
    settings: { ...state.settings },
    stats: { ...state.stats }
  };

  state.entries = [];
  state.pending = [];
  state.userRules = [];
  state.accounts = createDefaultAccounts();
  state.categories = createDefaultCategories();
  state.settings.monthlyBudget = 0;
  state.settings.autoConfirm = true;
  state.settings.autoConfirmConfidence = 85;
  state.stats = { parsedCount: 0, acceptedCount: 0, manualCount: 0 };
  bridgeToken = "";
  removeStorage(BRIDGE_TOKEN_KEY).catch((error) => console.warn("Unable to clear bridge token", error));
  saveState();
  showToast("已清空本地数据。", "info", {
    duration: 8000,
    onUndo: () => {
      Object.assign(state, snapshot);
      editingTransferId = null;
      saveState();
      showToast("已恢复清空前的数据。", "success");
    }
  });
}

function toCsv(rows) {
  return rows
    .map((row) =>
      row
        .map((cell) => `"${sanitizeCsvCell(cell).replace(/"/g, '""')}"`)
        .join(",")
    )
    .join("\n");
}

function downloadFile(filename, content, type) {
  const blob = new Blob(["\uFEFF", content], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

function render() {
  renderAutopilotStatus();
  renderSummary();
  renderLedger();
  renderRules();
  renderBudget();
  renderAccounts();
  renderCategoryManager();
  renderTransferForm();
  renderManualEntryForm();
  renderReports();
}

function renderAutopilotStatus() {
  if (!elements.autopilotCallout) return;
  elements.autopilotCallout.classList.toggle("is-connected", autoBridgeConnected);
  if (autoBridgeConnected) {
    elements.autopilotTitle.textContent = "自动捕获已连接";
    elements.autopilotCopy.textContent = "支付通知会自动同步到这里，识别成功后直接进入账本。";
  } else {
    elements.autopilotTitle.textContent = "等待手机通知授权";
    elements.autopilotCopy.textContent = "安装 Android 伴侣并允许通知读取后，支付通知会自动记账；电脑桥接仅用于同步查看。";
  }
}

function sanitizeCsvCell(value) {
  const text = String(value ?? "");
  if (/^[=+\-@]/.test(text)) return `'${text}`;
  return text;
}

function extractBackupPayload(backup) {
  if (!backup || typeof backup !== "object") return null;
  if (Array.isArray(backup.entries) && Array.isArray(backup.pending)) {
    return backup;
  }
  if (backup.format === "ziji-ledger-backup" && backup.payload && typeof backup.payload === "object") {
    return backup.payload;
  }
  return null;
}

function checksumJsonSync(value) {
  const text = JSON.stringify(value);
  let hash = 0;
  for (let index = 0; index < text.length; index += 1) {
    hash = (hash * 31 + text.charCodeAt(index)) >>> 0;
  }
  return `fnv1a32:${hash.toString(16).padStart(8, "0")}`;
}

async function checksumJson(value) {
  if (crypto?.subtle?.digest) {
    const encoded = new TextEncoder().encode(JSON.stringify(value));
    const digest = await crypto.subtle.digest("SHA-256", encoded);
    return `sha256:${Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("")}`;
  }
  return checksumJsonSync(value);
}

function renderSummary() {
  const now = new Date();
  const monthEntries = state.entries.filter((item) => isSameMonth(item.occurredAt, now));
  const todayEntries = state.entries.filter((item) => isSameDay(item.occurredAt, now));
  const monthSpend = sumExpenses(monthEntries);
  const todaySpend = sumExpenses(todayEntries);
  const topCategory = getTopCategory(monthEntries);
  const accepted = state.stats.acceptedCount || 0;
  const parsed = state.stats.parsedCount || 0;
  const rate = parsed ? Math.round((accepted / parsed) * 100) : 0;

  elements.monthSpend.textContent = money.format(monthSpend);
  elements.todaySpend.textContent = money.format(todaySpend);
  elements.topCategory.textContent = topCategory || "暂无";
  elements.autoRate.textContent = `${rate}%`;
  if (state.settings.monthlyBudget > 0) {
    const remain = state.settings.monthlyBudget - monthSpend;
    elements.budgetHint.textContent = remain >= 0 ? `还可用 ${money.format(remain)}` : `超出 ${money.format(Math.abs(remain))}`;
  } else {
    elements.budgetHint.textContent = "还没有设置预算";
  }
}

function renderLedger() {
  renderDuplicateReview();
  const categoryNames = state.categories.map((c) => c.name);
  const categories = ["全部", ...categoryNames.filter((category) => state.entries.some((item) => item.category === category))];
  if (elements.ledgerSearch && elements.ledgerSearch.value !== ledgerQuery) {
    elements.ledgerSearch.value = ledgerQuery;
  }
  const directionOptions = [
    ["all", "全部"],
    ["expense", "支出"],
    ["income", "收入"],
    ["transfer", "转账"]
  ];
  elements.directionFilters.innerHTML = directionOptions
    .map(([value, label]) => `<button class="filter-chip ${directionFilter === value ? "active" : ""}" type="button" data-direction="${value}">${label}</button>`)
    .join("");
  elements.directionFilters.querySelectorAll("button").forEach((button) => {
    button.addEventListener("click", () => {
      directionFilter = button.dataset.direction;
      renderLedger();
    });
  });
  elements.categoryFilters.innerHTML = categories
    .map((category) => `<button class="filter-chip ${activeFilter === category ? "active" : ""}" type="button" data-category="${escapeHtml(category)}">${escapeHtml(category)}</button>`)
    .join("");

  elements.categoryFilters.querySelectorAll("button").forEach((button) => {
    button.addEventListener("click", () => {
      activeFilter = button.dataset.category;
      renderLedger();
    });
  });

  const query = ledgerQuery.toLowerCase();
  const filtered = state.entries.filter((item) => {
    const categoryMatch = activeFilter === "全部" || item.category === activeFilter;
    const directionMatch = directionFilter === "all" || item.direction === directionFilter;
    const haystack = [
      item.merchant,
      item.category,
      resolveAccountName(item),
      item.note,
      item.rawText,
      item.source
    ].filter(Boolean).join(" ").toLowerCase();
    return categoryMatch && directionMatch && (!query || haystack.includes(query));
  });
  if (!filtered.length) {
    elements.ledgerList.innerHTML = `<div class="empty-state">没有匹配的账单。自动记录会直接进入这里，未识别到金额时再用手动记账补录。</div>`;
  } else {
    elements.ledgerList.innerHTML = filtered
      .map((item) =>
        item.direction === "transfer"
          ? `
          <div class="ledger-item transfer">
            <span class="ledger-icon transfer"><svg><use href="./assets/icons.svg#icon-transfer"></use></svg></span>
            <div class="ledger-main">
              <span class="merchant">${escapeHtml(resolveAccountNameById(item.fromAccountId))} → ${escapeHtml(resolveAccountNameById(item.toAccountId))}</span>
              <div class="meta"><span>${escapeHtml(formatDate(item.occurredAt))}</span>${item.note ? `<span>${escapeHtml(item.note)}</span>` : ""}</div>
            </div>
            <strong class="amount transfer">${money.format(item.amount)}</strong>
            <div class="ledger-actions">
              <button class="icon-btn" type="button" data-action="edit-transfer" data-id="${escapeHtml(item.id)}" aria-label="修改转账"><svg><use href="./assets/icons.svg#icon-pencil"></use></svg></button>
              <button class="icon-btn danger" type="button" data-action="delete-transfer" data-id="${escapeHtml(item.id)}" aria-label="删除转账"><svg><use href="./assets/icons.svg#icon-trash"></use></svg></button>
            </div>
          </div>
        `
          : `
          <div class="ledger-item">
            <span class="ledger-icon"><svg><use href="./assets/icons.svg#${categoryIconId(item.category)}"></use></svg></span>
            <div class="ledger-main">
              <span class="merchant">${escapeHtml(item.merchant)}</span>
              <div class="meta">${renderItemMeta(item)}</div>
            </div>
            <strong class="amount ${item.direction === "income" ? "income" : ""}">${item.direction === "income" ? "+" : "-"}${money.format(item.amount)}</strong>
            <button class="ledger-edit-btn" type="button" data-id="${escapeHtml(item.id)}" aria-label="修改 ${escapeHtml(item.merchant)}"><svg><use href="./assets/icons.svg#icon-pencil"></use></svg></button>
          </div>
        `
      )
      .join("");

    elements.ledgerList.querySelectorAll(".ledger-edit-btn").forEach((button) => {
      button.addEventListener("click", () => openEditModal("entry", button.dataset.id));
    });
    elements.ledgerList.querySelectorAll('[data-action="edit-transfer"]').forEach((button) => {
      button.addEventListener("click", () => editTransfer(button.dataset.id));
    });
    elements.ledgerList.querySelectorAll('[data-action="delete-transfer"]').forEach((button) => {
      button.addEventListener("click", () => deleteTransfer(button.dataset.id));
    });
  }

  renderCategoryChart();
}

function renderDuplicateReview() {
  const container = $("#duplicateReview");
  if (!container) return;
  const candidates = state.duplicateCandidates || [];
  container.hidden = candidates.length === 0;
  if (!candidates.length) {
    container.innerHTML = "";
    return;
  }

  container.innerHTML = `
    <div class="duplicate-review-heading">
      <strong>可能重复 · ${candidates.length} 笔待确认</strong>
      <span>信息不够确定，先保留在这里，不计入账本</span>
    </div>
    ${candidates.map((candidate) => {
      const existing = state.entries.find((item) => item.id === candidate.existingId);
      const incoming = candidate.item || {};
      const candidateEntryExists = candidate.entryId && state.entries.some((item) => item.id === candidate.entryId);
      const missingExistingHint = candidateEntryExists
        ? "另一条记录仍在账本中，保留即可"
        : "保留候选即可将它记入账本";
      const keepLabel = existing ? "保留两笔" : candidateEntryExists ? "保留这条记录" : "保留并入账";
      return `
        <article class="duplicate-review-card">
          <div class="duplicate-review-pair">
            <div><span>账本已有</span><strong>${existing ? `${escapeHtml(existing.merchant || "未知商户")} · ${money.format(existing.amount || 0)}` : "原账单已不存在"}</strong><small>${existing ? escapeHtml(formatDate(existing.occurredAt)) : missingExistingHint}</small></div>
            <div><span>${candidate.entryId ? "另一条已有记录" : "新捕获"}</span><strong>${escapeHtml(incoming.merchant || "未知商户")} · ${money.format(incoming.amount || 0)}</strong><small>${escapeHtml(formatDate(incoming.occurredAt))}</small></div>
          </div>
          <div class="duplicate-review-actions">
            ${existing ? `<button class="secondary-button compact" type="button" data-action="merge-candidate" data-id="${escapeHtml(candidate.id)}">确认同一笔并合并</button>` : ""}
            <button class="ghost-button compact" type="button" data-action="keep-candidate" data-id="${escapeHtml(candidate.id)}">${keepLabel}</button>
          </div>
        </article>
      `;
    }).join("")}
  `;
}

function renderItemMeta(item) {
  const tags = [
    { text: item.category },
    { text: resolveAccountName(item) },
    { text: formatDate(item.occurredAt) }
  ];

  if (item.captureSource) tags.push({ text: item.captureSource, className: "system-tag" });
  if (item.autoConfirmed) tags.push({ text: "自动入账", className: "auto-tag" });
  if (item.manual) tags.push({ text: "手动记账", className: "system-tag" });
  if (item.note) tags.push({ text: item.note });

  return tags
    .filter((tag) => tag.text)
    .map((tag) => `<span class="${tag.className || ""}">${escapeHtml(tag.text)}</span>`)
    .join("");
}

function computeCategoryTotals(entries, categories) {
  const totals = new Map(categories.map((c) => [c.name, 0]));
  // 已扣减退款的分类合计：退款有原交易时扣回原分类，没有时挂在「退款」项下。
  // 第二个参数是全量账本：跨月退款的原交易不在 entries 里，只有全量才能找回它。
  for (const [label, cents] of computeRefundOffset(entries, state.entries).categories) {
    totals.set(label, (totals.get(label) || 0) + cents);
  }
  return [...totals]
    .map(([label, total]) => ({ label, total: total / 100 }))
    .filter((row) => row.total !== 0)
    .sort((a, b) => b.total - a.total);
}

function buildChartRows(rows, emptyCopy) {
  if (!rows.length) return `<div class="empty-state">${escapeHtml(emptyCopy)}</div>`;

  // 退款没匹配到原分类时会出现负值行，比例尺只按正值算。
  const max = Math.max(...rows.map((row) => Math.max(row.total, 0)), 1);
  return rows
    .map((row) => {
      const width = row.total > 0 ? Math.max((row.total / max) * 100, 7) : 0;
      return `
        <div class="chart-row">
          <strong>${escapeHtml(row.label)}</strong>
          <span class="chart-track"><span class="chart-bar" style="width:${width}%"></span></span>
          <span>${money.format(row.total)}</span>
        </div>
      `;
    })
    .join("");
}

function renderCategoryChart() {
  elements.categoryChart.innerHTML = buildChartRows(
    computeCategoryTotals(state.entries, state.categories),
    "分类图会在入账后自动生成。"
  );
}

function renderReports() {
  const now = new Date();
  const period = new Date(now.getFullYear(), now.getMonth() + reportMonthOffset, 1);
  const periodEntries = state.entries.filter((item) => isSameMonth(item.occurredAt, period));
  // 传全量账本做退款归因：9 月底买的、10 月初退的，原交易不在本期切片里。
  const summary = computeRefundOffset(periodEntries, state.entries);
  const expense = summary.netExpenseCents / 100;
  const income = summary.incomeCents / 100;
  const net = income - expense;

  elements.reportPeriodLabel.textContent = `${period.getFullYear()}年${period.getMonth() + 1}月`;
  elements.reportNextMonth.disabled = reportMonthOffset >= 0;
  elements.reportExpense.textContent = money.format(expense);
  elements.reportIncome.textContent = money.format(income);
  elements.reportNet.textContent = money.format(net);
  elements.reportNet.className = net < 0 ? "money-out" : "money-in";

  const transfers = periodEntries.filter((item) => item.direction === "transfer").length;
  const counted = periodEntries.length - transfers;
  elements.reportMeta.textContent = counted
    ? `本期 ${counted} 笔收支${transfers ? `，另有 ${transfers} 笔转账不计入收支` : ""}。`
      + (summary.refundCents > 0
        ? `退款 ${money.format(summary.refundCents / 100)} 已冲抵支出，不计入收入。`
        : "")
    : "本期还没有收支记录。";

  elements.reportCategoryChart.innerHTML = buildChartRows(
    computeCategoryTotals(periodEntries, state.categories),
    "本期还没有支出。"
  );
  elements.reportTrend.innerHTML = buildChartRows(buildMonthlyTrend(period), "还没有足够的历史支出数据。");

  const balances = computeAccountBalances(state.entries, state.accounts)
    .filter((account) => !account.archived)
    .sort((a, b) => a.order - b.order);
  elements.reportNetWorth.textContent = money.format(balances.reduce((sum, a) => sum + a.balance, 0));
  elements.reportBalances.innerHTML = balances.length
    ? balances
        .map(
          (account) => `
        <div class="balance-row">
          <span>${escapeHtml(account.name)}</span>
          <strong class="${account.balance < 0 ? "money-out" : ""}">${money.format(account.balance)}</strong>
        </div>
      `
        )
        .join("")
    : `<div class="empty-state">还没有启用中的账户。</div>`;
}

function buildMonthlyTrend(period) {
  const months = [];
  for (let offset = 5; offset >= 0; offset -= 1) {
    const month = new Date(period.getFullYear(), period.getMonth() - offset, 1);
    months.push({
      label: `${month.getMonth() + 1}月`,
      total: sumExpenses(state.entries.filter((item) => isSameMonth(item.occurredAt, month)))
    });
  }
  return months.some((month) => month.total > 0) ? months : [];
}

function renderRules() {
  elements.ruleCategory.innerHTML = [...state.categories]
    .sort((a, b) => a.order - b.order)
    .map((c) => `<option value="${escapeHtml(c.name)}">${escapeHtml(c.name)}</option>`)
    .join("");

  if (!state.userRules.length) {
    elements.ruleList.innerHTML = `<div class="empty-state">还没有自定义规则。比如把“瑞幸”固定识别为餐饮。</div>`;
    return;
  }

  elements.ruleList.innerHTML = state.userRules
    .map(
      (rule) => `
        <div class="rule-item">
          <span>包含 <strong>${escapeHtml(rule.keyword)}</strong> → ${escapeHtml(rule.category)}</span>
          <button type="button" data-id="${rule.id}">删除</button>
        </div>
      `
    )
    .join("");

  elements.ruleList.querySelectorAll("button").forEach((button) => {
    button.addEventListener("click", () => deleteRule(button.dataset.id));
  });
}

function renderBudget() {
  elements.budgetInput.value = state.settings.monthlyBudget || "";

  const monthSpend = sumExpenses(state.entries.filter((item) => isSameMonth(item.occurredAt, new Date())));
  const budget = state.settings.monthlyBudget;
  const percent = budget > 0 ? Math.min((monthSpend / budget) * 100, 100) : 0;
  elements.budgetBar.style.width = `${percent}%`;

  if (budget > 0) {
    elements.budgetCopy.textContent = `本月已使用 ${Math.round(percent)}%，${money.format(monthSpend)} / ${money.format(budget)}。`;
  } else {
    elements.budgetCopy.textContent = "设置预算后，这里会显示本月消耗进度。";
  }
}

function renderAccounts() {
  const sorted = [...state.accounts].sort((a, b) => a.order - b.order);
  elements.accountList.innerHTML = sorted
    .map(
      (account, index) => `
        <div class="entity-item ${account.archived ? "archived" : ""}">
          <div class="entity-main">
            <input class="entity-name-input" type="text" value="${escapeHtml(account.name)}" data-id="${account.id}" aria-label="账户名称" />
            ${account.archived ? `<span class="entity-tag">已归档</span>` : ""}
          </div>
          <div class="entity-actions">
            <button type="button" class="icon-btn chevron-up" data-action="move-up" data-id="${account.id}" aria-label="上移" ${index === 0 ? "disabled" : ""}><svg><use href="./assets/icons.svg#icon-chevron"></use></svg></button>
            <button type="button" class="icon-btn" data-action="move-down" data-id="${account.id}" aria-label="下移" ${index === sorted.length - 1 ? "disabled" : ""}><svg><use href="./assets/icons.svg#icon-chevron"></use></svg></button>
            <button type="button" class="mini-button" data-action="toggle-archive" data-id="${account.id}">${account.archived ? "启用" : "归档"}</button>
          </div>
        </div>
      `
    )
    .join("");
}

function handleAccountListClick(event) {
  const button = event.target.closest("[data-action]");
  if (!button) return;
  const { action, id } = button.dataset;
  if (action === "move-up") moveAccount(id, "up");
  if (action === "move-down") moveAccount(id, "down");
  if (action === "toggle-archive") toggleAccountArchive(id);
}

function handleAccountRenameCommit(event) {
  const input = event.target;
  if (!input.matches?.(".entity-name-input")) return;
  renameAccount(input.dataset.id, input.value);
}

function addAccount() {
  const name = elements.newAccountName.value.trim();
  if (!name) {
    showToast("先填一个账户名称。", "error");
    return;
  }

  const startingBalance = Number(elements.newAccountBalance.value) || 0;
  const maxOrder = state.accounts.reduce((max, a) => Math.max(max, a.order), -1);
  state.accounts.push({
    id: crypto.randomUUID(),
    name,
    sourceTag: name,
    type: "other",
    startingBalance,
    archived: false,
    order: maxOrder + 1,
    createdAt: new Date().toISOString()
  });

  elements.newAccountName.value = "";
  elements.newAccountBalance.value = "";
  saveState();
  showToast("账户已添加。", "success");
}

function renameAccount(id, name) {
  const trimmed = name.trim();
  const account = state.accounts.find((a) => a.id === id);
  if (!account || !trimmed || account.name === trimmed) {
    render();
    return;
  }

  account.name = trimmed;
  saveState();
  showToast("账户名称已更新。", "success");
}

function toggleAccountArchive(id) {
  const account = state.accounts.find((a) => a.id === id);
  if (!account) return;

  if (!account.archived && state.accounts.filter((a) => !a.archived).length <= 1) {
    showToast("至少保留一个启用中的账户。", "error");
    return;
  }

  account.archived = !account.archived;
  saveState();
}

function moveAccount(id, direction) {
  if (!reorderEntities(state.accounts, id, direction)) return;
  saveState();
}

function reorderEntities(list, id, direction) {
  const sorted = [...list].sort((a, b) => a.order - b.order);
  const index = sorted.findIndex((item) => item.id === id);
  const swapIndex = direction === "up" ? index - 1 : index + 1;
  if (index < 0 || swapIndex < 0 || swapIndex >= sorted.length) return false;

  const a = sorted[index];
  const b = sorted[swapIndex];
  const temp = a.order;
  a.order = b.order;
  b.order = temp;
  return true;
}

function renderCategoryManager() {
  const sorted = [...state.categories].sort((a, b) => a.order - b.order);
  elements.categoryList.innerHTML = sorted
    .map(
      (category, index) => `
        <div class="entity-item">
          <span class="entity-icon"><svg><use href="./assets/icons.svg#${categoryIconId(category.name)}"></use></svg></span>
          <div class="entity-main">
            ${
              category.builtin
                ? `<span class="entity-static-name">${escapeHtml(category.name)}</span>`
                : `<input class="entity-name-input" type="text" value="${escapeHtml(category.name)}" data-id="${category.id}" aria-label="分类名称" />`
            }
          </div>
          <div class="entity-actions">
            <button type="button" class="icon-btn chevron-up" data-action="move-up" data-id="${category.id}" aria-label="上移" ${index === 0 ? "disabled" : ""}><svg><use href="./assets/icons.svg#icon-chevron"></use></svg></button>
            <button type="button" class="icon-btn" data-action="move-down" data-id="${category.id}" aria-label="下移" ${index === sorted.length - 1 ? "disabled" : ""}><svg><use href="./assets/icons.svg#icon-chevron"></use></svg></button>
            ${category.builtin ? "" : `<button type="button" class="icon-btn danger" data-action="delete" data-id="${category.id}" aria-label="删除分类"><svg><use href="./assets/icons.svg#icon-trash"></use></svg></button>`}
          </div>
        </div>
      `
    )
    .join("");
}

function handleCategoryListClick(event) {
  const button = event.target.closest("[data-action]");
  if (!button) return;
  const { action, id } = button.dataset;
  if (action === "move-up") moveCategory(id, "up");
  if (action === "move-down") moveCategory(id, "down");
  if (action === "delete") deleteCategory(id);
}

function handleCategoryRenameCommit(event) {
  const input = event.target;
  if (!input.matches?.(".entity-name-input")) return;
  renameCategory(input.dataset.id, input.value);
}

function addCategory() {
  const name = elements.newCategoryName.value.trim();
  if (!name) {
    showToast("先填一个分类名称。", "error");
    return;
  }

  if (state.categories.some((c) => c.name === name)) {
    showToast("这个分类已经存在。", "error");
    return;
  }

  const maxOrder = state.categories.reduce((max, c) => Math.max(max, c.order), -1);
  state.categories.push({ id: crypto.randomUUID(), name, builtin: false, order: maxOrder + 1 });
  elements.newCategoryName.value = "";
  saveState();
  showToast("分类已添加。", "success");
}

function renameCategory(id, name) {
  const trimmed = name.trim();
  const category = state.categories.find((c) => c.id === id);
  if (!category || category.builtin || !trimmed || category.name === trimmed) {
    render();
    return;
  }

  category.name = trimmed;
  saveState();
  showToast("分类名称已更新。", "success");
}

function deleteCategory(id) {
  const category = state.categories.find((c) => c.id === id);
  if (!category || category.builtin) return;
  if (!confirm(`确定删除分类"${category.name}"吗？`)) return;

  state.categories = state.categories.filter((c) => c.id !== id);
  saveState();
  showToast("分类已删除。", "success");
}

function moveCategory(id, direction) {
  if (!reorderEntities(state.categories, id, direction)) return;
  saveState();
}

/**
 * 支出统一走「毛支出 - 退款」的口径。
 * 退款如果只当收入算，退回来的钱会让收入虚增、支出虚高，两头都不对。
 */
function sumExpenses(entries) {
  // 传全量 state.entries 供退款归因，避免跨月退款在退款当月造成负支出。
  return computeRefundOffset(entries, state.entries).netExpenseCents / 100;
}

function sumIncome(entries) {
  return computeRefundOffset(entries).incomeCents / 100;
}

function amountToCents(value) {
  const amount = Number(value || 0);
  return Number.isFinite(amount) ? Math.round(amount * 100) : 0;
}

function getTopCategory(entries) {
  const totals = computeRefundOffset(entries).categories;
  return [...totals.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] || "";
}

function isSameMonth(value, date) {
  const target = new Date(value);
  return target.getFullYear() === date.getFullYear() && target.getMonth() === date.getMonth();
}

function isSameDay(value, date) {
  const target = new Date(value);
  return isSameMonth(value, date) && target.getDate() === date.getDate();
}

function formatDate(value, withYear = false) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "刚刚";
  return date.toLocaleString("zh-CN", {
    year: withYear ? "numeric" : undefined,
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  });
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function pauseToastTimer() {
  if (!elements.toast.classList.contains("show")) return;
  clearTimeout(toastTimer);
  toastRemaining = Math.max(toastDeadline - Date.now(), 800);
}

function resumeToastTimer() {
  if (!elements.toast.classList.contains("show")) return;
  restartToastTimer(toastRemaining || 2600);
}

function restartToastTimer(duration) {
  clearTimeout(toastTimer);
  toastDeadline = Date.now() + duration;
  toastTimer = setTimeout(() => elements.toast.classList.remove("show"), duration);
}

function showToast(message, type = "info", options = {}) {
  const iconId = type === "success" ? "icon-toast-success" : type === "error" ? "icon-toast-error" : "icon-toast-info";
  const undoHtml = options.onUndo ? `<button class="toast-undo" type="button" id="toastUndoBtn">撤销</button>` : "";
  const closeHtml = type === "error" ? `<button class="toast-close" type="button" id="toastCloseBtn" aria-label="关闭提示">关闭</button>` : "";
  elements.toast.innerHTML = `
    <span class="toast-icon"><svg><use href="./assets/icons.svg#${iconId}"></use></svg></span>
    <span class="toast-message">${escapeHtml(message)}</span>
    ${undoHtml}
    ${closeHtml}
  `;
  elements.toast.setAttribute("role", type === "error" ? "alert" : "status");
  elements.toast.setAttribute("aria-live", type === "error" ? "assertive" : "polite");
  elements.toast.className = `toast show ${type}`;
  if (type === "error") {
    clearTimeout(toastTimer);
  } else {
    restartToastTimer(options.duration || 3200);
  }
  if (options.onUndo) {
    const undoButton = $("#toastUndoBtn");
    undoButton.addEventListener("click", () => {
      options.onUndo();
      if (elements.toast.contains(undoButton)) elements.toast.classList.remove("show");
    });
  }
  if (type === "error") {
    $("#toastCloseBtn").addEventListener("click", () => elements.toast.classList.remove("show"));
  }
}

window.__ziji = {
  parseTransaction,
  splitImportText,
  computeAccountBalances,
  getState: () => state
};
