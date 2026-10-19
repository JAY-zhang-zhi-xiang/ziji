const CACHE_NAME = "ziji-ledger-cache-v35";
const ASSETS = [
  "./",
  "./index.html",
  "./styles.css?v=17",
  "./app.js?v=32",
  "./parser.js?v=15",
  "./refund.js?v=2",
  "./csv.js?v=1",
  "./manifest.webmanifest",
  "./assets/icon.svg"
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;
  if (new URL(event.request.url).pathname.startsWith("/api/")) return;

  // 网络优先 + 缓存兜底（本地服务经常就在同一台机器上，网络基本总是更快）。
  // 原来 fetch 失败且缓存里也没有时会 respondWith(undefined)，浏览器只能报错，
  // 手机断开局域网后打开 App 就是一片白屏。这里给导航请求退回首页，
  // 其余请求返回一段可读的提示。
  event.respondWith(
    (async () => {
      try {
        const response = await fetch(event.request);
        if (response && response.ok) {
          const copy = response.clone();
          const cache = await caches.open(CACHE_NAME);
          cache.put(event.request, copy).catch(() => {});
        }
        return response;
      } catch {
        const cached = await caches.match(event.request);
        if (cached) return cached;
        if (event.request.mode === "navigate") {
          const shell = await caches.match("./index.html");
          if (shell) return shell;
        }
        return new Response("离线：暂时连不上自记服务。", {
          status: 503,
          headers: { "Content-Type": "text/plain; charset=utf-8" }
        });
      }
    })()
  );
});
