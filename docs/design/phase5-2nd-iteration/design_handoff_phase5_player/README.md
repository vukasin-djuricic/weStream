# Handoff: weStream — Phase 5 Player UI (JavaFX)

## Overview
This bundle is the visual design for **Phase 5** of weStream — the single-window media/player app that sits on top of the existing Kademlia DHT + transfer engine. It covers five screens: **Now Playing**, **Swarm map**, **Library**, **Add Stream**, and **DHT Inspector**. The whole concept is "community-powered streaming," so the **swarm visualization is the hero**.

## ⚠️ About the design files (read first)
The `*.dc.html` files in this bundle are **design references built in HTML** — prototypes that show the intended look, layout, and motion. **They are not code to ship.** Your job is to **recreate these designs in JavaFX** using the project's established direction:

> JavaFX (UI) + **vlcj/libVLC** (embedded video) + Gradle, packaged with `jpackage`/`jlink` (see the repo's `CLAUDE.md`).

Open the HTML files in a browser to study them (click the left sidebar to switch screens). Use the PNGs in `reference_screens/` as the pixel reference. Then build the equivalent in FXML + JavaFX CSS + controllers.

**Important engine boundary:** nothing in this UI layer may import into or weaken `core.kademlia` (the pure-JDK rule from `CLAUDE.md` still holds). The player *reads from* the engine (peers, speeds, pieces, routing table, RPC events) and *drives* `SlidingWindowPicker.setPlayhead` — it does not live inside the engine.

## Fidelity
**High-fidelity.** Final colors, typography, spacing, and layout. Recreate pixel-faithfully. Exact values are in `DESIGN_TOKENS.md` and `theme.css`.

## ⚠️ Why a first JavaFX build often looks "different" — read this before you start
This is a **recreation brief, not a transpile.** You are rebuilding an HTML/CSS design in a different toolkit (JavaFX), so a first pass will diverge unless you actively close these gaps. In order of impact:

1. **FONTS ARE #1.** If Manrope + JetBrains Mono are not loaded, JavaFX silently falls back to the system font and *everything* looks wrong — weight, width, spacing, the mono data columns. **Bundle the TTFs and `Font.loadFont(...)` them at startup BEFORE building the scene** (see snippet below). Verify the fonts actually applied before judging anything else.
2. **No flexbox / no `%` in JavaFX CSS.** Proportions only hold if you build the layout with the right panes and grow flags: `HBox`/`VBox` with `spacing`, `HBox.setHgrow(node, ALWAYS)` / `VBox.setVgrow(...)` for the "fills" regions, `GridPane` with `ColumnConstraints(percentWidth=...)` for the stat-card rows and the DHT two-column split. Fixed widths (sidebar 230, right rail 322, title bar 46) must be set explicitly. Don't try to do layout in CSS.
3. **Colors/gradients/radii need `-fx-` syntax** and **both** `-fx-background-radius` *and* `-fx-border-radius` (setting one rounds the fill but not the border). Shadows/glows are `-fx-effect: dropshadow(...)`, not `box-shadow`.
4. **The HTML is the source of truth for exact values.** When a number looks off, open `weStream Player.dc.html` and read the literal px/hex from the inline `style="…"` on that element — don't eyeball it from the screenshot.
5. **Iterate visually.** Build a screen, run it, screenshot it, put it side-by-side with the matching PNG in `reference_screens/`, and fix the deltas. One pass is never enough; 2–3 visual diffs gets you there.

If your result looked very different, **check #1 first** — a missing font accounts for ~80% of "this looks nothing like the design" outcomes.

### The reliable way to drive this with Claude Code
> "Read `design_handoff_phase5_player/README.md` and `DESIGN_TOKENS.md`. Recreate the **Swarm** screen first in JavaFX (FXML + controller + the provided `theme.css`). Load the bundled fonts at startup. Then run it, screenshot it, compare against `reference_screens/02_swarm.png`, and iterate until it matches. Do one screen at a time."

Do **one screen at a time** with a visual compare loop — don't ask for all five in a single shot.

## What's in this folder
```
design_handoff_phase5_player/
├── README.md                  ← you are here
├── DESIGN_TOKENS.md           ← every color / type / radius / spacing value
├── theme.css                  ← STARTER JavaFX stylesheet (looked-up colors + base classes)
├── weStream Player.dc.html    ← the 5-screen app (Direction A · Midnight Neon)
├── weStream Directions.dc.html← 3 alternative visual directions (A/B/C) for the swarm hero
└── reference_screens/
    ├── 01_player.png
    ├── 01b_player_sliding_window.png  ← scrolled, shows the SlidingWindowPicker strip + footer
    ├── 02_swarm.png
    ├── 02b_swarm_table.png            ← scrolled, shows the peer table
    ├── 03_library.png
    ├── 04_add_stream.png
    └── 05_dht_inspector.png
```

## Web → JavaFX translation cheatsheet
The HTML uses flexbox + inline styles. Map it like this:

| HTML / CSS concept | JavaFX equivalent |
|---|---|
| `display:flex; gap` (row/col) | `HBox` / `VBox` with `spacing=` |
| `display:grid` | `GridPane` (fixed cols) or `TilePane`/`FlowPane` (auto-wrap cards) |
| `border-radius` | `-fx-background-radius` **and** `-fx-border-radius` |
| `box-shadow` / glow | `-fx-effect: dropshadow(...)` or a `DropShadow` effect in code |
| `linear/radial-gradient(...)` | same syntax inside `-fx-background-color` |
| `backdrop-filter: blur` | not native — fake with a semi-opaque fill |
| `:hover` | `:hover` works in JavaFX CSS |
| `position:absolute` (swarm nodes) | a `Pane` with `setLayoutX/Y` (absolute positioning) |
| monospace data | the **JetBrains Mono** font |
| `aspect-ratio 16/9` | bind height to width, or set both explicitly |
| `%` widths | JavaFX CSS has no `%`; use layout panes + `HBox.setHgrow(..., ALWAYS)` |

### Fonts (do this once at startup)
Bundle the TTFs in `src/main/resources/fonts/` and load before building the scene:
```java
Font.loadFont(getClass().getResourceAsStream("/fonts/Manrope-Variable.ttf"), 13);
Font.loadFont(getClass().getResourceAsStream("/fonts/JetBrainsMono-Regular.ttf"), 12);
// then in CSS: -fx-font-family: "Manrope";  /  class .mono { -fx-font-family: "JetBrains Mono"; }
```
Both fonts are SIL OFL (free to bundle). Get them from Google Fonts.

### Suggested structure
- `AppShell.fxml` — root `BorderPane`: top = custom title bar (46px), left = sidebar (230px `VBox`), center = a `StackPane` that swaps the 5 views.
- One FXML + controller per screen: `PlayerView`, `SwarmView`, `LibraryView`, `AddStreamView`, `DhtView`.
- `theme.css` attached to the Scene.
- Use `StageStyle.UNDECORATED` for the borderless look; implement the traffic-light dots + window drag yourself (mouse-press offset on the title bar).

---

## Screens

### 1. Now Playing  (`reference_screens/01_player.png`)
**Purpose:** watch a stream while seeing live swarm activity.
**Layout:** content splits into a video column (fills) + a **322px** right rail (`HBox`).
- **Header row:** title `Tears of Steel` (H1) + a green `STREAMING` pill; below, a mono meta line `2160p · HEVC | 1.74 GB | infohash 9f2a…c081`. Right side: two ghost buttons `Cast`, `Share`.
- **Video surface:** 16:9, radius 16, dark diagonal-stripe placeholder. **This is where vlcj renders** (PixelBuffer → `ImageView`/`Canvas`). Centered glowing play button (pulsing). Top-left overlay pill `buffer 6.2s ahead`.
- **Scrubber:** 6px track (`#221d2c`); buffered region in translucent cyan `rgba(108,200,232,0.32)`; played region in the magenta gradient; 14px white knob with an accent halo. Time row below in mono: `00:42:13` … `−00:50:42`.
- **Sliding-window strip** (the engine seam — wire to `SlidingWindowPicker`): a card titled "Sliding window" + mono `SlidingWindowPicker · W=32`. A row of ~44 thin cells: **have** = `#c64ff0`, **in-flight** = `#6cc8e8`, **missing** = `#2a2435`. Build as an `HBox` of `Region`s (or a `Canvas`). Legend + a mono footer (`piece 612 · playhead`, `requesting 8 · window end 644`).
- **Right rail "Live swarm":** header + accent peer-count pill; two stat tiles (DOWNLOAD cyan / UPLOAD pink); a list of peer rows (square glyph badge, mono id + location, a have% mini-bar, ↓/↑ speeds); a ghost button `View full swarm map →` that navigates to the Swarm screen.

### 2. Swarm map  (`reference_screens/02_swarm.png`) — THE HERO
**Purpose:** show the live peer mesh; "the more peers, the faster the stream."
- **Header:** H1 `Swarm map` + subtitle; right: green `auto-refreshing` pill.
- **Stat cards:** `GridPane`, 5 equal columns — CONNECTED PEERS `24`, SWARM HEALTH `Excellent` (green), DOWNLOAD `11.4 MB/s` (cyan), UPLOAD `3.2 MB/s` (pink), SHARE RATIO `1.84`.
- **Graph well:** 540px tall, radius 18, radial accent glow. Implement as a `Pane`:
  - Central **YOU** node — 74px circle, magenta gradient, drop-shadow glow, a dashed orbit ring (a `Circle` with `strokeDashArray`, rotated by a `RotateTransition`).
  - **Peer nodes** laid out on two elliptical rings via polar math (port of the design's `buildSwarm()` — see below). Active peers get a colored 2px ring + glow; idle peers are muted grey and smaller.
  - **Connections:** a `Line` from center to each peer. Active = colored + dashed with an animated `strokeDashOffset` (`Timeline`, ~1.1s loop) to suggest flow; idle = thin static `#2a2435`.
  - Legend bottom-left.
- **Peer table:** columns `PEER | LOCATION | LATENCY | PIECES | DOWN | UP | CONN | XOR DIST`. Use a `TableView` (style rows to match) or a `GridPane` of styled rows. Pieces = a mini progress bar + %. DOWN cyan, UP pink, CONN green for TCP / grey for idle, all mono.

**Port of `buildSwarm()` (polar layout):**
```java
// for peer i of n, ring = i % 2 (inner/outer ellipse)
double rx = (ring==1) ? 0.41 : 0.26;   // fraction of well width
double ry = (ring==1) ? 0.43 : 0.30;   // fraction of well height
double ang = (double)i / n * 2*Math.PI - Math.PI/2;
double x = wellW * (0.5 + rx*Math.cos(ang));
double y = wellH * (0.5 + ry*Math.sin(ang));
// node size 46 (fast, >1.5 MB/s) / 38 (active) / 30 (idle)
```

### 3. Library  (`reference_screens/03_library.png`)
**Purpose:** your seeds + downloads, and continue-watching.
- Header H1 `Library` + subtitle; right: a search field + a magenta-gradient `+ Share a file` button (→ Add Stream).
- **Continue-watching** feature card: gradient `#211433 → #15111d`, violet border, ambient glow blob, poster thumb with a magenta play button, title, mono meta, and a thin progress bar (`width 45%`). Whole card navigates to Player.
- **Your shares** (section label + green `seeding` pill): responsive card grid (`minmax(230px)` → `TilePane`/`FlowPane`, prefTileWidth ~230). Each card: 16:10 poster placeholder (subtle gradient), top-left resolution chip, top-right status pill, a 3px progress line at poster bottom, then title + mono meta (`size` left, `N peers` accent right).
- **Downloads** section: same card, with `67% ↓` / `✓ Complete` / `23% ↓` statuses (magenta for downloading, cyan/neutral for complete).

### 4. Add Stream  (`reference_screens/04_add_stream.png`)
**Purpose:** resolve an infohash via DHT, or share a file.
- Centered column, max width ~720.
- **Paste an infohash** card: a mono input (showing `4287ad37…d54569e`) + a magenta `Resolve →` button. Below, a **DHT lookup viz** strip: `YOU` chip → `FIND_VALUE` → three hollow k-closest dots → `VALUE` → `24 seeds` (dashed connectors).
- **Resolved metadata** card (violet gradient): poster thumb + `RESOLVED` green chip + `announced 8s ago`; title; a 3-col grid PIECE SIZE `256 KB` / PIECES `6,812` / TOTAL `1.74 GB`; then `▶ Stream now` (primary, → Player) + `⭳ Download` (ghost).
- Divider `OR SHARE YOUR OWN`.
- **Drop zone:** dashed border, upload glyph in an accent tile, `Drag a file here to share`, and the explainer: "weStream hashes it with SHA-1, splits it into 256 KB pieces, and announces you as the first seed in the DHT." (Hook to a `FileChooser` / drag-and-drop `setOnDragOver/Dropped`.)

### 5. DHT Inspector  (`reference_screens/05_dht_inspector.png`)
**Purpose:** engine/nerd view of the live Kademlia state.
- Header H1 `DHT inspector` + subtitle `…160-bit ID space · XOR metric · k = 20`.
- **Identity card** (violet gradient): big mono NodeId grouped in 8-char chunks; endpoint + uptime line; right side three figures BUCKETS `11/160`, CONTACTS `38`, STORED KEYS `7`.
- **Two-column** (`GridPane`, ~1.15fr / 1fr):
  - *Left:* **Routing table · k-buckets** — rows `b159 … b149`, each a fill bar (`count/20`); buckets ≥18 use the magenta gradient, mid grey-violet, low dim. Below: **Stored keys** list (key chip + `infohash → seed metadata` + `ttl`).
  - *Right:* **RPC activity** terminal panel (inset `#100d17`) with a live green dot. Monospace log rows: `time | dir (→ pink / ← cyan) | type | peer | result`. Type colors: FIND_NODE/FIND_VALUE violet, STORE amber, PING cyan, replies green; `timeout` in red.

---

## Interactions & behavior
- **Navigation:** clicking a sidebar item swaps the center view and sets the `active` style on that nav button (magenta pill + 3px left accent bar). Several in-content buttons navigate too (continue-watching → Player, `View full swarm map` → Swarm, `Stream now`/`+ Share a file` → Player/Add Stream).
- **Motion:** dashed connection lines animate (flow); the live/seeding dots pulse opacity (~1.6–2s); the YOU orbit ring slowly rotates; the play button glows. Keep these subtle — `Timeline`/`FadeTransition`/`RotateTransition`.
- **Hover:** nav items lighten; cards lift border to `#3a3148`; buttons brighten.
- All of the above is **static mock data** in the prototype — replace with live engine values via `Platform.runLater(...)` on update events.

## State / data the UI needs from the engine
- **Per peer:** short id, host/endpoint, location+latency (optional), have%, ↓ rate, ↑ rate, connection state, XOR distance — feeds the swarm rail, graph, and table.
- **Aggregate:** connected-peer count, total ↓/↑, swarm health, share ratio.
- **Playback:** current piece / playhead, window size `W`, per-piece state (have/in-flight/missing) — feeds the sliding-window strip; the playhead drives `SlidingWindowPicker.setPlayhead`.
- **DHT:** self NodeId + endpoint + uptime, bucket fill counts, stored keys (infohash→metadata, ttl), a stream of RPC events for the activity log.
- **Library:** list of shares + downloads (title, resolution, size, status, progress, peer count).

## Design tokens & theme
See `DESIGN_TOKENS.md` for every value, and `theme.css` for a ready-to-attach JavaFX stylesheet (looked-up colors + base classes: `.ws-card`, `.pill-green`, `.btn-primary`, `.nav-item.active`, `.you-node`, …). Start from `theme.css` and extend.

## Picking a different look
The built design is **Direction A (Midnight Neon)**. `weStream Directions.dc.html` shows two alternatives — **B (Engine Terminal:** mono, phosphor-green, sharp, grid) and **C (Clean Studio:** light, violet, airy). The *layout/components are identical across all three*; only the tokens change. If the team prefers B or C, ask the designer for that token set and swap `theme.css` — the FXML stays the same.

## Assets
No raster assets are required. Posters/video are placeholders (the user supplies real media at runtime; video renders via vlcj). Icons in the prototype are simple inline SVGs — reimplement as JavaFX `SVGPath`/`Region` shapes or pull from an icon set (e.g. Ikonli) matched to the stroke style. Fonts: Manrope + JetBrains Mono (bundle TTFs).
