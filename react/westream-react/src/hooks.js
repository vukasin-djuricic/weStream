// weStream — live-data React hooks + transforms.
// Poll the local engine API on an interval and shape the JSON into the exact
// objects the (mock-authored) screens already render, so wiring a screen "live"
// is a data-source swap, not a rewrite. Every hook degrades gracefully: until the
// first successful fetch (e.g. the engine is still booting) `data` is null and the
// screen falls back to its mock.

import { useEffect, useRef, useState } from "react";
import { getStatus, getRouting } from "./api";

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

/** milliseconds → "1h 4m" / "14m 22s". */
export function formatUptime(ms) {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m ${sec}s`;
}
