// weStream — local HTTP API client.
// Talks to this node's Java engine (the pure-JDK ApiServer) over loopback.
//
// Base URL resolution, in priority order:
//   1. window.westream.apiBase / .apiPort  — injected by Electron preload (Increment 7)
//   2. ?api=<port> query param             — handy in the Vite dev browser
//   3. http://127.0.0.1:11470              — the seed node's default API port
// (The Java side sends permissive CORS, so cross-origin dev/file:// fetches work.)

function resolveBase() {
  if (typeof window !== "undefined") {
    const ws = window.westream;
    if (ws?.apiBase) return ws.apiBase;
    if (ws?.apiPort) return `http://127.0.0.1:${ws.apiPort}`;
    const q = new URLSearchParams(window.location.search).get("api");
    if (q) return `http://127.0.0.1:${q}`;
  }
  return "http://127.0.0.1:11470";
}

export const API_BASE = resolveBase();

async function getJson(path) {
  const res = await fetch(API_BASE + path);
  if (!res.ok) throw new Error(`GET ${path} → ${res.status}`);
  return res.json();
}

async function postJson(path, body) {
  const res = await fetch(API_BASE + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} → ${res.status}`);
  return res.json();
}

// ---- read endpoints ----
export const getStatus = () => getJson("/api/status");
export const getRouting = () => getJson("/api/routing");
export const dhtGet = (key) => getJson(`/api/dht/get?key=${encodeURIComponent(key)}`);
export const getProgress = (infohash) =>
  getJson(`/api/progress?infohash=${encodeURIComponent(infohash)}`);
export const getTransfers = () => getJson("/api/transfers");
export const getRpcLog = () => getJson("/api/rpclog");

// ---- action endpoints ----
export const dhtPut = (key, value) => postJson("/api/dht/put", { key, value });
export const share = (path) => postJson("/api/share", { path });
export const startDownload = (infohash, out) =>
  postJson("/api/download", out ? { infohash, out } : { infohash });

// ---- video stream URL for a <video src> ----
export const streamUrl = (infohash) => `${API_BASE}/stream/${infohash}`;
