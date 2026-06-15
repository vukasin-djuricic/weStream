# weStream — Phase 5 Player · React port

A faithful, **running** React port of the design (Direction A · Midnight Neon). Verified to render identically to the prototype across all five screens.

```
react_handoff_phase5/
├── README.md
└── src/
    ├── WeStreamApp.jsx   ← the whole app: title bar, sidebar, 5 screens
    ├── data.js           ← mock data + builders (swap for live engine values)
    └── tokens.js         ← design tokens (colors, gradients, fonts, radii)
```

## Why React is the right target
The design was authored as inline-styled HTML with flexbox/grid — which is exactly how React renders. So this isn't a re-design, it's a near-direct port. No CSS framework required.

## Drop it into a project (Vite — recommended)
```bash
npm create vite@latest westream -- --template react
cd westream && npm install
# copy these files in:
#   src/WeStreamApp.jsx, src/data.js, src/tokens.js
```
`src/main.jsx`:
```jsx
import React from "react";
import ReactDOM from "react-dom/client";
import WeStreamApp from "./WeStreamApp.jsx";
ReactDOM.createRoot(document.getElementById("root")).render(<WeStreamApp />);
```
`index.html` `<head>` — **load the fonts** (without these it looks wrong):
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
```
Then `npm run dev`. That's it — the app renders.

## How styling works (and how to change it)
React needs style **objects**, not CSS strings, so the file uses a tiny `css("…")` helper that parses a CSS string into a style object. This kept the port 1:1 with the design. Three ways to evolve it, pick your taste:
- **Keep `css()`** — fastest, already works.
- **Flatten to objects** — replace `style={css("color:#fff;padding:8px")}` with `style={{ color: "#fff", padding: 8 }}`.
- **Move to Tailwind / CSS Modules** — use `tokens.js` as your source of truth and translate.

`:hover` can't live in an inline style, so there's a small `<Hover base="…" hover="…">` wrapper component that swaps styles on mouse enter/leave. If you adopt Tailwind/CSS, replace it with real `:hover` classes.

Keyframe animations (`wsPulse`, `wsFlow`, `wsOrbit`, `wsGlow`) are injected once via a `<style>{KEYFRAMES}</style>` tag at the top of the app.

## Wiring to the real engine
All screen data comes from `data.js` — replace the mock builders with live values:
- **`buildPieces()`** → per-piece state (have / in-flight / missing) from your `SlidingWindowPicker`; the playhead index drives the strip.
- **`peers` / `buildSwarm()`** → connected peers (id, location, latency, have%, ↓/↑, conn state, XOR distance). `buildSwarm()` also computes the polar x/y layout for the graph — keep that, just feed it real peers.
- **`shares` / `downloads`** → your library items.
- **`buildBuckets()` / `storedKeys` / `rpcLog`** → live Kademlia routing state + an RPC event stream.

Lift `screen` state up or swap for a router (React Router) if you want real URLs per screen. The sidebar already drives navigation via `setScreen`.

## Components map (in `WeStreamApp.jsx`)
- `WeStreamApp` — shell: title bar + sidebar + screen switch (`useState`).
- `PlayerScreen` — video surface (mount `<video>`/vlcj-web here), scrubber, **sliding-window strip**, live swarm rail.
- `SwarmScreen` — stat cards, radial peer graph (SVG lines + absolutely-positioned nodes), peer table.
- `LibraryScreen` + `MediaCard` — continue-watching + shares + downloads grids.
- `AddStreamScreen` — infohash input, DHT lookup viz, resolved metadata, drop zone.
- `DhtScreen` — identity card, k-bucket bars, stored keys, live RPC log.

## Tokens
See `tokens.js`. Brand magenta `#c64ff0` (+ deep `#9b3ec9`), cyan `#6cc8e8` = download, pink `#ee7fb0` = upload, green `#46d39a` = healthy/live. Fonts: **Manrope** (UI) + **JetBrains Mono** (all engine/numeric data).
