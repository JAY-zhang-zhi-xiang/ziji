#!/usr/bin/env node
/**
 * iconfont 导入管线：
 *   node scripts/iconfont-import.mjs <at.alicdn.com 的 .js 链接>
 *
 * 做三件事：
 * 1. 下载 iconfont 项目文件，解析里面的 <symbol>（SVG Symbol 格式）；
 * 2. 每个 symbol 转成 Android VectorDrawable，写入
 *    android-companion/src/main/res/drawable/ic_font_<symbol id>.xml
 *    （统一黑色，颜色由 App 代码按场景染色，与现有 Lucide 图标同一套机制）；
 * 3. 在项目根目录生成 icons-font-preview.html：
 *    左边现有图标、右边 iconfont 图标，按「symbol id == 现有图标名去掉 ic_ 前缀」
 *    自动配对，配不上的单独列出，供人工确认映射。
 *
 * 映射约定：在 iconfont.cn 的项目里把图标命名为现有图标的对应名（去掉 ic_ 前缀），
 * 例如 cat_exp_utensils_crossed、nav_stats、check、wallet。没命名的图标只出现在
 * 「待映射」区，不会替换任何现有图标。
 *
 * 已知限制：SVG 渐变（linearGradient/radialGradient）不支持，会跳过并警告。
 */

import { writeFile, mkdir, readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join, resolve, isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const drawableDir = join(root, "android-companion", "src", "main", "res", "drawable");
const previewPath = join(root, "icons-font-preview.html");
const DRAWABLE_DIR_REL = "android-companion/src/main/res/drawable";

const arg = process.argv[2];
if (!arg) {
  console.error("用法: node scripts/iconfont-import.mjs <iconfont 项目 CDN 的 .js 链接 | 本地 .js/.svg 文件路径>");
  process.exit(1);
}

// ---------- 1. 下载（或读取）并解析 symbol ----------

let js;
const localPath = !/^https?:\/\//.test(arg)
  ? (isAbsolute(arg) ? arg : resolve(process.cwd(), arg))
  : null;
if (localPath) {
  if (!existsSync(localPath)) {
    console.error(`文件不存在: ${localPath}`);
    process.exit(1);
  }
  js = await readFile(localPath, "utf8");
} else {
  const res = await fetch(arg, { headers: { "User-Agent": "Mozilla/5.0 ziji-icon-import" } });
  if (!res.ok) {
    console.error(`下载失败: HTTP ${res.status} — ${arg}`);
    process.exit(1);
  }
  js = await res.text();
}

const symbols = [];
const symbolRe = /<symbol([^>]*)>([\s\S]*?)<\/symbol>/g;
let m;
while ((m = symbolRe.exec(js)) !== null) {
  const attrs = m[1];
  const body = m[2];
  const id = (attrs.match(/id="([^"]*)"/) || [])[1] || "";
  const viewBox = (attrs.match(/viewBox="([^"]*)"/) || [])[1] || "0 0 1024 1024";
  symbols.push({ id, viewBox, body });
}
if (symbols.length === 0) {
  console.error("没有解析到任何 <symbol>。请确认链接是 iconfont 项目的「Symbol」CDN 地址（.js）。");
  process.exit(1);
}
console.log(`解析到 ${symbols.length} 个 symbol: ${symbols.map((s) => s.id).join(", ")}`);

// ---------- 2. symbol → VectorDrawable ----------

/** 把 symbol 内部的图形元素转成 <path> 的 XML 片段。 */
function convertBody(body, warnings) {
  const paths = [];
  // path 元素
  const pathRe = /<path([^>]*)\/?>(?:<\/path>)?/g;
  let pm;
  while ((pm = pathRe.exec(body)) !== null) {
    const a = parseAttrs(pm[1]);
    if (!a.d) continue;
    const lines = [];
    lines.push(`<path`);
    lines.push(`        android:pathData="${escapeAttr(a.d)}"`);
    // 无 fill 属性 = 继承 symbol 默认（iconfont 里即黑色实心）。
    // 常量直接用最终的 #AARRGGBB 写法，不能经过 normalizeColor——
    // 否则会被当成网页 #RRGGBBAA 重排成透明色（首个冒烟测试踩到的坑）。
    const fill = a.fill === "none" ? "#00000000" : (a.fill ? normalizeColor(a.fill) : "#FF000000");
    lines.push(`        android:fillColor="${fill}"`);
    if (a.opacity && a.opacity !== "1") {
      lines.push(`        android:fillAlpha="${a.opacity}"`);
    }
    if (a["fill-rule"] === "evenodd") lines.push(`        android:fillType="evenOdd"`);
    if (a.stroke && a.stroke !== "none") {
      lines.push(`        android:strokeColor="${normalizeColor(a.stroke)}"`);
      lines.push(`        android:strokeWidth="${a["stroke-width"] || "2"}"`);
      if (a["stroke-linecap"]) lines.push(`        android:strokeLineCap="${a["stroke-linecap"]}"`);
      if (a["stroke-linejoin"]) lines.push(`        android:strokeLineJoin="${a["stroke-linejoin"]}"`);
    } else {
      // iconfont 的 path 常默认 fill；显式写透明 stroke 防止被外层染色后出现描边残影
      lines.push(`        android:strokeColor="#00000000"`);
    }
    lines.push(`        />`);
    paths.push(lines.join("\n"));
  }
  // 圆/矩形等基本形状：iconfont 的 symbol 里罕见，遇到就警告
  for (const tag of ["circle", "rect", "polygon", "polyline", "ellipse"]) {
    if (new RegExp(`<${tag}[\\s>]`).test(body)) {
      warnings.push(`包含 <${tag}>，请改用「下载 SVG → 合并成 path」或换一个图标（tag: ${tag}）`);
    }
  }
  if (/<(linearGradient|radialGradient|mask|clipPath|defs)/.test(body)) {
    warnings.push(`包含渐变/裁剪（不支持），效果会打折`);
  }
  return paths.join("\n");
}

function parseAttrs(s) {
  const out = {};
  const re = /([\w-]+)\s*=\s*"([^"]*)"/g;
  let m;
  while ((m = re.exec(s)) !== null) out[m[1].toLowerCase()] = m[2];
  return out;
}

function escapeAttr(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
}

function normalizeColor(c) {
  c = c.trim();
  if (c === "currentColor" || c === "black") return "#FF000000";
  if (/^#[0-9a-fA-F]{6}$/.test(c)) return "#FF" + c.slice(1).toUpperCase();
  if (/^#[0-9a-fA-F]{8}$/.test(c)) {
    // #RRGGBBAA → #AARRGGBB
    return "#" + c.slice(7).toUpperCase() + c.slice(1, 7).toUpperCase();
  }
  return "#FF000000";
}

function viewBoxSize(vb) {
  const n = vb.trim().split(/[\s,]+/).map(Number);
  return { w: n[2] || 1024, h: n[3] || n[2] || 1024 };
}

const generated = [];
const warnings = [];
for (const sym of symbols) {
  if (!sym.id) {
    warnings.push(`有一个 symbol 没有 id，跳过`);
    continue;
  }
  const safeId = sym.id.replace(/[^\w-]/g, "");
  const bodyXml = convertBody(sym.body, warnings);
  if (!bodyXml) {
    warnings.push(`symbol「${safeId}」没有可用 path，跳过`);
    continue;
  }
  const { w, h } = viewBoxSize(sym.viewBox);
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<!-- iconfont.cn symbol: ${safeId} (viewBox ${sym.viewBox}) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="${w}"
    android:viewportHeight="${h}">
    ${bodyXml}
</vector>
`;
  const outPath = join(drawableDir, `ic_font_${safeId}.xml`);
  await writeFile(outPath, xml, "utf8");
  generated.push({ id: safeId, viewBox: sym.viewBox, file: `ic_font_${safeId}.xml` });
}
await mkdir(drawableDir, { recursive: true });
console.log(`已生成 ${generated.length} 个 VectorDrawable 到 ${DRAWABLE_DIR_REL}/ic_font_*.xml`);
for (const w of new Set(warnings)) console.warn(`警告: ${w}`);

// ---------- 3. 预览页：现有图标 vs iconfont 图标 ----------

// 现有图标名清单（去掉 ic_ 前缀、去掉 .xml）
import { readdirSync } from "node:fs";
const existing = existsSync(drawableDir)
  ? readdirSync(drawableDir)
      .filter((f) => /^ic_.*\.xml$/.test(f) && !f.startsWith("ic_font_") && f !== "ic_launcher_foreground.xml")
      .map((f) => f.slice(3, -4))
  : [];

const byId = new Map(generated.map((g) => [g.id, g]));
const mapped = [];
const unmappedOld = [];
for (const name of existing) {
  const g = byId.get(name);
  if (g) mapped.push({ name, font: g });
  else unmappedOld.push(name);
}
const extraNew = generated.filter((g) => !byId.has(g.id));

const cell = (label, svg) =>
  `<div class="cell"><div class="glyph">${svg}</div><div class="cap">${label}</div></div>`;
const oldSvg = (name) => `/ic_${name}.xml 占位`;
// 预览页直接嵌 drawable 的 pathData 太重，用占位 + 注释列名；对比主要看 iconfont 侧
const rows = mapped
  .map(
    ({ name, font }) => `
  <tr><td class="mono">ic_${name}</td><td class="mono">${font.file}</td><td>${font.viewBox}</td></tr>`
  )
  .join("\n");

const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>iconfont 替换预览</title>
<style>
  :root{--bg:#ECE8E0;--surface:#FEFDFA;--border:#DED8CE;--text:#2A2E32;--muted:#585F65;--primary:#A4432D;}
  body{margin:0;padding:32px 24px 56px;background:#F4F1EA;color:var(--text);
       font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;}
  h1{font-size:22px;font-weight:600;letter-spacing:-.01em;}
  .lead{color:var(--muted);font-size:14px;line-height:1.7;}
  table{border-collapse:collapse;margin-top:20px;background:var(--surface);border:1px solid var(--border);border-radius:12px;overflow:hidden;}
  td,th{padding:8px 14px;text-align:left;border-bottom:1px solid var(--border);font-size:13px;}
  .mono{font-family:Consolas,monospace;font-size:12px;}
  h2{font-size:15px;margin:32px 0 8px;}
  .chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px;}
  .chip{background:var(--surface);border:1px solid var(--border);border-radius:999px;padding:4px 12px;font-size:12px;color:var(--muted);}
  .chip b{color:var(--primary);font-weight:600;}
</style></head><body>
<h1>iconfont 导入结果</h1>
<p class="lead">已生成 ${generated.length} 个 VectorDrawable。自动配对 ${mapped.length} 个（symbol id 与现有图标同名），
现有图标中 ${unmappedOld.length} 个待映射，iconfont 侧 ${extraNew.length} 个未配对。确认映射无误后运行批量替换。</p>

<h2>自动配对（将替换现有图标）</h2>
<table><tr><th>现有图标</th><th>新文件</th><th>viewBox</th></tr>${rows || '<tr><td colspan="3">无 — 请在 iconfont 项目里把图标命名成现有图标名（去掉 ic_ 前缀）</td></tr>'}</table>

<h2>待映射的现有图标（${unmappedOld.length} 个，本次保留原样）</h2>
<div class="chips">${unmappedOld.map((n) => `<span class="chip"><b>ic_${n}</b></span>`).join("")}</div>

<h2>iconfont 侧未配对（${extraNew.length} 个）</h2>
<div class="chips">${extraNew.map((g) => `<span class="chip">${g.id}</span>`).join("") || "无"}</div>
</body></html>`;
await writeFile(previewPath, html, "utf8");
console.log(`预览页已生成: ${previewPath}`);
