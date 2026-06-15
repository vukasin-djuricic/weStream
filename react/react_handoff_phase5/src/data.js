// weStream — Phase 5 Player · mock data + builders
// Faithful port of the logic class from the design prototype.
// Swap these for live engine values once wired up.

// ---- Now Playing: sliding-window piece strip (SlidingWindowPicker) ----
export function buildPieces() {
  const arr = [];
  for (let i = 0; i < 44; i++) {
    let color = "#2a2435"; // missing
    if (i < 12) color = "#c64ff0"; // have
    else if (i < 20) color = i % 2 === 0 ? "#6cc8e8" : "#3a86a8"; // in-flight window
    else if (i < 24) color = "#221d2c";
    arr.push({ idx: 600 + i, color });
  }
  return arr;
}

// ---- Now Playing right rail: top peers ----
export const peers = [
  { glyph: "A3", id: "a3f91c", loc: "AMS · 12ms", havePct: "86%", down: "3.1", up: "0.4", tint: "#c64ff0" },
  { glyph: "7C", id: "7c2104", loc: "FRA · 24ms", havePct: "72%", down: "2.4", up: "1.1", tint: "#6cc8e8" },
  { glyph: "E1", id: "e10b8d", loc: "NYC · 88ms", havePct: "94%", down: "1.9", up: "0.6", tint: "#ee7fb0" },
  { glyph: "B5", id: "b5d330", loc: "LON · 31ms", havePct: "41%", down: "1.6", up: "0.2", tint: "#46d39a" },
  { glyph: "2F", id: "2f9a17", loc: "SGP · 142ms", havePct: "63%", down: "1.2", up: "0.5", tint: "#f4bf4f" },
  { glyph: "9D", id: "9d4e62", loc: "PAR · 19ms", havePct: "78%", down: "0.9", up: "0.8", tint: "#9b8cf0" },
];

// ---- Swarm screen: peer mesh with polar layout ----
const swarmRaw = [
  { glyph: "A3", id: "a3f91c", tint: "#c64ff0", glow: "rgba(198,79,240,0.5)",  down: "3.1", up: "0.4", loc: "Amsterdam", lat: "12ms",  have: "86%", conn: "TCP",  dist: "0x1a3f", active: true },
  { glyph: "7C", id: "7c2104", tint: "#6cc8e8", glow: "rgba(108,200,232,0.45)", down: "2.4", up: "1.1", loc: "Frankfurt", lat: "24ms",  have: "72%", conn: "TCP",  dist: "0x2c41", active: true },
  { glyph: "E1", id: "e10b8d", tint: "#ee7fb0", glow: "rgba(238,127,176,0.4)",  down: "1.9", up: "0.6", loc: "New York",  lat: "88ms",  have: "94%", conn: "TCP",  dist: "0x3e8d", active: true },
  { glyph: "B5", id: "b5d330", tint: "#46d39a", glow: "rgba(70,211,154,0.4)",   down: "1.6", up: "0.2", loc: "London",    lat: "31ms",  have: "41%", conn: "TCP",  dist: "0x4b33", active: true },
  { glyph: "2F", id: "2f9a17", tint: "#f4bf4f", glow: "rgba(244,191,79,0.35)",  down: "1.2", up: "0.5", loc: "Singapore", lat: "142ms", have: "63%", conn: "TCP",  dist: "0x5f17", active: true },
  { glyph: "9D", id: "9d4e62", tint: "#9b8cf0", glow: "rgba(155,140,240,0.4)",  down: "0.9", up: "0.8", loc: "Paris",     lat: "19ms",  have: "78%", conn: "TCP",  dist: "0x6d62", active: true },
  { glyph: "C8", id: "c84a05", tint: "#6cc8e8", glow: "rgba(108,200,232,0.3)",  down: "0.7", up: "0.3", loc: "Tokyo",     lat: "160ms", have: "55%", conn: "TCP",  dist: "0x7c05", active: false },
  { glyph: "04", id: "04ff19", tint: "#5f5670", glow: "rgba(95,86,112,0.3)",    down: "0.5", up: "0.1", loc: "Toronto",   lat: "74ms",  have: "33%", conn: "idle", dist: "0x8419", active: false },
  { glyph: "F2", id: "f2b6cc", tint: "#5f5670", glow: "rgba(95,86,112,0.3)",    down: "0.4", up: "0.2", loc: "Sydney",    lat: "198ms", have: "28%", conn: "idle", dist: "0x9acc", active: false },
  { glyph: "6A", id: "6a01de", tint: "#c64ff0", glow: "rgba(198,79,240,0.35)",  down: "0.8", up: "0.6", loc: "Madrid",    lat: "38ms",  have: "67%", conn: "TCP",  dist: "0xa1de", active: true },
  { glyph: "D7", id: "d73e90", tint: "#5f5670", glow: "rgba(95,86,112,0.3)",    down: "0.3", up: "0.0", loc: "Oslo",      lat: "44ms",  have: "19%", conn: "idle", dist: "0xb390", active: false },
  { glyph: "1B", id: "1b8c44", tint: "#46d39a", glow: "rgba(70,211,154,0.3)",   down: "0.6", up: "0.4", loc: "Dublin",    lat: "29ms",  have: "51%", conn: "TCP",  dist: "0xc844", active: true },
  { glyph: "8E", id: "8e2071", tint: "#5f5670", glow: "rgba(95,86,112,0.3)",    down: "0.2", up: "0.1", loc: "Berlin",    lat: "22ms",  have: "12%", conn: "idle", dist: "0xe071", active: false },
];

export function buildSwarm() {
  const n = swarmRaw.length;
  return swarmRaw.map((d, i) => {
    const ring = i % 2;
    const rx = ring ? 41 : 26;
    const ry = ring ? 43 : 30;
    const ang = (i / n) * Math.PI * 2 - Math.PI / 2;
    const x = +(50 + rx * Math.cos(ang)).toFixed(2);
    const y = +(50 + ry * Math.sin(ang)).toFixed(2);
    const size = d.active ? (parseFloat(d.down) > 1.5 ? 46 : 38) : 30;
    return {
      ...d,
      x, y, size,
      left: x + "%", top: y + "%", sizePx: size + "px",
      border: "2px solid " + d.tint,
      shadow: "0 0 16px " + d.glow,
      connColor: d.conn === "idle" ? "#5f5670" : "#74e3b0",
      // connection line styling (center → peer)
      line: d.active ? d.tint : "#2a2435",
      lw: d.active ? (parseFloat(d.down) > 1.5 ? 2 : 1.4) : 1,
      dash: d.active ? "4 4" : "0",
    };
  });
}

// ---- Library: shares + downloads ----
const seed = { status: "▲ Seeding", statusColor: "#74e3b0", statusBg: "rgba(70,211,154,0.14)", prog: "100%", progColor: "#46d39a" };
const dl = (p) => ({ status: p + " ↓", statusColor: "#e7b0f0", statusBg: "rgba(198,79,240,0.16)", prog: p, progColor: "linear-gradient(90deg,#9b3ec9,#c64ff0)" });
const done = { status: "✓ Complete", statusColor: "#a99fbb", statusBg: "rgba(120,108,140,0.18)", prog: "100%", progColor: "#6cc8e8" };

export const shares = [
  { title: "Tears of Steel", res: "2160p", size: "1.74 GB", peersLabel: "24 peers", posterBg: "linear-gradient(135deg,#2c1838,#15111d)", ...seed },
  { title: "Big Buck Bunny", res: "1080p", size: "691 MB", peersLabel: "8 peers", posterBg: "linear-gradient(135deg,#1a2436,#15111d)", ...seed },
  { title: "Cosmos Laundromat", res: "2160p", size: "2.10 GB", peersLabel: "5 peers", posterBg: "linear-gradient(135deg,#2a1e36,#15111d)", ...seed },
  { title: "CC Music Pack · Vol. 3", res: "FLAC", size: "480 MB", peersLabel: "3 peers", posterBg: "linear-gradient(135deg,#142a26,#15111d)", ...seed },
];
export const downloads = [
  { title: "Sintel", res: "1080p", size: "1.10 GB", peersLabel: "12 peers", posterBg: "linear-gradient(135deg,#2c1838,#15111d)", ...dl("67%") },
  { title: "archlinux-2026.06.01.iso", res: "ISO", size: "1.23 GB", peersLabel: "19 peers", posterBg: "linear-gradient(135deg,#1c2230,#15111d)", ...done },
  { title: "Cosmos · Possible Worlds E1", res: "720p", size: "820 MB", peersLabel: "6 peers", posterBg: "linear-gradient(135deg,#26203a,#15111d)", ...dl("23%") },
];

// ---- DHT inspector ----
export function buildBuckets() {
  const counts = [20, 20, 18, 14, 11, 9, 6, 4, 3, 2, 1];
  return counts.map((c, i) => {
    const idx = 159 - i;
    const full = c >= 18;
    return {
      idx, count: c, fill: Math.round((c / 20) * 100) + "%",
      barColor: full ? "linear-gradient(90deg,#9b3ec9,#c64ff0)" : c >= 8 ? "#9b6cd0" : "#5f5670",
    };
  });
}

export const storedKeys = [
  { key: "4287ad37…d54569e", value: "seed 127.0.0.1:1100 · Tears of Steel · 1.74 GB", ttl: "23h" },
  { key: "9f2ac081…b4e7a2", value: "seed 127.0.0.1:1100 · Big Buck Bunny · 691 MB", ttl: "23h" },
  { key: "c13e7740…0a9f51", value: "seed 127.0.0.1:1100 · Cosmos Laundromat · 2.10 GB", ttl: "22h" },
  { key: "6bd90e22…ff1c08", value: "seed 127.0.0.1:1100 · CC Music Pack v3 · 480 MB", ttl: "21h" },
];

const RPC_TYPE_COLOR = {
  FIND_NODE: "#c08fe8", FIND_VALUE: "#c08fe8", STORE: "#f4bf4f",
  PING: "#6cc8e8", NODES: "#74e3b0", VALUE: "#74e3b0", PONG: "#6cc8e8", STORED: "#74e3b0",
};
const rpcRaw = [
  ["14:22:08", "←", "FIND_VALUE", "a3f91c@92.18.x", "VALUE · 24 peers"],
  ["14:22:08", "→", "VALUE", "a3f91c@92.18.x", "sent 612 B"],
  ["14:22:06", "←", "PING", "7c2104@88.4.x", "PONG · 24ms"],
  ["14:22:04", "→", "FIND_NODE", "e10b8d@71.9.x", "14 contacts"],
  ["14:22:03", "←", "STORE", "b5d330@45.2.x", "STORED ok"],
  ["14:22:01", "→", "FIND_NODE", "2f9a17@13.7.x", "20 contacts"],
  ["14:21:59", "←", "PING", "9d4e62@30.1.x", "PONG · 19ms"],
  ["14:21:57", "→", "STORE", "6a01de@58.6.x", "STORED ok"],
  ["14:21:55", "←", "FIND_NODE", "c84a05@61.0.x", "11 contacts"],
  ["14:21:52", "→", "PING", "1b8c44@29.3.x", "timeout"],
  ["14:21:50", "←", "FIND_VALUE", "f2b6cc@77.8.x", "NODES · 20"],
];
export const rpcLog = rpcRaw.map(([time, dir, type, peer, result]) => ({
  time, dir, type, peer, result,
  dirColor: dir === "→" ? "#ee7fb0" : "#6cc8e8",
  typeColor: RPC_TYPE_COLOR[type] || "#b3aac0",
  resultColor: result === "timeout" ? "#f0795e" : "#6b6379",
}));
