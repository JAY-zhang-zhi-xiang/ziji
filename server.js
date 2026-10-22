import { createServer } from "node:http";
import { readFile, writeFile, mkdir, stat, rename, copyFile, open } from "node:fs/promises";
import { createReadStream } from "node:fs";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { dirname, extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseTransaction, splitImportText } from "./parser.js";

const ROOT = fileURLToPath(new URL(".", import.meta.url));
const DEFAULT_DATA_FILE = join(ROOT, "data", "auto-captures.json");
const DEFAULT_TOKEN_FILE = join(ROOT, "data", "bridge-token.txt");
const mutationLocks = new Map();
const MIME_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".apk": "application/vnd.android.package-archive",
  ".svg": "image/svg+xml; charset=utf-8",
  ".txt": "text/plain; charset=utf-8"
};

const options = parseArgs(process.argv.slice(2));

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  // 以前每次启动都会生成新 token：电脑重启一次，手机里存的旧 token 立刻失效，
  // 自动记账全部 401 且没有任何提示。现在生成后落到 data/bridge-token.txt 复用。
  options.token = await loadOrCreateBridgeToken(options);
  const server = createLedgerServer(options);
  server.listen(options.port, options.host, () => {
    console.log(`自记本地服务已启动：http://${options.host}:${options.port}`);
    console.log(`自动捕获接口：POST http://${options.host}:${options.port}/api/capture`);
    if (options.token) {
      console.log(`访问令牌：${options.token}`);
      console.log(`令牌文件：${options.tokenFile || DEFAULT_TOKEN_FILE}（重启后保持不变）`);
    } else {
      console.log("访问令牌：已关闭，请只在可信局域网内使用。");
    }
  });
}

export function resolveBridgeToken(config = {}) {
  if (config.anonymous) return "";
  return config.token || `ziji-${randomUUID().replace(/-/g, "")}`;
}

/**
 * 取 token：显式指定 > 已保存 > 新生成（并保存）。
 *
 * 只随机不保存会让「手机端 token」在每次重启后失效，这是最容易踩的坑。
 */
export async function loadOrCreateBridgeToken(config = {}, tokenFile = DEFAULT_TOKEN_FILE) {
  if (config.anonymous) return "";
  if (config.token) return config.token;
  try {
    const saved = (await readFile(tokenFile, "utf8")).trim();
    if (saved) return saved;
  } catch {
    // 首次运行还没有文件，继续生成。
  }
  const created = resolveBridgeToken(config);
  try {
    await mkdir(dirname(tokenFile), { recursive: true });
    await writeFile(tokenFile, `${created}\n`, "utf8");
  } catch {
    // 目录只读时退化成「每次随机」，不影响服务启动。
  }
  return created;
}

export function createLedgerServer(config = {}) {
  const host = config.host || "127.0.0.1";
  const port = Number(config.port || 5188);
  const dataFile = resolve(config.dataFile || DEFAULT_DATA_FILE);
  const token = String(config.token || "");

  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url || "/", `http://${host}:${port}`);
      if (url.pathname.startsWith("/api/")) {
        await handleApi(request, response, url, { dataFile, token });
        return;
      }
      await serveStatic(response, url.pathname);
    } catch (error) {
      const status = error.statusCode || 500;
      sendJson(response, status, { error: error.code || "server_error", message: error.message });
    }
  });

  return server;
}

async function handleApi(request, response, url, context) {
  setCorsHeaders(response, request);

  if (request.method === "OPTIONS") {
    response.writeHead(204);
    response.end();
    return;
  }

  if (request.method === "GET" && url.pathname === "/api/health") {
    const requiresToken = Boolean(context.token);
    sendJson(response, 200, {
      ok: true,
      service: "ziji-ledger-bridge",
      requiresToken,
      authorized: !requiresToken || isAuthorized(request, url, context.token)
    });
    return;
  }

  if (!isAuthorized(request, url, context.token)) {
    sendJson(response, 401, { error: "unauthorized" });
    return;
  }

  if (request.method === "POST" && url.pathname === "/api/capture") {
    const payload = await readRequestPayload(request);
    const text = extractCaptureText(payload);
    if (!text.trim()) {
      sendJson(response, 400, { error: "missing_text" });
      return;
    }

    const rows = splitImportText(text);
    if (!rows.length) {
      sendJson(response, 422, { error: "no_bill_rows" });
      return;
    }

    const result = await withMutationLock(context.dataFile, async () => {
      const captures = await readCaptures(context.dataFile);
      const stored = [];
      let duplicates = 0;

      for (let index = 0; index < rows.length; index += 1) {
        const row = rows[index];
        const captureId = payload.captureId
          ? `${String(payload.captureId)}${rows.length > 1 ? `:${index}` : ""}`
          : "";
        if (hasPendingDuplicate(captures, row, payload.source || "bridge", captureId)) {
          duplicates += 1;
          continue;
        }

        const capture = {
          id: createId(),
          clientId: captureId,
          text: row,
          source: payload.source || "bridge",
          parsed: parseTransaction(row),
          claimed: false,
          createdAt: new Date().toISOString()
        };
        captures.unshift(capture);
        stored.push(capture);
      }

      await writeCaptures(context.dataFile, captures);
      return { stored, duplicates };
    });
    const { stored, duplicates } = result;
    sendJson(response, stored.length > 0 ? 201 : 200, { ok: true, added: stored.length, duplicates, captures: stored });
    return;
  }

  if (request.method === "GET" && url.pathname === "/api/captures") {
    const captures = await readCaptures(context.dataFile);
    const pending = captures.filter((capture) => !capture.claimed);
    sendJson(response, 200, { ok: true, captures: pending });
    return;
  }

  const claimMatch = url.pathname.match(/^\/api\/captures\/([^/]+)\/claim$/);
  if (request.method === "POST" && claimMatch) {
    const id = decodeURIComponent(claimMatch[1]);
    const claimed = await withMutationLock(context.dataFile, async () => {
      const captures = await readCaptures(context.dataFile);
      const capture = captures.find((item) => item.id === id);
      if (!capture) return false;
      capture.claimed = true;
      capture.claimedAt = new Date().toISOString();
      await writeCaptures(context.dataFile, captures);
      return true;
    });
    if (!claimed) {
      sendJson(response, 404, { error: "not_found" });
      return;
    }
    sendJson(response, 200, { ok: true });
    return;
  }

  sendJson(response, 404, { error: "not_found" });
}

function withMutationLock(dataFile, task) {
  const previous = mutationLocks.get(dataFile) || Promise.resolve();
  const current = previous.catch(() => {}).then(task);
  mutationLocks.set(dataFile, current);
  return current.finally(() => {
    if (mutationLocks.get(dataFile) === current) mutationLocks.delete(dataFile);
  });
}

async function serveStatic(response, pathname) {
  const cleanPath = pathname === "/" ? "/index.html" : pathname;
  let relative;
  try {
    relative = normalize(decodeURIComponent(cleanPath)).replace(/^[\\/]+/, "");
  } catch {
    sendJson(response, 400, { error: "bad_path" });
    return;
  }

  // 数据目录与备份不对外暴露。
  // 之前 /data/auto-captures.json 是可以直接下载的：token 只保护了 /api/，
  // 同一局域网内任何人知道地址就能拿到整份账单。
  if (isPrivateAsset(relative)) {
    sendJson(response, 404, { error: "not_found" });
    return;
  }

  const filePath = resolve(ROOT, relative);
  if (!filePath.startsWith(ROOT)) {
    sendJson(response, 403, { error: "forbidden" });
    return;
  }

  try {
    const info = await stat(filePath);
    if (!info.isFile()) throw new Error("Not a file");
    response.writeHead(200, {
      "Content-Type": MIME_TYPES[extname(filePath)] || "application/octet-stream",
      "Cache-Control": "no-cache"
    });
    createReadStream(filePath).pipe(response);
  } catch {
    sendJson(response, 404, { error: "not_found" });
  }
}

/** 静态资源里不允许访问的路径：账单数据、token、备份与临时文件。 */
function isPrivateAsset(relativePath) {
  const segments = relativePath.split(/[\\/]+/).filter(Boolean);
  if (!segments.length) return false;
  // 必须忽略大小写：Windows / macOS 的文件系统不区分大小写，
  // /Data/auto-captures.json 一样能落到真实的 data 目录。
  if (segments[0].toLowerCase() === "data") return true;
  return segments.some((segment) => {
    const lowered = segment.toLowerCase();
    return (
      lowered.startsWith(".") ||
      lowered.endsWith(".bak") ||
      lowered.includes(".tmp-") ||
      lowered.includes(".corrupt-")
    );
  });
}

async function readRequestPayload(request) {
  const body = await readBody(request);
  const type = String(request.headers["content-type"] || "");
  if (type.includes("application/json")) {
    try {
      return JSON.parse(body || "{}");
    } catch {
      return {};
    }
  }
  return { text: body };
}

function extractCaptureText(payload) {
  return String(payload.text || payload.body || payload.message || payload.content || "");
}

async function readBody(request) {
  const BODY_LIMIT = 64 * 1024;
  const chunks = [];
  let total = 0;
  for await (const chunk of request) {
    total += chunk.length;
    if (total > BODY_LIMIT) {
      request.destroy();
      const err = new Error("payload_too_large");
      err.statusCode = 413;
      err.code = "payload_too_large";
      throw err;
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function readCaptures(dataFile) {
  try {
    const raw = await readFile(dataFile, "utf8");
    const data = JSON.parse(raw);
    return Array.isArray(data) ? data : [];
  } catch (error) {
    try {
      const raw = await readFile(dataFile, "utf8");
      if (raw.trim()) {
        await quarantineCorruptFile(dataFile, raw);
      }
    } catch {
      // Ignore missing or unreadable files; the caller will continue with an empty queue.
    }
    return [];
  }
}

async function writeCaptures(dataFile, captures) {
  const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
  const pruned = captures.filter(
    (c) => !c.claimed || !c.claimedAt || new Date(c.claimedAt).getTime() > cutoff
  );
  await mkdir(dirname(dataFile), { recursive: true });
  if (await exists(dataFile)) {
    // 只有当前文件还能解析时才覆盖 .bak。
    // 否则一旦文件损坏，下一次写入会把仅存的那份好备份也冲成坏文件。
    const current = await readFile(dataFile, "utf8").catch(() => null);
    if (isValidJsonArray(current)) {
      await copyFile(dataFile, `${dataFile}.bak`).catch(() => {});
    }
  }
  const tempFile = `${dataFile}.tmp-${process.pid}-${Date.now()}`;
  const handle = await open(tempFile, "w");
  try {
    await handle.writeFile(`${JSON.stringify(pruned, null, 2)}\n`, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(tempFile, dataFile);
}

function sendJson(response, status, payload) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store"
  });
  response.end(JSON.stringify(payload));
}

function setCorsHeaders(response, request) {
  const origin = String(request.headers.origin || "");
  const host = String(request.headers.host || "");
  if (origin && (origin === `http://${host}` || origin === `https://${host}`)) {
    response.setHeader("Access-Control-Allow-Origin", origin);
  }
  response.setHeader("Vary", "Origin");
  response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Ziji-Token");
}

function isAuthorized(request, url, token) {
  if (!token) return true;
  const auth = String(request.headers.authorization || "");
  const bearer = auth.toLowerCase().startsWith("bearer ") ? auth.slice(7) : "";
  const candidates = [request.headers["x-ziji-token"] || "", bearer];
  return candidates.some((candidate) => constantTimeEqual(candidate, token));
}

function constantTimeEqual(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

function hasPendingDuplicate(captures, text, source = "bridge", clientId = "") {
  const signature = normalizeCaptureText(text);
  const now = Date.now();
  return captures.some((capture) => {
    if (clientId) return capture.clientId === clientId && capture.source === source;
    if (capture.claimed) return false;
    // Legacy clients have no event ID. Only collapse immediate retransmits;
    // identical transactions later in the day must remain separate captures.
    return normalizeCaptureText(capture.text || capture.sourceText || "") === signature
      && now - new Date(capture.createdAt).getTime() <= 2 * 60 * 1000;
  });
}

function normalizeCaptureText(text) {
  return String(text || "").replace(/\s+/g, " ").trim();
}

function createId() {
  return globalThis.crypto?.randomUUID?.() || `capture-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function isValidJsonArray(raw) {
  if (!raw) return false;
  try {
    return Array.isArray(JSON.parse(raw));
  } catch {
    return false;
  }
}

async function exists(path) {  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

async function quarantineCorruptFile(dataFile, raw) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  const quarantinePath = `${dataFile}.corrupt-${timestamp}`;
  await writeFile(quarantinePath, raw, "utf8");
}

function parseArgs(args) {
  const config = {
    host: "127.0.0.1",
    port: 5188,
    dataFile: DEFAULT_DATA_FILE,
    token: process.env.ZIJI_TOKEN || "",
    anonymous: false
  };

  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--host") config.host = args[++index] || config.host;
    if (arg === "--port") config.port = Number(args[++index] || config.port);
    if (arg === "--data") config.dataFile = args[++index] || config.dataFile;
    if (arg === "--token") config.token = args[++index] || config.token;
    if (arg === "--no-token") config.anonymous = true;
  }

  return config;
}
