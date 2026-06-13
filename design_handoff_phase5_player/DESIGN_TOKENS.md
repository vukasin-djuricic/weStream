# Design Tokens — weStream Phase 5 Player

Direction **A · Midnight Neon** (the built design). If you instead pick Direction **B (Engine Terminal)** or **C (Clean Studio)** from `weStream Directions.dc.html`, the *layout is identical* — only the values in this file change. Ask for the B/C token sets and they can be dropped in.

## Colors

### Surfaces
| Token | Hex | Use |
|---|---|---|
| bg-app | `#0f0d15` | Window background (behind everything) |
| bg-chrome | `#141019` | Title bar + sidebar |
| bg-panel | `#131019` | Right rails / deep panels |
| surface | `#15111d` | Cards, tiles, table rows |
| surface-2 | `#1a1623` | Raised card (sidebar footer) |
| surface-inset | `#100d17` | Inputs, graph well, RPC log bg |
| hover | `#1d1826` | Nav / list-row hover |

### Borders
| Token | Hex |
|---|---|
| border | `#221d2c` |
| border-soft | `#1c1826` |
| border-strong | `#2c2638` |
| border-violet | `#34284a` (on the purple-tinted gradient cards) |

### Text
| Token | Hex | Use |
|---|---|---|
| text-hi | `#f4f1f8` | Headings |
| text | `#e7e1ef` | Primary values |
| text-mid | `#b3aac0` | Secondary |
| text-soft | `#8b8299` | Subtitles |
| text-lo | `#756c85` | Mono captions / meta |
| text-dim | `#5f5670` | Faint labels |

### Accent & semantic
| Token | Hex | Meaning |
|---|---|---|
| **accent** | `#c64ff0` | Brand magenta-purple — PRIMARY |
| accent-deep | `#9b3ec9` | Gradient end (pair with accent) |
| accent-soft | `#c08fe8` | Accent text on dark |
| cyan | `#6cc8e8` | **Download** direction / buffer |
| pink | `#ee7fb0` | **Upload** direction |
| green | `#46d39a` | Seeding / healthy / live (dot) |
| green-text | `#74e3b0` | Green text on dark |
| amber | `#f4bf4f` | Warning |
| red | `#f0795e` | Error / RPC timeout |

### Signature gradients
- **Primary button / YOU node:** `linear-gradient(135deg, #c64ff0 → #9b3ec9)` (button) / `linear-gradient(150deg, #c64ff0 → #7c2fd0)` (node)
- **Progress / piece fill:** `linear-gradient(90deg, #9b3ec9 → #c64ff0)`
- **Purple feature card:** `linear-gradient(120deg, #1d1430 → #15111d 70%)`
- **Ambient hero glow:** `radial-gradient(circle, rgba(198,79,240,0.18), transparent 70%)`

### Tinted fills (translucent)
- accent fill: `rgba(198,79,240,0.13)`
- green fill: `rgba(70,211,154,0.10)` + border `rgba(70,211,154,0.28)`

## Typography
Two families only.
- **Manrope** — all UI text/headings. Weights used: 400, 500, 600, 700, 800.
- **JetBrains Mono** — ALL engine/numeric data: node IDs, infohashes, speeds, latency, XOR distance, k-bucket labels, RPC log, timestamps. Weights 400/500/600/700.

Both are SIL Open Font License — free to bundle in the app.

### Scale (px)
| Role | Size / weight |
|---|---|
| Screen H1 | 22 / 800 |
| Feature title (hero) | 24 / 800 |
| Panel H2 | 14 / 800 |
| Stat number (big) | 26 / 800 |
| Stat number (tile) | 17–18 / 800 |
| Body | 13–13.5 / 600–700 |
| Subtitle | 13 / 500 |
| Mono data | 11–12.5 / 500–600 |
| Mono caption | 9–10.5 / 500–600 |
| Caps label (tracked) | 10.5 / 700, letter-spacing 0.06em |

## Radii
| Token | px | Use |
|---|---|---|
| r-sm | 5–6 | small pills, code chips |
| r-md | 10–12 | buttons, inputs, tiles |
| r-lg | 14 | cards, table container |
| r-xl | 16–18 | hero / feature panels, graph well |
| r-full | 999 | status pills, circular nodes |

## Spacing
Base rhythm **4px**. Common: card padding `15–20`, section gap `16`, screen padding `22px 24px`, grid/list gap `12–14`, inline gaps `8–12`.

## Elevation / effects
- Card: 1px border, no shadow (flat on dark).
- Feature/hero card: 1px violet border + soft outer shadow `0 14px 34px rgba(40,20,60,0.28)`.
- Glow (YOU node, active play button): `dropshadow(gaussian, rgba(198,79,240,0.55), 30, 0.2, 0, 0)`.
- Live/seeding dot: green `#46d39a` with a slow opacity pulse (1→0.4→1, ~2s).

## Layout frame
- Window: title bar **46px** tall, then a row of [ sidebar **230px** | content (fills) ].
- Player right rail: **322px**. Swarm/DHT use the full content width.
- Designed at desktop width ≈ **1280–1440px**.
