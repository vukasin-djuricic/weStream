import React, { useState, useEffect } from "react";
import {
  buildPieces, peers, buildSwarm, shares, downloads,
  buildBuckets, storedKeys, rpcLog,
} from "./data";
import { useStatus, useRouting, useProgress, useTransfers, useRpcLog, useDhtKeys, useThroughput, useWindowWidth, bucketsFromSizes, buildSwarmFrom, leecherCards, stripFromProgress, windowStripFrom, libraryFromTransfers, rpcLogFromEvents, storedKeysFrom, humanBytes, formatId, shortId, formatUptime } from "./hooks";
import { share as apiShare, startDownload as apiDownload, peekPeers as apiPeek, streamUrl } from "./api";

/* ------------------------------------------------------------------
   weStream — Phase 5 Player  (Direction A · Midnight Neon)
   Faithful React port of the design prototype.

   HOW THE STYLING WORKS
   The design was authored with inline CSS strings. React wants style
   OBJECTS, so this file uses a tiny `css("…")` helper that parses a
   CSS string into a React style object. That let the port stay 1:1
   with the design. If you'd rather have plain objects / Tailwind /
   CSS-modules, flatten them later — the values are all here.

   Fonts: make sure Manrope + JetBrains Mono are loaded (see README).
------------------------------------------------------------------ */

// CSS string -> React style object (camelCases properties)
function css(str) {
  const o = {};
  for (const part of str.split(";")) {
    const i = part.indexOf(":");
    if (i < 0) continue;
    let k = part.slice(0, i).trim();
    const v = part.slice(i + 1).trim();
    if (!k) continue;
    k = k.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    o[k] = v;
  }
  return o;
}

/** Format a bytes/sec rate as a MB/s number string ("—" when unknown). */
function mbps(bytesPerSec) {
  if (bytesPerSec == null) return "—";
  return (bytesPerSec / 1e6).toFixed(1);
}

const KEYFRAMES = `
@keyframes wsPulse { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }
@keyframes wsGlow { 0%,100% { box-shadow: 0 0 0 0 rgba(198,79,240,0.45), 0 0 22px 2px rgba(198,79,240,0.25); } 50% { box-shadow: 0 0 0 6px rgba(198,79,240,0.0), 0 0 30px 6px rgba(198,79,240,0.4); } }
@keyframes wsFlow { to { stroke-dashoffset: -28; } }
@keyframes wsOrbit { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.ws-scroll::-webkit-scrollbar { width: 10px; height: 10px; }
.ws-scroll::-webkit-scrollbar-thumb { background: #322b40; border-radius: 8px; border: 2px solid transparent; background-clip: padding-box; }
/* Reserve the scrollbar gutter always, so a vertical scrollbar appearing never
   narrows the content. Without this the Player's aspect-ratio video drives a
   feedback loop (scrollbar shrinks width -> video shorter -> no overflow ->
   scrollbar gone -> width grows -> overflow -> ...), which flickers. */
.ws-scroll { scrollbar-gutter: stable; }
`;

// small hover helper (since inline styles can't do :hover)
function Hover({ base, hover, as: Tag = "div", children, ...rest }) {
  const [h, setH] = useState(false);
  return (
    <Tag
      style={{ ...css(base), ...(h ? css(hover) : null) }}
      onMouseEnter={() => setH(true)}
      onMouseLeave={() => setH(false)}
      {...rest}
    >
      {children}
    </Tag>
  );
}

const NAV = [
  { key: "home", label: "Library", group: "browse",
    icon: <><path d="M3 10.5 12 3l9 7.5" /><path d="M5 9.5V20h14V9.5" /><path d="M10 20v-6h4v6" /></> },
  { key: "player", label: "Now Playing", group: "browse",
    icon: <><circle cx="12" cy="12" r="9" /><path d="M10 8.5l5 3.5-5 3.5z" fill="currentColor" stroke="none" /></> },
  { key: "swarm", label: "Swarm", group: "browse",
    icon: <><circle cx="12" cy="5" r="2.4" /><circle cx="5" cy="18" r="2.4" /><circle cx="19" cy="18" r="2.4" /><path d="M10.6 6.8 6.4 16M13.4 6.8 17.6 16M7.4 18h9.2" /></> },
  { key: "add", label: "Add Stream", group: "browse",
    icon: <><circle cx="12" cy="12" r="9" /><path d="M12 8v8M8 12h8" /></> },
  { key: "dht", label: "DHT Inspector", group: "engine",
    icon: <><circle cx="12" cy="12" r="2.2" /><circle cx="12" cy="12" r="6" /><circle cx="12" cy="12" r="9.6" /></> },
];

export default function WeStreamApp() {
  const [screen, setScreen] = useState("player");
  const [currentInfohash, setCurrentInfohash] = useState(null); // set by Add-Stream, streamed by Player

  // ---- live engine data (falls back to mock until the first fetch lands) ----
  const status = useStatus();
  const throughput = useThroughput(status.data); // { down, up } bytes/sec, or null
  const routing = useRouting();
  const playerProgress = useProgress(currentInfohash);
  const transfers = useTransfers();
  const library = transfers.data ? libraryFromTransfers(transfers.data.transfers) : null;
  const rpc = useRpcLog();
  const liveRpcLog = rpc.data ? rpcLogFromEvents(rpc.data.events) : null;
  const dhtKeys = useDhtKeys();
  // Engine is reachable only while polls succeed; a fetch error after the first
  // success means the JVM died / the port is gone — surface it instead of showing stale data.
  const connected = !!status.data && !status.error;
  const everConnected = !!status.data;
  const buckets = routing.data ? bucketsFromSizes(routing.data.bucketSizes) : buildBuckets();
  const swarm = routing.data ? buildSwarmFrom(routing.data.contacts) : buildSwarm();
  const peerCount = status.data ? status.data.peerCount : 24;
  const youLabel = status.data ? shortId(status.data.nodeId) : "4287ad37";
  const prog = playerProgress.data;
  // Render the real strip whenever the engine gives piece states — an active
  // download OR a seed/complete file (active:false but seeding:true). Mock only
  // when there is genuinely no data (e.g. before the first poll).
  const pieces = (prog && prog.pieceStates && prog.pieceStates.length) ? stripFromProgress(prog) : buildPieces();
  // When viewing a file we SEED, the player's peer rail shows the real leechers
  // pulling from us (with their true have%); otherwise it shows the DHT swarm.
  const playerPeers = (prog && prog.seeding && prog.leechers) ? leecherCards(prog.leechers) : swarm;
  // Native-style window controls: macOS puts traffic lights on the left, Windows
  // (and Linux) put min/max/close on the right. Default to the right elsewhere.
  const isMac = (window.westream?.platform || "") === "darwin";

  return (
    <div className="ws-scroll" style={css("height:100vh;display:flex;flex-direction:column;background:#0f0d15;color:#f4f1f8;font-family:'Manrope',system-ui,sans-serif;overflow:hidden")}>
      <style>{KEYFRAMES}</style>

      {/* ===== TITLE BAR ===== */}
      <header style={css("height:46px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:0 14px;background:linear-gradient(180deg,#181320,#141019);border-bottom:1px solid #272131;-webkit-app-region:drag")}>
        <div style={css("display:flex;align-items:center;gap:14px")}>
          {isMac && (
            <div style={css("display:flex;gap:8px;padding-right:6px;-webkit-app-region:no-drag")}>
              <span onClick={() => window.ws?.close()} style={css("width:12px;height:12px;border-radius:50%;background:#ec6a5e;cursor:pointer")} />
              <span onClick={() => window.ws?.minimize()} style={css("width:12px;height:12px;border-radius:50%;background:#f4bf4f;cursor:pointer")} />
              <span onClick={() => window.ws?.maximize()} style={css("width:12px;height:12px;border-radius:50%;background:#61c554;cursor:pointer")} />
            </div>
          )}
          <div style={css("display:flex;align-items:center;gap:9px")}>
            <span style={css("position:relative;width:22px;height:22px;display:inline-flex;align-items:center;justify-content:center")}>
              <span style={css("position:absolute;width:9px;height:9px;border-radius:50%;background:#c64ff0;box-shadow:0 0 10px rgba(198,79,240,0.7)")} />
              <span style={css("position:absolute;width:5px;height:5px;border-radius:50%;background:#f4f1f8;top:1px;left:0")} />
              <span style={css("position:absolute;width:5px;height:5px;border-radius:50%;background:#ee7fb0;bottom:0;right:1px")} />
              <span style={css("position:absolute;width:4px;height:4px;border-radius:50%;background:#46d39a;bottom:1px;left:2px")} />
            </span>
            <span style={css("font-weight:800;font-size:15px;letter-spacing:-0.02em")}>we<span style={{ color: "#c64ff0" }}>Stream</span></span>
          </div>
        </div>

        <div style={css("display:flex;align-items:center;gap:8px;font-family:'JetBrains Mono',monospace;font-size:11.5px;white-space:nowrap;flex-shrink:0")}>
          <div style={css("display:flex;align-items:center;gap:7px;padding:5px 11px;border-radius:999px;" + (connected
            ? "background:rgba(70,211,154,0.10);border:1px solid rgba(70,211,154,0.28);color:#74e3b0"
            : "background:rgba(244,191,79,0.10);border:1px solid rgba(244,191,79,0.28);color:#f4bf4f"))}>
            <span style={css("width:7px;height:7px;border-radius:50%;background:" + (connected ? "#46d39a" : "#f4bf4f") + ";animation:wsPulse 2s infinite")} />
            {connected ? "DHT CONNECTED" : "CONNECTING…"}
          </div>
          <div style={css("display:flex;align-items:center;gap:6px;padding:5px 11px;background:#1b1722;border:1px solid #2c2638;border-radius:999px;color:#ada3bd")}>
            <span style={{ color: "#c64ff0" }}>●</span> {peerCount} peers
          </div>
          {/* Live node-wide throughput (real PIECE bytes/sec); "—" until two samples land. */}
          <div style={css("display:flex;align-items:center;gap:5px;padding:5px 11px;background:#1b1722;border:1px solid #2c2638;border-radius:999px")}>
            <span style={{ color: "#6cc8e8" }}>↓ {mbps(throughput?.down)}</span><span style={{ color: "#756c85" }}>MB/s</span>
            <span style={{ color: "#ee7fb0", marginLeft: 4 }}>↑ {mbps(throughput?.up)}</span><span style={{ color: "#756c85" }}>MB/s</span>
          </div>
        </div>

        {!isMac && (
          <div style={css("display:flex;gap:16px;color:#756c85;font-size:16px;padding-right:4px;-webkit-app-region:no-drag")}>
            <span onClick={() => window.ws?.minimize()} style={{ cursor: "pointer" }}>—</span>
            <span onClick={() => window.ws?.maximize()} style={{ cursor: "pointer" }}>⤢</span>
            <span onClick={() => window.ws?.close()} style={{ cursor: "pointer" }}>⨯</span>
          </div>
        )}
      </header>

      {/* engine-down banner: a poll failed, so the JVM is unreachable (don't silently show stale/mock data) */}
      {status.error && (
        <div style={css("flex-shrink:0;display:flex;align-items:center;gap:10px;padding:9px 16px;background:rgba(240,121,94,0.12);border-bottom:1px solid rgba(240,121,94,0.35);color:#f0795e;font:600 12px 'JetBrains Mono'")}>
          <span style={css("width:8px;height:8px;border-radius:50%;background:#f0795e")} />
          {everConnected
            ? "Engine unreachable — the node process may have stopped. Showing the last known data."
            : "Cannot reach the engine on this node's API port. Is the Java node running?"}
        </div>
      )}

      <div style={css("flex:1;display:flex;min-height:0")}>
        {/* ===== SIDEBAR ===== */}
        <nav style={css("width:230px;flex-shrink:0;display:flex;flex-direction:column;background:#141019;border-right:1px solid #221d2c;padding:16px 12px")}>
          {["browse", "engine"].map((group) => (
            <React.Fragment key={group}>
              <div style={css("font-size:10.5px;font-weight:700;letter-spacing:0.13em;color:#5f5670;padding:" + (group === "browse" ? "4px 12px 10px" : "18px 12px 10px"))}>
                {group.toUpperCase()}
              </div>
              {NAV.filter((n) => n.group === group).map((n) => {
                const active = screen === n.key;
                return (
                  <Hover
                    key={n.key} as="button" onClick={() => setScreen(n.key)}
                    base="position:relative;display:flex;align-items:center;gap:13px;width:100%;padding:11px 13px;border:none;background:transparent;color:#c7bfd6;font:600 13.5px 'Manrope';cursor:pointer;border-radius:11px;text-align:left;margin-bottom:2px"
                    hover="background:#1d1826"
                  >
                    {active && <span style={css("position:absolute;inset:0;background:rgba(198,79,240,0.13);border-radius:11px;box-shadow:inset 3px 0 0 #c64ff0")} />}
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ position: "relative" }}>{n.icon}</svg>
                    <span style={{ position: "relative" }}>{n.label}</span>
                    {(n.key === "swarm" || n.badge) && <span style={css("position:relative;margin-left:auto;font:600 10.5px 'JetBrains Mono';color:#c64ff0;background:rgba(198,79,240,0.14);padding:2px 7px;border-radius:999px")}>{n.key === "swarm" ? peerCount : n.badge}</span>}
                  </Hover>
                );
              })}
            </React.Fragment>
          ))}

          <div style={css("margin-top:auto;padding:13px;background:#1a1623;border:1px solid #271f33;border-radius:14px")}>
            <div style={css("display:flex;align-items:center;gap:9px;margin-bottom:9px")}>
              <span style={css("width:9px;height:9px;border-radius:50%;background:#46d39a;box-shadow:0 0 8px rgba(70,211,154,0.6)")} />
              <span style={css("font-size:12px;font-weight:700")}>This node</span>
            </div>
            <div style={css("font-family:'JetBrains Mono',monospace;font-size:10.5px;color:#756c85;line-height:1.5;word-break:break-all")}>
              id {status.data ? shortId(status.data.nodeId) : "4287ad37…d54569e"}<br />
              {status.data ? `${status.data.host}:${status.data.udpPort}` : "127.0.0.1:1100"}
            </div>
          </div>
        </nav>

        {/* ===== MAIN ===== */}
        <main className="ws-scroll" style={css("flex:1;min-width:0;overflow:auto;background:radial-gradient(1100px 600px at 78% -8%, rgba(198,79,240,0.07), transparent 60%), #0f0d15")}>
          {screen === "player" && <PlayerScreen pieces={pieces} infohash={currentInfohash} progress={prog} swarm={playerPeers} peerCount={peerCount} throughput={throughput} onSwarm={() => setScreen("swarm")} onAdd={() => setScreen("add")} />}
          {screen === "swarm" && <SwarmScreen swarm={swarm} peerCount={peerCount} youLabel={youLabel} throughput={throughput} />}
          {screen === "home" && <LibraryScreen library={library} onPlayer={() => setScreen("player")} onAdd={() => setScreen("add")} onPlay={(ih) => { setCurrentInfohash(ih); setScreen("player"); }} />}
          {screen === "add" && <AddStreamScreen
            onStream={(ih) => { setCurrentInfohash(ih); setScreen("player"); }}
            onDownloaded={(ih) => setCurrentInfohash(ih)} />}
          {screen === "dht" && <DhtScreen buckets={buckets} status={status.data} routing={routing.data} rpcEvents={liveRpcLog} dht={dhtKeys.data} />}
        </main>
      </div>
    </div>
  );
}

/* ===================== PLAYER ===================== */
function PlayerScreen({ pieces, infohash, progress, swarm = [], peerCount = 0, throughput = null, onSwarm, onAdd }) {
  const [vstat, setVstat] = useState(null); // null | "buffering" | "error" — from the <video> events
  const [copied, setCopied] = useState(false);
  const shareInfohash = () => {
    if (!infohash) return;
    navigator.clipboard?.writeText(infohash); // the infohash IS the shareable token (paste in Add Stream)
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };
  const total = progress ? progress.total : 0;
  const have = progress ? progress.have : 0;
  const inFlight = progress ? progress.inFlight : 0;
  const frac = total > 0 ? have / total : 0;
  const pct = Math.round(frac * 100) + "%";
  // Collapse the 322px swarm rail on a narrow window (3 nodes tiled on one
  // display) so the video keeps room; "View full swarm map" still reaches it.
  const showRail = useWindowWidth() >= 900;
  // The REAL sliding window: the W pieces at the playhead (null on a complete
  // seeder, where we fall back to the global piece map below).
  const windowCells = progress ? windowStripFrom(progress) : null;
  const windowed = windowCells != null;
  // Once the file is whole, a wall of identical "have" cells says nothing — swap the
  // piece grid for a verified/seeding summary (real upload rate + leecher count).
  const complete = total > 0 && have >= total;
  const leecherN = progress?.seeding ? (progress.peers || 0) : null;
  return (
    <section style={css("display:flex;min-height:100%")}>
      <div style={css("flex:1;min-width:0;padding:22px 24px;display:flex;flex-direction:column")}>
        <div style={css("display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:16px")}>
          <div>
            <div style={css("display:flex;align-items:center;gap:10px;margin-bottom:6px")}>
              <h1 style={css("margin:0;font-size:21px;font-weight:800;letter-spacing:-0.02em")}>{infohash ? "Now streaming" : "No stream selected"}</h1>
              {infohash && <span style={css("font:700 10px 'JetBrains Mono';color:#74e3b0;background:rgba(70,211,154,0.12);border:1px solid rgba(70,211,154,0.3);padding:3px 8px;border-radius:6px;letter-spacing:0.08em")}>STREAMING</span>}
            </div>
            <div style={css("display:flex;align-items:center;gap:12px;font-family:'JetBrains Mono',monospace;font-size:11.5px;color:#756c85")}>
              {total > 0 && <><span>{total} pieces</span><span style={{ color: "#322b40" }}>|</span><span>{pct} have</span><span style={{ color: "#322b40" }}>|</span></>}
              <span>infohash {infohash ? shortId(infohash) : "—"}</span>
            </div>
          </div>
          {/* Share = copy the infohash (the P2P shareable token: paste it in Add Stream → Resolve). */}
          {infohash && (
            <div style={css("display:flex;gap:8px")}>
              <Hover as="button" onClick={shareInfohash}
                base={"display:flex;align-items:center;gap:7px;padding:8px 13px;border-radius:10px;font:600 12.5px 'Manrope';cursor:pointer;" + (copied
                  ? "background:rgba(70,211,154,0.12);border:1px solid rgba(70,211,154,0.3);color:#74e3b0"
                  : "background:#1c1826;border:1px solid #2c2638;color:#c7bfd6")}
                hover={copied ? "" : "background:#252031"}>
                {copied ? "✓ infohash copied" : "Share"}
              </Hover>
            </div>
          )}
        </div>

        {/* video surface — HTML5 <video> streams /stream/<infohash> (Range/206 from the engine; seeks move the playhead) */}
        <div style={css("position:relative;aspect-ratio:16/9;border-radius:16px;overflow:hidden;background:#000;border:1px solid #272131;display:flex;align-items:center;justify-content:center")}>
          {infohash ? (
            <>
              <video key={infohash} src={streamUrl(infohash)} controls autoPlay
                onError={() => setVstat("error")}
                onWaiting={() => setVstat("buffering")}
                onStalled={() => setVstat("buffering")}
                onPlaying={() => setVstat(null)}
                onCanPlay={() => setVstat((s) => (s === "error" ? s : null))}
                style={{ width: "100%", height: "100%", objectFit: "contain", background: "#000" }} />
              {vstat && (
                <div style={css("position:absolute;top:12px;left:12px;display:flex;align-items:center;gap:8px;padding:7px 12px;border-radius:9px;font:600 11px 'JetBrains Mono';" + (vstat === "error"
                  ? "background:rgba(240,121,94,0.18);border:1px solid rgba(240,121,94,0.4);color:#f0795e"
                  : "background:rgba(108,200,232,0.16);border:1px solid rgba(108,200,232,0.4);color:#6cc8e8"))}>
                  <span style={css("width:7px;height:7px;border-radius:50%;background:currentColor;animation:wsPulse 1.4s infinite")} />
                  {vstat === "error"
                    ? "Playback error — the browser may not support this container/codec."
                    : "Buffering — waiting for pieces near the playhead…"}
                </div>
              )}
            </>
          ) : (
            <div style={css("position:relative;display:flex;flex-direction:column;align-items:center;gap:14px")}>
              <div style={css("position:absolute;inset:-40px;background:radial-gradient(420px 240px at 50% 42%, rgba(198,79,240,0.10), transparent 70%)")} />
              <div style={css("position:relative;width:64px;height:64px;border-radius:50%;background:rgba(198,79,240,0.14);border:1px solid rgba(198,79,240,0.4);display:flex;align-items:center;justify-content:center;animation:wsGlow 3s infinite")}>
                <svg width="26" height="26" viewBox="0 0 24 24"><path d="M8 5.5l11 6.5-11 6.5z" fill="#f4f1f8" /></svg>
              </div>
              <span style={css("position:relative;font-family:'JetBrains Mono',monospace;font-size:11px;color:#756c85;letter-spacing:0.05em")}>No stream selected</span>
              <Hover as="button" onClick={onAdd} base="position:relative;padding:8px 16px;background:#1c1826;border:1px solid #2c2638;border-radius:10px;color:#c7bfd6;font:600 12px 'Manrope';cursor:pointer" hover="background:#252031">Go to Add a stream →</Hover>
            </div>
          )}
        </div>

        {/* download buffer bar (playback seeking lives in the native <video> controls above) */}
        <div style={{ marginTop: 16 }}>
          <div style={css("position:relative;height:6px;border-radius:999px;background:#221d2c")}>
            <div style={css("position:absolute;left:0;top:0;bottom:0;border-radius:999px;background:linear-gradient(90deg,#9b3ec9,#c64ff0);width:" + pct)} />
          </div>
          <div style={css("display:flex;justify-content:space-between;margin-top:7px;font-family:'JetBrains Mono',monospace;font-size:11px;color:#756c85")}>
            <span style={{ color: "#c7bfd6" }}>{infohash ? "downloaded " + pct : "—"}</span><span>{total > 0 ? have + "/" + total + " pieces" : ""}</span>
          </div>
        </div>

        {/* sliding window — when downloading this is the REAL [playhead, playhead+W)
            slice (a seek relocates it; watch pieces go missing→in-flight→have). Once
            complete the mosaic is uniform, so it becomes a verified/seeding summary. */}
        {complete ? (
          <div style={css("margin-top:16px;padding:16px 18px;background:linear-gradient(120deg,rgba(70,211,154,0.08),#15111d 70%);border:1px solid rgba(70,211,154,0.3);border-radius:14px;display:flex;align-items:center;gap:15px")}>
            <span style={css("width:42px;height:42px;flex-shrink:0;border-radius:13px;display:flex;align-items:center;justify-content:center;background:rgba(70,211,154,0.13);border:1px solid rgba(70,211,154,0.4);box-shadow:0 0 18px rgba(70,211,154,0.25)")}>
              <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="#74e3b0" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6L9 17l-5-5" /></svg>
            </span>
            <div style={css("flex:1;min-width:0")}>
              <div style={css("font-size:13.5px;font-weight:800;color:#f4f1f8;margin-bottom:3px")}>Complete — fully downloaded</div>
              <div style={css("font:600 11px 'JetBrains Mono';color:#74e3b0")}>{total} / {total} pieces verified · SHA-1</div>
            </div>
            <div style={css("text-align:right;flex-shrink:0")}>
              <div style={css("font:700 15px 'JetBrains Mono';color:#ee7fb0")}>↑ {mbps(throughput?.up)}<span style={css("font-size:10px;color:#756c85;font-weight:600")}> MB/s</span></div>
              <div style={css("display:flex;align-items:center;gap:6px;justify-content:flex-end;margin-top:4px;font:600 10px 'JetBrains Mono';color:#756c85")}>
                <span style={css("width:7px;height:7px;border-radius:50%;background:#46d39a;box-shadow:0 0 7px rgba(70,211,154,0.6);animation:wsPulse 2s infinite")} />
                {leecherN != null ? "seeding to " + leecherN + (leecherN === 1 ? " leecher" : " leechers") : "seeding to swarm"}
              </div>
            </div>
          </div>
        ) : (
        <div style={css("margin-top:16px;padding:14px 16px;background:#15111d;border:1px solid #221d2c;border-radius:14px")}>
          <div style={css("display:flex;align-items:center;justify-content:space-between;margin-bottom:11px")}>
            <div style={css("display:flex;align-items:center;gap:9px")}>
              <span style={css("font-size:12.5px;font-weight:700")}>{windowed ? "Sliding window" : "Piece map"}</span>
              <span style={css("font-family:'JetBrains Mono',monospace;font-size:10.5px;color:#756c85")}>
                {windowed
                  ? "SlidingWindowPicker · W=" + progress.window + " · playhead @ " + progress.playhead
                  : (progress?.seeding ? "seeding · full file" : "SlidingWindowPicker")}
              </span>
            </div>
            <div style={css("display:flex;align-items:center;gap:14px;font-size:10.5px;color:#756c85")}>
              {[["have", "#c64ff0"], ["in-flight", "#6cc8e8"], ["missing", "#2a2435"]].map(([l, c]) => (
                <span key={l} style={css("display:flex;align-items:center;gap:6px")}><span style={css("width:9px;height:9px;border-radius:2px;background:" + c)} />{l}</span>
              ))}
            </div>
          </div>
          <div style={css("display:flex;gap:3px;align-items:flex-end")}>
            {(windowed ? windowCells : pieces).map((p) => (
              <span key={p.idx} title={"piece " + p.idx}
                style={css("flex:1;height:26px;border-radius:3px;position:relative;background:" + p.color
                  + (p.head ? ";box-shadow:0 0 0 2px #f4bf4f,0 0 9px rgba(244,191,79,0.7)" : ""))} />
            ))}
          </div>
          <div style={css("display:flex;justify-content:space-between;margin-top:9px;font-family:'JetBrains Mono',monospace;font-size:10px;color:#5f5670")}>
            {windowed
              ? <><span style={{ color: "#f4bf4f" }}>▸ playhead @ piece {progress.playhead}</span><span>requesting {inFlight} · {have}/{total} have</span></>
              : <><span>{have}/{total} have</span><span>requesting {inFlight}</span></>}
          </div>
        </div>
        )}
      </div>

      {/* live swarm rail — hidden on a narrow window to give the video room */}
      {showRail && (
      <aside className="ws-scroll" style={css("width:322px;flex-shrink:0;border-left:1px solid #221d2c;background:#131019;padding:20px 18px;display:flex;flex-direction:column;gap:16px;overflow:auto")}>
        <div style={css("display:flex;align-items:center;justify-content:space-between")}>
          <h2 style={css("margin:0;font-size:14px;font-weight:800")}>{progress?.seeding ? "Leechers" : "Live swarm"}</h2>
          <span style={css("font:600 11px 'JetBrains Mono';color:#c64ff0;background:rgba(198,79,240,0.13);padding:3px 9px;border-radius:999px")}>{swarm.length} {progress?.seeding ? "downloading" : "peers"}</span>
        </div>
        {/* Real seeder/leecher split of the peers we pull FROM (from their bitfields) — download side only */}
        {progress && !progress.seeding && progress.peers > 0 && (
          <div style={css("display:flex;gap:10px")}>
            {[["SEEDERS", progress.seeders || 0, "#74e3b0"], ["LEECHERS", progress.leechers || 0, "#e7b0f0"]].map(([l, v, c]) => (
              <div key={l} style={css("flex:1;padding:10px 12px;background:#15111d;border:1px solid #221d2c;border-radius:12px")}>
                <div style={css("font-family:'JetBrains Mono',monospace;font-size:10px;color:#756c85;margin-bottom:4px")}>{l}</div>
                <div style={css("font-size:18px;font-weight:800;color:" + c)}>{v}</div>
              </div>
            ))}
          </div>
        )}
        <div style={css("display:flex;gap:10px")}>
          {[["DOWNLOAD", mbps(throughput?.down), "#6cc8e8"], ["UPLOAD", mbps(throughput?.up), "#ee7fb0"]].map(([l, v, c]) => (
            <div key={l} style={css("flex:1;padding:12px;background:#15111d;border:1px solid #221d2c;border-radius:12px")}>
              <div style={css("font-family:'JetBrains Mono',monospace;font-size:10px;color:#756c85;margin-bottom:4px")}>{l}</div>
              <div style={css("font-size:18px;font-weight:800;color:" + c)}>{v}<span style={css("font-size:11px;color:#756c85;font-weight:600")}> MB/s</span></div>
            </div>
          ))}
        </div>
        <div style={css("display:flex;flex-direction:column;gap:8px")}>
          {swarm.length === 0 && (
            <div style={css("padding:16px;text-align:center;font:500 11.5px 'JetBrains Mono';color:#5f5670;background:#15111d;border:1px solid #221d2c;border-radius:12px")}>no peers connected yet</div>
          )}
          {swarm.map((pr) => (
            <Hover key={pr.id} base="display:flex;align-items:center;gap:11px;padding:10px 11px;background:#15111d;border:1px solid #221d2c;border-radius:12px" hover="border-color:#3a3148">
              <span style={css("width:30px;height:30px;flex-shrink:0;border-radius:9px;display:flex;align-items:center;justify-content:center;font:700 11px 'JetBrains Mono';color:#0f0d15;background:" + pr.tint)}>{pr.glyph}</span>
              <div style={css("flex:1;min-width:0")}>
                <div style={css("display:flex;align-items:center;gap:7px")}>
                  <span style={css("font-family:'JetBrains Mono',monospace;font-size:11.5px;color:#e7e1ef;font-weight:600")}>{pr.id.length > 12 ? pr.id.slice(0, 10) + "…" : pr.id}</span>
                  <span style={css("font-size:10px;color:#5f5670")}>{pr.loc}</span>
                </div>
                <div style={css("margin-top:4px;font:500 9.5px 'JetBrains Mono';color:" + (pr.havePct ? "#74e3b0" : "#5f5670"))}>{pr.havePct ? "has " + pr.havePct + " of file" : "XOR dist " + pr.dist}</div>
              </div>
              {/* per-peer transfer speed isn't metered yet; the meaningful per-peer datum
                  (have% for leechers, XOR distance for DHT contacts) is on the line above. */}
              <span style={css("flex-shrink:0;font:600 9px 'JetBrains Mono';color:#5f5670;background:rgba(21,17,29,0.6);border:1px solid #221d2c;padding:2px 7px;border-radius:5px")}>{pr.havePct ? "leech" : "DHT"}</span>
            </Hover>
          ))}
        </div>
        <Hover as="button" onClick={onSwarm} base="margin-top:auto;padding:11px;background:#1a1623;border:1px solid #271f33;border-radius:11px;color:#c7bfd6;font:600 12.5px 'Manrope';cursor:pointer" hover="background:#221d2c">View full swarm map →</Hover>
      </aside>
      )}
    </section>
  );
}

/* ===================== SWARM ===================== */
function SwarmScreen({ swarm, peerCount = 24, youLabel = "4287ad37", throughput = null }) {
  // DOWNLOAD/UPLOAD are the real node-wide rate; SHARE RATIO and the per-peer columns
  // stay "—" (no per-peer meter, and these rows are DHT contacts, not transfer peers).
  const stats = [
    ["CONNECTED PEERS", String(peerCount), "#f4f1f8"], ["SWARM HEALTH", peerCount > 0 ? "Excellent" : "Alone", "#46d39a"],
    ["DOWNLOAD", mbps(throughput?.down), "#6cc8e8"], ["UPLOAD", mbps(throughput?.up), "#ee7fb0"], ["SHARE RATIO", "—", "#f4f1f8"],
  ];
  return (
    <section style={css("padding:22px 24px 30px")}>
      <div style={css("display:flex;align-items:flex-end;justify-content:space-between;gap:16px;margin-bottom:18px")}>
        <div>
          <h1 style={css("margin:0 0 5px;font-size:22px;font-weight:800;letter-spacing:-0.02em")}>Swarm map</h1>
          <div style={css("font-size:13px;color:#8b8299")}>Live peers in your <span style={{ color: "#c7bfd6", fontWeight: 600 }}>Kademlia routing table</span> · the more peers, the faster lookups and transfers</div>
        </div>
        <div style={css("display:flex;align-items:center;gap:8px;padding:7px 13px;background:rgba(70,211,154,0.10);border:1px solid rgba(70,211,154,0.28);border-radius:999px")}>
          <span style={css("width:7px;height:7px;border-radius:50%;background:#46d39a;animation:wsPulse 2s infinite")} />
          <span style={css("font:600 11.5px 'JetBrains Mono';color:#74e3b0")}>auto-refreshing</span>
        </div>
      </div>

      <div style={css("display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin-bottom:18px")}>
        {stats.map(([l, v, c]) => (
          <div key={l} style={css("padding:15px 16px;background:#15111d;border:1px solid #221d2c;border-radius:14px")}>
            <div style={css("font:600 10.5px 'JetBrains Mono';color:#756c85;letter-spacing:0.06em")}>{l}</div>
            <div style={css("font-size:26px;font-weight:800;margin-top:5px;color:" + c)}>{v}{(l === "DOWNLOAD" || l === "UPLOAD") && <span style={css("font-size:13px;color:#756c85")}> MB/s</span>}</div>
          </div>
        ))}
      </div>

      {/* graph */}
      <div style={css("position:relative;height:540px;background:radial-gradient(620px 420px at 50% 50%, rgba(198,79,240,0.08), transparent 65%), #131019;border:1px solid #221d2c;border-radius:18px;overflow:hidden")}>
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" style={css("position:absolute;inset:0;width:100%;height:100%;overflow:visible")}>
          {swarm.map((l, i) => (
            <line key={i} x1="50" y1="50" x2={l.x} y2={l.y} stroke={l.line} strokeWidth={l.lw} vectorEffect="non-scaling-stroke" strokeDasharray={l.dash} style={{ animation: "wsFlow 1.1s linear infinite" }} />
          ))}
        </svg>

        <div style={css("position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);display:flex;flex-direction:column;align-items:center;gap:8px;z-index:2")}>
          <div style={css("position:relative;width:74px;height:74px;display:flex;align-items:center;justify-content:center")}>
            <span style={css("position:absolute;inset:-10px;border-radius:50%;border:1px dashed rgba(198,79,240,0.4);animation:wsOrbit 18s linear infinite")} />
            <div style={css("width:74px;height:74px;border-radius:50%;background:linear-gradient(150deg,#c64ff0,#7c2fd0);display:flex;align-items:center;justify-content:center;box-shadow:0 0 30px rgba(198,79,240,0.55)")}>
              <span style={css("font:800 13px 'Manrope';color:#fff")}>YOU</span>
            </div>
          </div>
          <span style={css("font:600 10px 'JetBrains Mono';color:#c7bfd6;background:#15111d;border:1px solid #2c2638;padding:3px 9px;border-radius:6px")}>you · {youLabel}</span>
        </div>

        {swarm.map((n) => (
          <div key={n.id} style={css("position:absolute;transform:translate(-50%,-50%);display:flex;flex-direction:column;align-items:center;gap:5px;z-index:2;left:" + n.left + ";top:" + n.top)}>
            <div style={css("border-radius:50%;background:#181320;display:flex;align-items:center;justify-content:center;font:700 11px 'JetBrains Mono';width:" + n.sizePx + ";height:" + n.sizePx + ";border:" + n.border + ";color:" + n.tint + ";box-shadow:" + n.shadow)}>{n.glyph}</div>
            <span title={n.loc} style={css("font:600 9px 'JetBrains Mono';color:#8b8299;background:rgba(21,17,29,0.85);padding:2px 6px;border-radius:5px;white-space:nowrap")}>{n.id.slice(0, 6)}</span>
          </div>
        ))}

        <div style={css("position:absolute;left:16px;bottom:16px;display:flex;gap:16px;padding:9px 14px;background:rgba(15,13,21,0.8);backdrop-filter:blur(8px);border:1px solid #2c2638;border-radius:11px;font-size:11px;color:#8b8299")}>
          <span style={css("display:flex;align-items:center;gap:7px")}><span style={css("width:16px;height:2px;background:#c64ff0")} />active transfer</span>
          <span style={css("display:flex;align-items:center;gap:7px")}><span style={css("width:16px;height:2px;background:#322b40")} />known peer</span>
        </div>
      </div>

      {/* table */}
      <div style={css("margin-top:18px;background:#15111d;border:1px solid #221d2c;border-radius:16px;overflow:hidden")}>
        <div style={css("display:grid;grid-template-columns:1.6fr 1.2fr 0.8fr 1.4fr 0.8fr 1fr;gap:12px;padding:13px 18px;border-bottom:1px solid #221d2c;font:700 10.5px 'JetBrains Mono';color:#756c85;letter-spacing:0.06em")}>
          <span>PEER</span><span>LOCATION</span><span>LATENCY</span><span>PIECES</span><span>CONN</span><span>XOR DIST</span>
        </div>
        {swarm.map((n) => (
          <Hover key={n.id} base="display:grid;grid-template-columns:1.6fr 1.2fr 0.8fr 1.4fr 0.8fr 1fr;gap:12px;padding:12px 18px;border-bottom:1px solid #1c1826;align-items:center" hover="background:#191421">
            <div style={css("display:flex;align-items:center;gap:10px;min-width:0")}>
              <span style={css("width:26px;height:26px;flex-shrink:0;border-radius:7px;display:flex;align-items:center;justify-content:center;font:700 10px 'JetBrains Mono';border:" + n.border + ";color:" + n.tint)}>{n.glyph}</span>
              <span style={css("font:600 12px 'JetBrains Mono';color:#e7e1ef")}>{n.id.length > 14 ? n.id.slice(0, 12) + "…" : n.id}</span>
            </div>
            <span style={css("font-size:12.5px;color:#b3aac0")}>{n.loc}</span>
            <span style={css("font:500 11.5px 'JetBrains Mono';color:#8b8299")}>{n.lat}</span>
            <span style={css("font:500 11.5px 'JetBrains Mono';color:#5f5670")} title="piece availability is only known for active transfer peers, not DHT contacts">{n.have}</span>
            <span style={css("font:500 11px 'JetBrains Mono';color:" + n.connColor)}>{n.conn}</span>
            <span style={css("font:500 11.5px 'JetBrains Mono';color:#756c85")}>{n.dist}</span>
          </Hover>
        ))}
      </div>
    </section>
  );
}

/* ===================== LIBRARY ===================== */
function MediaCard({ it, onPlay }) {
  return (
    <Hover onClick={() => it.infohash && onPlay && onPlay(it.infohash)} base="background:#15111d;border:1px solid #221d2c;border-radius:14px;overflow:hidden;cursor:pointer" hover="border-color:#3a3148">
      <div style={css("position:relative;aspect-ratio:16/10;display:flex;align-items:center;justify-content:center;background:" + it.posterBg)}>
        <span style={css("font:600 9.5px 'JetBrains Mono';color:#5f5670")}>poster</span>
        <span style={css("position:absolute;top:9px;left:9px;font:700 9px 'JetBrains Mono';color:#cfc6dd;background:rgba(15,13,21,0.72);padding:2px 7px;border-radius:5px")}>{it.res}</span>
        <span style={css("position:absolute;top:9px;right:9px;display:flex;align-items:center;gap:5px;font:700 9px 'JetBrains Mono';padding:3px 8px;border-radius:999px;color:" + it.statusColor + ";background:" + it.statusBg)}>{it.status}</span>
        <div style={css("position:absolute;left:0;right:0;bottom:0;height:3px;background:#2a2038")}><div style={css("height:100%;width:" + it.prog + ";background:" + it.progColor)} /></div>
      </div>
      <div style={css("padding:12px 13px")}>
        <div style={css("font-size:13.5px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis")}>{it.title}</div>
        <div style={css("display:flex;justify-content:space-between;margin-top:8px;font:500 11px 'JetBrains Mono';color:#756c85")}>
          <span>{it.size}</span><span style={{ color: "#c08fe8" }}>{it.peersLabel}</span>
        </div>
      </div>
    </Hover>
  );
}

function LibraryScreen({ library, onPlayer, onAdd, onPlay }) {
  const grid = css("display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:14px");
  const myShares = library ? library.shares : shares;       // live or mock fallback
  const myDownloads = library ? library.downloads : downloads;
  const empty = css("padding:24px;font:500 12.5px 'JetBrains Mono';color:#5f5670;background:#15111d;border:1px dashed #221d2c;border-radius:12px");
  // "Continue watching" = the most recent real download, if any (else the hero is hidden).
  const hero = myDownloads.find((d) => d.infohash) || null;
  // Live search over the grids (hero stays — continue-watching isn't filtered).
  const [query, setQuery] = useState("");
  const q = query.trim().toLowerCase();
  const match = (arr) => (q ? arr.filter((it) => (it.title || "").toLowerCase().includes(q)) : arr);
  const shownShares = match(myShares);
  const shownDownloads = match(myDownloads);
  return (
    <section style={css("padding:22px 24px 30px")}>
      <div style={css("display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:20px")}>
        <div>
          <h1 style={css("margin:0 0 5px;font-size:22px;font-weight:800;letter-spacing:-0.02em")}>Library</h1>
          <div style={css("font-size:13px;color:#8b8299")}>Files you seed and stream, all from the swarm</div>
        </div>
        <div style={css("display:flex;gap:10px")}>
          <div style={css("display:flex;align-items:center;gap:9px;padding:9px 14px;background:#15111d;border:1px solid #221d2c;border-radius:11px;width:230px")}>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#756c85" strokeWidth="2"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search library…"
              style={css("flex:1;min-width:0;border:none;outline:none;background:transparent;font:500 12.5px 'Manrope';color:#e7e1ef")} />
          </div>
          <Hover as="button" onClick={onAdd} base="display:flex;align-items:center;gap:8px;padding:9px 16px;background:linear-gradient(135deg,#c64ff0,#9b3ec9);border:none;border-radius:11px;color:#fff;font:700 12.5px 'Manrope';cursor:pointer" hover="filter:brightness(1.1)">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>Share a file
          </Hover>
        </div>
      </div>

      {hero && (
      <Hover onClick={() => onPlay && onPlay(hero.infohash)} base="position:relative;display:flex;gap:22px;padding:20px;margin-bottom:26px;background:linear-gradient(110deg,#211433,#15111d 60%);border:1px solid #34284a;border-radius:18px;cursor:pointer;overflow:hidden" hover="border-color:#52406a">
        <div style={css("position:absolute;right:-40px;top:-60px;width:280px;height:280px;border-radius:50%;background:radial-gradient(circle, rgba(198,79,240,0.18), transparent 70%)")} />
        <div style={css("position:relative;width:230px;flex-shrink:0;aspect-ratio:16/10;border-radius:12px;background:repeating-linear-gradient(135deg,#1a1426,#1a1426 10px,#1d1729 10px,#1d1729 20px);border:1px solid #34284a;display:flex;align-items:center;justify-content:center")}>
          <div style={css("width:48px;height:48px;border-radius:50%;background:rgba(198,79,240,0.85);display:flex;align-items:center;justify-content:center;box-shadow:0 6px 20px rgba(198,79,240,0.45)")}>
            <svg width="20" height="20" viewBox="0 0 24 24"><path d="M8 5.5l11 6.5-11 6.5z" fill="#fff" /></svg>
          </div>
        </div>
        <div style={css("position:relative;flex:1;display:flex;flex-direction:column;justify-content:center;min-width:0")}>
          <span style={css("font:700 10.5px 'JetBrains Mono';color:#c08fe8;letter-spacing:0.08em")}>CONTINUE WATCHING</span>
          <h2 style={css("margin:7px 0 6px;font-size:24px;font-weight:800;letter-spacing:-0.02em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis")}>{hero.title}</h2>
          <div style={css("font:500 12px 'JetBrains Mono';color:#8b8299;margin-bottom:14px")}>{hero.res} · {hero.size} · {hero.status}</div>
          <div style={css("height:6px;width:360px;max-width:100%;border-radius:999px;background:#2a2038")}>
            <div style={css("height:100%;width:" + hero.prog + ";border-radius:999px;background:linear-gradient(90deg,#9b3ec9,#c64ff0)")} />
          </div>
        </div>
      </Hover>
      )}

      <div style={css("font-size:13px;font-weight:800;letter-spacing:-0.01em;margin-bottom:13px;display:flex;align-items:center;gap:9px")}>Your shares <span style={css("font:600 11px 'JetBrains Mono';color:#74e3b0;background:rgba(70,211,154,0.1);padding:2px 8px;border-radius:999px")}>seeding</span></div>
      {shownShares.length === 0
        ? <div style={{ ...empty, marginBottom: 28 }}>{q ? "No shares match your search." : "Nothing shared yet — share a file from Add a stream."}</div>
        : <div style={{ ...grid, marginBottom: 28 }}>{shownShares.map((it) => <MediaCard key={it.infohash || it.title} it={it} onPlay={onPlay} />)}</div>}

      <div style={css("font-size:13px;font-weight:800;letter-spacing:-0.01em;margin-bottom:13px")}>Downloads</div>
      {shownDownloads.length === 0
        ? <div style={empty}>{q ? "No downloads match your search." : "No downloads yet — resolve an infohash from Add a stream."}</div>
        : <div style={grid}>{shownDownloads.map((it) => <MediaCard key={it.infohash || it.title} it={it} onPlay={onPlay} />)}</div>}
    </section>
  );
}

/* ===================== ADD STREAM ===================== */
function AddStreamScreen({ onStream, onDownloaded }) {
  const [infohash, setInfohash] = useState("");
  const [sharePath, setSharePath] = useState("");
  const [msg, setMsg] = useState(null); // { text, ok }
  const [busy, setBusy] = useState(false);
  const [resolved, setResolved] = useState(null); // real metadata from a resolve/share, or null
  const [peek, setPeek] = useState(null); // null | {status:'looking'|'found'|'none', peers, totalLength, pieceCount}
  const [copied, setCopied] = useState(false); // "Copy infohash" feedback
  const HEX40 = /^[0-9a-fA-F]{40}$/;
  const inputStyle = css("flex:1;min-width:0;background:transparent;border:none;outline:none;font:500 13px 'JetBrains Mono';color:#e7e1ef");

  // Live DHT peek: when a valid infohash is entered, resolve its metadata + swarm
  // size WITHOUT downloading (debounced), so the user sees how many peers hold the
  // file before committing. Honest count — the DHT peer set has no seeder/leecher
  // flag, so this is the whole swarm; the S/L split shows live during the transfer.
  useEffect(() => {
    const ih = infohash.trim().toLowerCase();
    if (!HEX40.test(ih)) { setPeek(null); return; }
    let alive = true;
    setPeek({ status: "looking" });
    const t = setTimeout(async () => {
      try {
        const r = await apiPeek(ih);
        if (!alive) return;
        setPeek(r.found
          ? { status: "found", peers: r.peers, totalLength: r.totalLength, pieceCount: r.pieceCount }
          : { status: "none" });
      } catch {
        if (alive) setPeek({ status: "none" });
      }
    }, 450);
    return () => { alive = false; clearTimeout(t); };
  }, [infohash]);

  const doDownload = async (thenStream) => {
    if (busy) return; // ignore re-clicks while a request is in flight
    const ih = infohash.trim().toLowerCase();
    if (!HEX40.test(ih)) { setMsg({ text: "Infohash must be 40 hex characters.", ok: false }); return; }
    setBusy(true);
    try {
      const r = await apiDownload(ih);
      // alreadyLocal: we seed this file, so the engine didn't start a download — just stream it.
      setResolved({ infohash: ih, pieceSize: r.pieceSize, pieceCount: r.pieceCount, totalLength: r.totalLength, mine: r.alreadyLocal === true });
      setMsg(r.alreadyLocal
        ? { text: "Already on this node — streaming from your seed.", ok: true }
        : { text: `Download started → ${r.out}`, ok: true });
      onDownloaded && onDownloaded(ih);
      if (thenStream) onStream && onStream(ih);
    } catch (e) {
      setResolved(null);
      setMsg({ text: "Infohash not announced in the DHT (or engine offline).", ok: false });
    } finally { setBusy(false); }
  };

  const doShare = async () => {
    if (busy) return; // ignore re-clicks while hashing/sharing is in flight (no duplicate shares)
    const p = sharePath.trim();
    if (!p) { setMsg({ text: "Enter a file path to share.", ok: false }); return; }
    setBusy(true);
    setMsg({ text: "Hashing & sharing… (large files take a few seconds)", ok: true });
    try {
      const r = await apiShare(p);
      setInfohash(r.infohash);
      // mine:true — this node now SEEDS the file, so the card offers Watch + Copy
      // infohash (to hand the token to a peer), never a pointless "download your own file".
      setResolved({ infohash: r.infohash, pieceSize: r.pieceSize, pieceCount: r.pieceCount, totalLength: r.totalLength, mine: true });
      setMsg({ text: `Shared · ${r.infohash} (${r.pieceCount} pieces)`, ok: true });
    } catch (e) {
      setMsg({ text: "Share failed — is the path a readable file and the engine online?", ok: false });
    } finally { setBusy(false); }
  };

  // Watch a file this node already seeds: just open the player (it streams from the
  // local seed store) — no redundant download of our own file.
  const watchMine = () => resolved && onStream && onStream(resolved.infohash);
  const copyInfohash = () => {
    if (!resolved) return;
    navigator.clipboard?.writeText(resolved.infohash);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <section style={css("padding:30px 24px 40px;display:flex;justify-content:center")}>
      <div style={css("width:100%;max-width:720px")}>
        <h1 style={css("margin:0 0 6px;font-size:24px;font-weight:800;letter-spacing:-0.02em;text-align:center")}>Add a stream</h1>
        <div style={css("font-size:13px;color:#8b8299;text-align:center;margin-bottom:26px")}>Resolve an infohash through the DHT, or share a file of your own</div>

        {msg && (
          <div style={css("margin-bottom:16px;padding:11px 15px;border-radius:11px;font:500 12px 'JetBrains Mono';word-break:break-all;" + (msg.ok
            ? "background:rgba(70,211,154,0.1);border:1px solid rgba(70,211,154,0.3);color:#74e3b0"
            : "background:rgba(240,121,94,0.1);border:1px solid rgba(240,121,94,0.3);color:#f0795e"))}>{msg.text}</div>
        )}

        <div style={css("padding:20px;background:#15111d;border:1px solid #221d2c;border-radius:16px;margin-bottom:16px")}>
          <div style={css("font:700 11px 'JetBrains Mono';color:#756c85;letter-spacing:0.06em;margin-bottom:11px")}>PASTE AN INFOHASH</div>
          <div style={css("display:flex;gap:10px")}>
            <div style={css("flex:1;display:flex;align-items:center;gap:11px;padding:13px 15px;background:#100d17;border:1px solid #2c2638;border-radius:12px")}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#c64ff0" strokeWidth="2"><circle cx="12" cy="12" r="3" /><path d="M12 2v4M12 18v4M2 12h4M18 12h4" /></svg>
              <input value={infohash} onChange={(e) => setInfohash(e.target.value)} placeholder="paste a 40-hex infohash" style={inputStyle} />
            </div>
            <Hover as="button" onClick={() => doDownload(true)} disabled={busy || peek?.status === "none"} base={"display:flex;align-items:center;gap:8px;padding:0 20px;border:none;border-radius:12px;color:#fff;font:700 13px 'Manrope';white-space:nowrap;" + (busy || peek?.status === "none" ? "background:#2c2638;color:#6b6379;cursor:not-allowed" : "background:linear-gradient(135deg,#c64ff0,#9b3ec9);cursor:pointer")} hover={busy || peek?.status === "none" ? "" : "filter:brightness(1.1)"}>
              <svg width="15" height="15" viewBox="0 0 24 24"><path d="M8 5.5l11 6.5-11 6.5z" fill="currentColor" /></svg>
              {busy ? "Starting…" : "Watch now"}
            </Hover>
          </div>
          {/* secondary: fetch in the background without opening the player */}
          <div style={css("display:flex;justify-content:flex-end;margin-top:8px")}>
            <Hover as="button" onClick={() => doDownload(false)} disabled={busy || peek?.status === "none"}
              base={"display:flex;align-items:center;gap:6px;background:none;border:none;font:600 11px 'JetBrains Mono';padding:2px 4px;" + (busy || peek?.status === "none" ? "color:#3f3a4b;cursor:not-allowed" : "color:#8b8299;cursor:pointer")}
              hover={busy || peek?.status === "none" ? "" : "color:#c7bfd6"}>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 3v12M7 11l5 4 5-4M5 20h14" /></svg>
              or download only
            </Hover>
          </div>
          {/* live DHT peek — real swarm size, resolved without downloading (replaces the old static FIND_VALUE diagram) */}
          <div style={css("display:flex;align-items:center;gap:11px;margin-top:14px;padding:12px 15px;background:#100d17;border:1px solid #1c1826;border-radius:12px;min-height:22px")}>
            <span style={css("width:24px;height:24px;flex-shrink:0;border-radius:50%;background:linear-gradient(150deg,#c64ff0,#7c2fd0);display:flex;align-items:center;justify-content:center;font:800 8px 'Manrope';color:#fff")}>YOU</span>
            <div style={css("flex:1;min-width:0;display:flex;align-items:center;gap:9px;flex-wrap:wrap;font:600 11px 'JetBrains Mono'")}>
              {!peek && <span style={css("color:#5f5670")}>Paste an infohash to look up its swarm in the DHT</span>}
              {peek?.status === "looking" && (<>
                <span style={css("width:8px;height:8px;border-radius:50%;background:#6cc8e8;animation:wsPulse 1.2s infinite")} />
                <span style={css("color:#6cc8e8")}>FIND_VALUE… resolving in the DHT</span>
              </>)}
              {peek?.status === "none" && (<>
                <span style={css("width:8px;height:8px;border-radius:50%;background:#f0795e")} />
                <span style={css("color:#f0795e")}>Not announced in the DHT — no one is sharing this infohash</span>
              </>)}
              {peek?.status === "found" && (<>
                <span style={css("width:8px;height:8px;border-radius:50%;background:#74e3b0;box-shadow:0 0 8px rgba(70,211,154,0.6)")} />
                <span style={css("color:#74e3b0")}>{peek.peers} {peek.peers === 1 ? "peer" : "peers"} in swarm</span>
                <span style={css("color:#322b40")}>·</span>
                <span style={css("color:#a99fbb")}>{humanBytes(peek.totalLength)}</span>
                <span style={css("color:#322b40")}>·</span>
                <span style={css("color:#a99fbb")}>{peek.pieceCount} pieces</span>
              </>)}
            </div>
            {peek?.status === "found" && (
              <span title="The DHT peer set has no seeder-vs-leecher flag — this is the whole swarm. The seeders/leechers split is shown live once the download connects and reads each peer's bitfield." style={css("flex-shrink:0;font:600 9.5px 'JetBrains Mono';color:#5f5670;cursor:help")}>ⓘ S / L live during transfer</span>
            )}
          </div>
        </div>

        {resolved && (
        <div style={css("padding:20px;background:linear-gradient(120deg,#1d1430,#15111d 70%);border:1px solid #34284a;border-radius:16px;margin-bottom:24px")}>
          <div style={css("display:flex;gap:17px")}>
            <div style={css("width:128px;flex-shrink:0;aspect-ratio:16/10;border-radius:11px;background:repeating-linear-gradient(135deg,#241634,#241634 9px,#281a38 9px,#281a38 18px);border:1px solid #34284a;display:flex;align-items:center;justify-content:center")}>
              <span style={css("font:600 9px 'JetBrains Mono';color:#6b5f80")}>poster</span>
            </div>
            <div style={css("flex:1;min-width:0")}>
              <div style={css("display:flex;align-items:center;gap:9px;margin-bottom:4px")}>
                <span style={css("font:700 9.5px 'JetBrains Mono';color:#74e3b0;background:rgba(70,211,154,0.12);padding:2px 8px;border-radius:5px")}>{resolved.mine ? "SHARED · SEEDING" : "RESOLVED"}</span>
              </div>
              <h2 style={css("margin:0 0 12px;font-size:16px;font-weight:800;letter-spacing:-0.01em;font-family:'JetBrains Mono',monospace;word-break:break-all")}>{shortId(resolved.infohash)}</h2>
              <div style={css("display:grid;grid-template-columns:repeat(3,1fr);gap:10px 18px")}>
                {[["PIECE SIZE", humanBytes(resolved.pieceSize)], ["PIECES", String(resolved.pieceCount)], ["TOTAL", humanBytes(resolved.totalLength)]].map(([l, v]) => (
                  <div key={l}><div style={css("font:600 9px 'JetBrains Mono';color:#6b5f80")}>{l}</div><div style={css("font:600 12.5px 'JetBrains Mono';color:#d8d0e4;margin-top:2px")}>{v}</div></div>
                ))}
              </div>
            </div>
          </div>
          <div style={css("display:flex;gap:11px;margin-top:18px")}>
            {resolved.mine ? (
              // We already seed this file — Watch opens the player (no redundant download);
              // Copy hands the infohash token to a peer. No "download your own file".
              <>
                <Hover as="button" onClick={watchMine} base="flex:1;display:flex;align-items:center;justify-content:center;gap:9px;padding:13px;background:linear-gradient(135deg,#c64ff0,#9b3ec9);border:none;border-radius:12px;color:#fff;font:700 13.5px 'Manrope';cursor:pointer" hover="filter:brightness(1.1)">
                  <svg width="16" height="16" viewBox="0 0 24 24"><path d="M8 5.5l11 6.5-11 6.5z" fill="#fff" /></svg> Watch
                </Hover>
                <Hover as="button" onClick={copyInfohash} base={"display:flex;align-items:center;justify-content:center;gap:9px;padding:13px 22px;border-radius:12px;font:700 13.5px 'Manrope';cursor:pointer;" + (copied ? "background:rgba(70,211,154,0.12);border:1px solid rgba(70,211,154,0.35);color:#74e3b0" : "background:#1a1623;border:1px solid #2c2638;color:#c7bfd6")} hover={copied ? "" : "background:#221d2c"}>
                  {copied
                    ? <><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6L9 17l-5-5" /></svg> Copied</>
                    : <><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V5a2 2 0 0 1 2-2h10" /></svg> Copy infohash</>}
                </Hover>
              </>
            ) : (
              // Someone else's file — Watch now fetches + opens the player; Download only
              // fetches in the background (shows up in the Library).
              <>
                <Hover as="button" onClick={() => doDownload(true)} base="flex:1;display:flex;align-items:center;justify-content:center;gap:9px;padding:13px;background:linear-gradient(135deg,#c64ff0,#9b3ec9);border:none;border-radius:12px;color:#fff;font:700 13.5px 'Manrope';cursor:pointer" hover="filter:brightness(1.1)">
                  <svg width="16" height="16" viewBox="0 0 24 24"><path d="M8 5.5l11 6.5-11 6.5z" fill="#fff" /></svg> Watch now
                </Hover>
                <Hover as="button" onClick={() => doDownload(false)} base="display:flex;align-items:center;justify-content:center;gap:9px;padding:13px 22px;background:#1a1623;border:1px solid #2c2638;border-radius:12px;color:#c7bfd6;font:700 13.5px 'Manrope';cursor:pointer" hover="background:#221d2c">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 3v12M7 11l5 4 5-4M5 20h14" /></svg> Download only
                </Hover>
              </>
            )}
          </div>
        </div>
        )}

        <div style={css("display:flex;align-items:center;gap:14px;margin-bottom:20px")}>
          <span style={css("flex:1;height:1px;background:#221d2c")} />
          <span style={css("font:600 11px 'JetBrains Mono';color:#5f5670")}>OR SHARE YOUR OWN</span>
          <span style={css("flex:1;height:1px;background:#221d2c")} />
        </div>

        <div style={css("padding:24px;border:1.5px dashed #3a3148;border-radius:16px;text-align:center")}>
          <div style={css("width:52px;height:52px;margin:0 auto 14px;border-radius:14px;background:rgba(198,79,240,0.1);border:1px solid rgba(198,79,240,0.3);display:flex;align-items:center;justify-content:center")}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#c64ff0" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 16V4M7 9l5-5 5 5M5 20h14" /></svg>
          </div>
          <div style={css("font-size:15px;font-weight:700;margin-bottom:6px")}>Share a file from this machine</div>
          <div style={css("font-size:12.5px;color:#8b8299;max-width:420px;margin:0 auto 16px;line-height:1.55")}>weStream hashes it with SHA-1, splits it into 256 KB pieces, and announces you as the first seed in the DHT.</div>
          <div style={css("display:flex;gap:10px;max-width:520px;margin:0 auto")}>
            <div style={css("flex:1;display:flex;align-items:center;padding:11px 14px;background:#100d17;border:1px solid #2c2638;border-radius:11px")}>
              <input value={sharePath} onChange={(e) => setSharePath(e.target.value)} placeholder="Click Browse… or paste an absolute path" style={inputStyle} />
            </div>
            {window.ws?.pickFile && (
              <Hover as="button"
                onClick={async () => { const p = await window.ws.pickFile(); if (p) setSharePath(p); }}
                base="display:flex;align-items:center;gap:7px;padding:0 16px;background:#1b1722;border:1px solid #2c2638;border-radius:11px;color:#cdc4dc;font:600 13px 'Manrope';cursor:pointer;white-space:nowrap"
                hover="border-color:#3a3148;background:#211b2b">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 7h6l2 2h10v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" /></svg>
                Browse…
              </Hover>
            )}
            <Hover as="button" onClick={doShare} disabled={busy} base={"display:flex;align-items:center;gap:8px;padding:0 22px;background:linear-gradient(135deg,#c64ff0,#9b3ec9);border:none;border-radius:11px;color:#fff;font:700 13px 'Manrope';white-space:nowrap;cursor:" + (busy ? "wait" : "pointer") + ";opacity:" + (busy ? "0.6" : "1")} hover="filter:brightness(1.1)">{busy ? "Sharing…" : "Share"}</Hover>
          </div>
        </div>
      </div>
    </section>
  );
}

/* ===================== DHT INSPECTOR ===================== */
function DhtScreen({ buckets, status, routing, rpcEvents, dht }) {
  const nodeId = status ? formatId(status.nodeId) : "4287ad37 811db73f 862115ba 0960cc6c 9d54569e";
  const endpoint = status
    ? `udp://${status.host}:${status.udpPort} · up ${formatUptime(status.uptimeMs)}`
    : "udp://127.0.0.1:1100 · seed node · up 14m 22s";
  const contactCount = routing ? routing.contacts.length : 38;
  const log = rpcEvents || rpcLog; // live events, or the mock until the first poll
  const keys = dht ? storedKeysFrom(dht) : storedKeys; // live store snapshot, or the mock until first poll
  const storedCount = dht ? dht.storedCount : storedKeys.length;
  return (
    <section style={css("padding:22px 24px 30px")}>
      <div style={css("margin-bottom:18px")}>
        <h1 style={css("margin:0 0 5px;font-size:22px;font-weight:800;letter-spacing:-0.02em")}>DHT inspector</h1>
        <div style={css("font-size:13px;color:#8b8299")}>Kademlia routing internals · 160-bit ID space · XOR metric · k = 20</div>
      </div>

      <div style={css("display:flex;align-items:center;gap:24px;padding:18px 20px;background:linear-gradient(120deg,#1d1430,#15111d 70%);border:1px solid #34284a;border-radius:16px;margin-bottom:16px;flex-wrap:wrap")}>
        <div style={css("flex:1;min-width:280px")}>
          <div style={css("font:700 10px 'JetBrains Mono';color:#6b5f80;letter-spacing:0.06em;margin-bottom:6px")}>THIS NODE · NODEID (SHA-1)</div>
          <div style={css("font:600 16px 'JetBrains Mono';color:#e7e1ef;letter-spacing:0.04em;word-break:break-all")}>{nodeId}</div>
          <div style={css("font:500 11.5px 'JetBrains Mono';color:#8b8299;margin-top:6px")}>{endpoint}</div>
        </div>
        <div style={css("display:flex;gap:26px")}>
          {[["BUCKETS", String(buckets.length), "#f4f1f8", "/160"], ["CONTACTS", String(contactCount), "#c08fe8"], ["STORED KEYS", String(storedCount), "#74e3b0"]].map(([l, v, c, suf]) => (
            <div key={l}><div style={css("font:600 9.5px 'JetBrains Mono';color:#6b5f80")}>{l}</div><div style={css("font-size:22px;font-weight:800;margin-top:3px;color:" + c)}>{v}{suf && <span style={css("font-size:12px;color:#5f5670")}>{suf}</span>}</div></div>
          ))}
        </div>
      </div>

      <div style={css("display:grid;grid-template-columns:1.15fr 1fr;gap:16px;align-items:start")}>
        <div style={css("display:flex;flex-direction:column;gap:16px")}>
          <div style={css("background:#15111d;border:1px solid #221d2c;border-radius:16px;overflow:hidden")}>
            <div style={css("display:flex;align-items:center;justify-content:space-between;padding:14px 18px;border-bottom:1px solid #221d2c")}>
              <span style={css("font-size:13px;font-weight:800")}>Routing table · k-buckets</span>
              <span style={css("font:500 10.5px 'JetBrains Mono';color:#756c85")}>distance = id ⊕ target</span>
            </div>
            <div style={css("padding:8px 10px")}>
              {buckets.map((b) => (
                <Hover key={b.idx} base="display:grid;grid-template-columns:56px 1fr 64px;gap:12px;align-items:center;padding:8px 8px;border-radius:8px" hover="background:#191421">
                  <span style={css("font:600 11.5px 'JetBrains Mono';color:#c08fe8")}>b{b.idx}</span>
                  <div style={css("display:flex;align-items:center;gap:8px")}>
                    <div style={css("flex:1;height:7px;border-radius:999px;background:#221d2c;overflow:hidden")}><div style={css("height:100%;width:" + b.fill + ";background:" + b.barColor)} /></div>
                  </div>
                  <span style={css("font:500 11px 'JetBrains Mono';color:#8b8299;text-align:right")}>{b.count}/20</span>
                </Hover>
              ))}
            </div>
          </div>

          <div style={css("background:#15111d;border:1px solid #221d2c;border-radius:16px;overflow:hidden")}>
            <div style={css("padding:14px 18px;border-bottom:1px solid #221d2c;font-size:13px;font-weight:800")}>Stored keys <span style={css("font:500 11px 'JetBrains Mono';color:#756c85")}>infohash → seed metadata</span></div>
            {keys.length === 0 && (
              <div style={css("padding:16px 18px;font:500 11.5px 'JetBrains Mono';color:#5f5670")}>no keys stored on this node yet</div>
            )}
            {keys.map((k) => (
              <div key={k.key} style={css("display:flex;align-items:center;gap:13px;padding:12px 18px;border-bottom:1px solid #1c1826")}>
                <span style={css("width:30px;height:30px;flex-shrink:0;border-radius:8px;background:rgba(198,79,240,0.1);border:1px solid rgba(198,79,240,0.28);display:flex;align-items:center;justify-content:center")}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#c64ff0" strokeWidth="2"><path d="M7 14a4 4 0 1 1 4-4M11 10l9 9M17 16l2 2M14 13l2 2" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </span>
                <div style={css("flex:1;min-width:0")}>
                  <div style={css("font:600 12px 'JetBrains Mono';color:#e7e1ef")}>{k.key}</div>
                  <div style={css("font:500 10.5px 'JetBrains Mono';color:#756c85;margin-top:3px")}>{k.value}</div>
                </div>
                <span style={css("font:500 10px 'JetBrains Mono';color:#6b5f80;white-space:nowrap")}>ttl {k.ttl}</span>
              </div>
            ))}
          </div>
        </div>

        <div style={css("background:#100d17;border:1px solid #221d2c;border-radius:16px;overflow:hidden;height:100%")}>
          <div style={css("display:flex;align-items:center;justify-content:space-between;padding:14px 18px;border-bottom:1px solid #221d2c;background:#15111d")}>
            <span style={css("font-size:13px;font-weight:800")}>RPC activity</span>
            <span style={css("display:flex;align-items:center;gap:7px;font:600 10px 'JetBrains Mono';color:#74e3b0")}><span style={css("width:6px;height:6px;border-radius:50%;background:#46d39a;animation:wsPulse 1.6s infinite")} />live</span>
          </div>
          <div style={css("padding:8px 6px")}>
            {log.map((r, i) => (
              <div key={i} style={css("display:grid;grid-template-columns:64px 16px 88px 1fr auto;gap:9px;align-items:center;padding:6px 12px;font:500 11px 'JetBrains Mono'")}>
                <span style={{ color: "#5f5670" }}>{r.time}</span>
                <span style={{ color: r.dirColor, fontWeight: 700 }}>{r.dir}</span>
                <span style={{ color: r.typeColor, fontWeight: 600 }}>{r.type}</span>
                <span style={css("color:#8b8299;white-space:nowrap;overflow:hidden;text-overflow:ellipsis")}>{r.peer}</span>
                <span style={{ color: r.resultColor, whiteSpace: "nowrap" }}>{r.result}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
