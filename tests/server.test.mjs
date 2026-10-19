import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile, readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createLedgerServer, loadOrCreateBridgeToken, resolveBridgeToken } from "../server.js";

const tempDir = await mkdtemp(join(tmpdir(), "ziji-ledger-"));
const dataFile = join(tempDir, "captures.json");
const token = "test-token";

await writeFile(dataFile, "{not valid json", "utf8");

const server = createLedgerServer({ dataFile, token });

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const { port } = server.address();
const baseUrl = `http://127.0.0.1:${port}`;

try {
  const health = await request("/api/health");
  assert.equal(health.status, 200);
  assert.equal(health.body.ok, true);
  assert.equal(health.body.requiresToken, true);
  assert.equal(health.body.authorized, false);

  const authorizedHealth = await request("/api/health", { headers: authHeaders() });
  assert.equal(authorizedHealth.status, 200);
  assert.equal(authorizedHealth.body.ok, true);
  assert.equal(authorizedHealth.body.requiresToken, true);
  assert.equal(authorizedHealth.body.authorized, true);

  const wrongTokenHealth = await request("/api/health", { headers: { "X-Ziji-Token": "wrong-token" } });
  assert.equal(wrongTokenHealth.status, 200);
  assert.equal(wrongTokenHealth.body.ok, true);
  assert.equal(wrongTokenHealth.body.requiresToken, true);
  assert.equal(wrongTokenHealth.body.authorized, false);

  const unauthorized = await request("/api/captures");
  assert.equal(unauthorized.status, 401);

  const queryTokenUnauthorized = await request("/api/captures?token=test-token");
  assert.equal(queryTokenUnauthorized.status, 401);

  const created = await request("/api/capture", {
    method: "POST",
    headers: jsonHeaders(),
    body: JSON.stringify({
      source: "android-notification",
      text: "支付宝 06月06日 12:31 向 星巴克 付款 32.00元 付款方式 花呗"
    })
  });
  assert.equal(created.status, 201);
  assert.equal(created.body.added, 1);
  assert.equal(created.body.captures[0].parsed.amount, 32);
  assert.equal(created.body.captures[0].parsed.merchant, "星巴克");

  // 起始文件是坏的：第一次写入不能把坏内容复制成 .bak，
  // 否则唯一一份好备份就被冲掉了。
  const backupsAfterFirstWrite = (await readdir(tempDir)).filter((name) => name.endsWith(".bak"));
  assert.equal(backupsAfterFirstWrite.length, 0, "corrupt file must not become the backup");

  const duplicate = await request("/api/capture", {
    method: "POST",
    headers: jsonHeaders(),
    body: JSON.stringify({
      source: "android-notification",
      text: "支付宝 06月06日 12:31 向 星巴克 付款 32.00元 付款方式 花呗"
    })
  });
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.body.added, 0);
  assert.equal(duplicate.body.duplicates, 1);
  assert.equal(duplicate.body.captures.length, 0);

  const concurrent = await Promise.all([
    request("/api/capture", {
      method: "POST",
      headers: jsonHeaders(),
      body: JSON.stringify({ source: "test", text: "微信支付 06月06日 12:40 向 便利店 付款 11.11元" })
    }),
    request("/api/capture", {
      method: "POST",
      headers: jsonHeaders(),
      body: JSON.stringify({ source: "test", text: "微信支付 06月06日 12:41 向 便利店 付款 22.22元" })
    })
  ]);
  assert.equal(concurrent.reduce((sum, result) => sum + result.body.added, 0), 2);

  const backupFiles = (await readdir(tempDir)).filter((name) => name.includes("corrupt-"));
  assert.equal(backupFiles.length, 1);

  const restoredFile = JSON.parse(await readFile(dataFile, "utf8"));
  assert.equal(restoredFile.length, 3);

  const captures = await request("/api/captures", { headers: authHeaders() });
  assert.equal(captures.status, 200);
  assert.equal(captures.body.captures.length, 3);

  for (const capture of captures.body.captures) {
    const claimed = await request(`/api/captures/${capture.id}/claim`, { method: "POST", headers: authHeaders() });
    assert.equal(claimed.status, 200);
    assert.equal(claimed.body.ok, true);
  }

  const afterClaim = await request("/api/captures", { headers: authHeaders() });
  assert.equal(afterClaim.status, 200);
  assert.equal(afterClaim.body.captures.length, 0);

  assert.equal(resolveBridgeToken({ token: "explicit" }), "explicit");
  assert.equal(resolveBridgeToken({ anonymous: true }), "");
  assert.equal(resolveBridgeToken({ anonymous: true, token: "explicit" }), "");
  const generated = resolveBridgeToken({});
  assert.match(generated, /^ziji-[0-9a-f]{32}$/);
  assert.notEqual(generated, resolveBridgeToken({}));

  // token 必须持久化：否则每次重启都换新 token，手机里存的旧 token 立刻失效。
  const tokenFile = join(tempDir, "bridge-token.txt");
  const firstToken = await loadOrCreateBridgeToken({}, tokenFile);
  assert.match(firstToken, /^ziji-[0-9a-f]{32}$/);
  assert.equal(await loadOrCreateBridgeToken({}, tokenFile), firstToken);
  assert.equal((await readFile(tokenFile, "utf8")).trim(), firstToken);
  assert.equal(await loadOrCreateBridgeToken({ token: "explicit" }, tokenFile), "explicit");
  assert.equal(await loadOrCreateBridgeToken({ anonymous: true }, tokenFile), "");

  // 数据目录不能被静态服务直接读走：token 保护的是 /api/，不是文件路径。
  const leakedData = await fetch(`${baseUrl}/data/auto-captures.json`);
  assert.equal(leakedData.status, 404);
  const leakedToken = await fetch(`${baseUrl}/data/bridge-token.txt`);
  assert.equal(leakedToken.status, 404);
  const leakedBackup = await fetch(`${baseUrl}/data/auto-captures.json.bak`);
  assert.equal(leakedBackup.status, 404);
  // Windows / macOS 不区分大小写，这条路径同样会落到真实的 data 目录。
  const caseBypass = await fetch(`${baseUrl}/Data/auto-captures.json`);
  assert.equal(caseBypass.status, 404);

  const sameOrigin = await rawRequest("/api/health", { headers: { Origin: baseUrl } });
  assert.equal(sameOrigin.headers.get("access-control-allow-origin"), baseUrl);
  assert.equal(sameOrigin.headers.get("vary"), "Origin");

  const crossOrigin = await rawRequest("/api/health", { headers: { Origin: "http://evil.example" } });
  assert.equal(crossOrigin.headers.get("access-control-allow-origin"), null);

  const noOrigin = await rawRequest("/api/health");
  assert.equal(noOrigin.headers.get("access-control-allow-origin"), null);

  console.log("server tests passed: 1");
} finally {
  await new Promise((resolve) => server.close(resolve));
  await rm(tempDir, { recursive: true, force: true });
}

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, options);
  const text = await response.text();
  return {
    status: response.status,
    body: text ? JSON.parse(text) : null
  };
}

async function rawRequest(path, options = {}) {
  return fetch(`${baseUrl}${path}`, options);
}

function authHeaders() {
  return { "X-Ziji-Token": token };
}

function jsonHeaders() {
  return { ...authHeaders(), "Content-Type": "application/json" };
}
