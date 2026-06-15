// weStream — live-data React hooks + transforms.
// Poll the local engine API on an interval and shape the JSON into the exact
// objects the (mock-authored) screens already render, so wiring a screen "live"
// is a data-source swap, not a rewrite. Every hook degrades gracefully: until the
// first successful fetch (e.g. the engine is still booting) `data` is null and the
// screen falls back to its mock.

import { useEffect, useRef, useState } from "react";
import { getStatus, getRouting, getProgress } from "./api";

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

/** Poll a download's live progress at 500ms (faster — it drives the piece strip). No-op when infohash is null. */
export const useProgress = (infohash) =>
  usePoll(() => (infohash ? getProgress(infohash) : Promise.resolve(null)), 500);

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
      have: (hashHex(c.id) % 60 + 40) + "%",
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

/** milliseconds → "1h 4m" / "14m 22s". */
export function formatUptime(ms) {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m ${sec}s`;
}
