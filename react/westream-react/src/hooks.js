// weStream — live-data React hooks + transforms.
// Poll the local engine API on an interval and shape the JSON into the exact
// objects the (mock-authored) screens already render, so wiring a screen "live"
// is a data-source swap, not a rewrite. Every hook degrades gracefully: until the
// first successful fetch (e.g. the engine is still booting) `data` is null and the
// screen falls back to its mock.

import { useEffect, useRef, useState } from "react";
import { getStatus, getRouting, getProgress, getTransfers, getRpcLog, getDhtKeys } from "./api";

/**
 * Poll `fetchFn` every `intervalMs`. Returns { data, error }. Uses a setTimeout
 * chain (not setInterval) so a slow request never stacks, and cleans up on unmount.
 */
export function usePoll(fetchFn, intervalMs) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const fnRef = useRef(fetchFn);
  fnRef.current = fetchFn;

  useEffect(() => {
    let alive = true;
    let timer;
    const tick = async () => {
      try {
        const d = await fnRef.current();
        if (alive) {
          setData(d);
          setError(null);
        }
      } catch (e) {
        if (alive) setError(e);
      }
      if (alive) timer = setTimeout(tick, intervalMs);
    };
    tick();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [intervalMs]);

  return { data, error };
}

export const useStatus = () => usePoll(getStatus, 1000);
export const useRouting = () => usePoll(getRouting, 1000);

/**
 * Track the window's inner width so a screen can collapse side panels on a
 * narrow window (the user tiles 3 nodes side-by-side on one display).
 */
export function useWindowWidth() {
  const [w, setW] = useState(typeof window !== "undefined" ? window.innerWidth : 1340);
  useEffect(() => {
    const onResize = () => setW(window.innerWidth);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return w;
}

/** Poll a download's live progress at 500ms (faster — it drives the piece strip). No-op when infohash is null. */
export const useProgress = (infohash) =>
  usePoll(() => (infohash ? getProgress(infohash) : Promise.resolve(null)), 500);

/** Poll the Library list (files this node seeds/downloads) at 1.5s. */
export const useTransfers = () => usePoll(getTransfers, 1500);

/** Poll the RPC activity log at 1s (drives the DHT inspector's live feed). */
export const useRpcLog = () => usePoll(getRpcLog, 1000);

/** Poll the local DHT store snapshot at 2s (drives the inspector's "Stored keys" panel). */
export const useDhtKeys = () => usePoll(getDhtKeys, 2000);

/**
 * Node-wide down/up throughput in bytes/sec, derived from successive /api/status
 * samples (the engine exposes cumulative upBytes/downBytes; the rate is the delta
 * over the poll gap). Fed the EXISTING useStatus data, so it adds no extra poll.
 * Returns null until it has two samples to diff; the rate is a coarse ~1s average.
 */
export function useThroughput(statusData) {
  const prev = useRef(null);
  const [rate, setRate] = useState(null);
  useEffect(() => {
    if (!statusData || statusData.upBytes == null) return;
    const now = { up: statusData.upBytes, down: statusData.downBytes, t: Date.now() };
    const p = prev.current;
    if (p && now.t > p.t) {
      const dt = (now.t - p.t) / 1000;
      setRate({
        down: Math.max(0, (now.down - p.down) / dt),
        up: Math.max(0, (now.up - p.up) / dt),
      });
    }
    prev.current = now;
  }, [statusData]);
  return rate;
}

/** /api/dht/keys → the Stored-keys rows the DHT inspector renders (key id + honest placeholders). */
export function storedKeysFrom(dht) {
  return (dht.keys || []).map((k) => ({
    key: shortId(k.key),
    value: "stored value",     // values are raw bytes server-side; not surfaced
    ttl: "—",                  // the bounded store has no TTL yet (no republish)
  }));
}

const RPC_TYPE_COLOR = {
  FIND_NODE: "#c08fe8", FIND_VALUE: "#c08fe8", STORE: "#f4bf4f",
  PING: "#6cc8e8", NODES: "#74e3b0", VALUE: "#74e3b0", PONG: "#6cc8e8", STORED: "#74e3b0",
};

/** /api/rpclog events (already newest-first) → the rpcLog row shape the DHT screen renders. */
export function rpcLogFromEvents(events) {
  return events.map((e) => ({
    time: e.time, dir: e.dir, type: e.type, peer: e.peer, result: e.detail || "",
    dirColor: e.dir === "→" ? "#ee7fb0" : "#6cc8e8",
    typeColor: RPC_TYPE_COLOR[e.type] || "#b3aac0",
    resultColor: e.detail === "timeout" ? "#f0795e" : "#6b6379",
  }));
}

/** bytes → "1.74 GB" / "691 MB" / "480 KB". */
export function humanBytes(n) {
  if (n >= 1 << 30) return (n / (1 << 30)).toFixed(2) + " GB";
  if (n >= 1 << 20) return (n / (1 << 20)).toFixed(0) + " MB";
  if (n >= 1 << 10) return (n / (1 << 10)).toFixed(0) + " KB";
  return n + " B";
}

/**
 * /api/transfers rows → the MediaCard shape the Library renders, split into
 * { shares, downloads }. Mirrors the mock status/colour helpers from data.js.
 */
export function libraryFromTransfers(transfers) {
  const POSTER = "linear-gradient(135deg,#2c1838,#15111d)";
  const toCard = (t) => {
    const pct = t.total > 0 ? Math.round((t.have / t.total) * 100) + "%" : "0%";
    const ext = (t.name.includes(".") ? t.name.split(".").pop() : "FILE").toUpperCase().slice(0, 5);
    let status;
    if (t.seeding) status = { status: "▲ Seeding", statusColor: "#74e3b0", statusBg: "rgba(70,211,154,0.14)", prog: "100%", progColor: "#46d39a" };
    else if (t.complete) status = { status: "✓ Complete", statusColor: "#a99fbb", statusBg: "rgba(120,108,140,0.18)", prog: "100%", progColor: "#6cc8e8" };
    else status = { status: pct + " ↓", statusColor: "#e7b0f0", statusBg: "rgba(198,79,240,0.16)", prog: pct, progColor: "linear-gradient(90deg,#9b3ec9,#c64ff0)" };
    return {
      title: t.name,
      res: ext,
      size: humanBytes(t.totalLength),
      peersLabel: t.peers + " peers",
      posterBg: POSTER,
      infohash: t.infohash,
      ...status,
    };
  };
  return {
    shares: transfers.filter((t) => t.seeding).map(toCard),
    downloads: transfers.filter((t) => !t.seeding).map(toCard),
  };
}

// ----------------------------------------------------------------- transforms

const BUCKET_GRADIENT = "linear-gradient(90deg,#9b3ec9,#c64ff0)";

/** /api/routing bucketSizes[160] → the non-empty k-bucket rows the DHT screen renders. */
export function bucketsFromSizes(sizes) {
  return sizes
    .map((count, idx) => ({ idx, count }))
    .filter((b) => b.count > 0)
    .sort((a, b) => b.idx - a.idx)
    .map((b) => ({
      idx: b.idx,
      count: b.count,
      fill: Math.round((b.count / 20) * 100) + "%",
      barColor: b.count >= 18 ? BUCKET_GRADIENT : b.count >= 8 ? "#9b6cd0" : "#5f5670",
    }));
}

/** 40-hex id → "4287ad37 811db73f …" (groups of 8), matching the design. */
export function formatId(hex) {
  return (hex.match(/.{1,8}/g) || [hex]).join(" ");
}

/** 40-hex id → short "4287ad37…d54569e" for the sidebar/title chips. */
export function shortId(hex) {
  return hex.length > 15 ? `${hex.slice(0, 8)}…${hex.slice(-7)}` : hex;
}

// Peer cosmetics derived deterministically from the contact id (same idea as the
// JavaFX swarm view). The engine has no per-peer throughput/latency/bitfield
// metering yet, so down/up/latency are honestly "—"; tint/glyph/have% are a
// stable visual hash so the map looks alive.
const TINTS = [
  ["#c64ff0", "rgba(198,79,240,0.5)"],
  ["#6cc8e8", "rgba(108,200,232,0.45)"],
  ["#ee7fb0", "rgba(238,127,176,0.4)"],
  ["#46d39a", "rgba(70,211,154,0.4)"],
  ["#f4bf4f", "rgba(244,191,79,0.35)"],
  ["#9b8cf0", "rgba(155,140,240,0.4)"],
];

function hashHex(id) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return h;
}

// Per-piece state byte → strip colour: 0 missing, 1 in-flight, 2 have.
const PIECE_COLORS = ["#2a2435", "#6cc8e8", "#c64ff0"];

/**
 * /api/progress pieceStates → the strip's {idx,color} bars. Caps the bar count
 * (a real file has thousands of pieces): when over maxBars, downsample into
 * buckets — a bucket shows the WORST state it covers (missing > in-flight > have),
 * so any gap stays visible.
 */
export function stripFromProgress(progress, maxBars = 96) {
  const states = progress.pieceStates || [];
  const n = states.length;
  if (n === 0) return [];
  if (n <= maxBars) return states.map((s, i) => ({ idx: i, color: PIECE_COLORS[s] }));
  const bars = [];
  const per = n / maxBars;
  for (let b = 0; b < maxBars; b++) {
    const start = Math.floor(b * per);
    const end = Math.floor((b + 1) * per);
    let worst = 2;
    for (let i = start; i < end; i++) worst = Math.min(worst, states[i]);
    bars.push({ idx: start, color: PIECE_COLORS[worst] });
  }
  return bars;
}

/** /api/routing contacts → the swarm-node shape SwarmScreen renders (polar layout + cosmetics). */
export function buildSwarmFrom(contacts) {
  const n = Math.max(1, contacts.length);
  return contacts.map((c, i) => {
    const [tint, glow] = TINTS[hashHex(c.id) % TINTS.length];
    const ring = i % 2;
    const rx = ring ? 41 : 26;
    const ry = ring ? 43 : 30;
    const ang = (i / n) * Math.PI * 2 - Math.PI / 2;
    const x = +(50 + rx * Math.cos(ang)).toFixed(2);
    const y = +(50 + ry * Math.sin(ang)).toFixed(2);
    const size = 38;
    return {
      id: c.id,
      glyph: c.id.slice(0, 2).toUpperCase(),
      tint, glow,
      down: "—", up: "—",
      loc: `${c.host}:${c.port}`,
      lat: "—",
      // These are DHT routing-table peers, not transfer peers — we don't know their
      // piece availability, so "have" is honestly unknown (no fabricated percentage).
      have: "—",
      conn: "DHT",
      dist: "0x" + c.id.slice(0, 4),
      active: true,
      x, y, size,
      left: x + "%", top: y + "%", sizePx: size + "px",
      border: "2px solid " + tint,
      shadow: "0 0 16px " + glow,
      connColor: "#74e3b0",
      line: tint,
      lw: 1.6,
      dash: "4 4",
    };
  });
}

/**
 * /api/progress `leechers` (the peers currently pulling from this seed) → the
 * peer-rail card shape, carrying each one's REAL availability (have/total from the
 * bitfield they sent us). Unlike the DHT swarm, this is the actual transfer set.
 */
export function leecherCards(leechers) {
  return (leechers || []).map((l) => {
    const id = l.id || "unknown";
    const [tint, glow] = TINTS[hashHex(id) % TINTS.length];
    const pct = l.total > 0 ? Math.round((l.have / l.total) * 100) : 0;
    return {
      id,
      glyph: id.slice(0, 2).toUpperCase(),
      tint, glow,
      loc: l.endpoint,
      down: "—",
      havePct: pct + "%",
      dist: "0x" + id.slice(0, 4),
    };
  });
}

/** milliseconds → "1h 4m" / "14m 22s". */
export function formatUptime(ms) {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m ${sec}s`;
}
