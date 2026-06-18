# weStream — YouTube demo script

A tight **2–3 minute** walkthrough that shows the three things that make weStream
worth watching: **watch-while-download streaming**, a **real multi-seed swarm**, and
a **hand-written Kademlia DHT** you can see working. Voiceover-friendly; every beat
has what's on screen and a line to say.

---

## Pre-flight (set this up before you hit record)

- **Throttle the seed.** On a single machine, localhost transfers finish instantly —
  nothing to see. Launch node 0 with `launchers/run-node0-slow.cmd` (caps upload to
  3500 KB/s) so the sliding window and progress bars are *visible* on camera.
- **Use a real movie** (a recognizable `.mp4`) so playback reads as "a real video",
  not a test pattern.
- **Lay out the windows.** The min window is 600px, so 2–3 node windows tile side by
  side on a 1080p display. Plan: node 0 (seed) left, node 1 (leecher) right; bring in
  node 2 (second seed) for the swarm beat.
- **Record 1080p/60**, system audio off (or low), cursor highlight on. Do one silent
  run first to rehearse the clicks, then record + voice over.
- **Have the infohash ready to paste** (copy it from node 0's "SHARED · SEEDING" card)
  so the paste on node 1 is instant — no fumbling.

---

## The script

### 0:00 — Hook (≈8s)
**Screen:** weStream title bar, then a video already playing with the sliding-window
strip filling underneath.
**Say:** *"This is a BitTorrent client and a streaming player — and I wrote the whole
peer-to-peer engine from scratch. No libraries. Let me show you."*

### 0:08 — Share a file (≈20s)
**Screen:** Node 0 → **Add Stream** → pick a movie file → it hashes, the card flips to
**SHARED · SEEDING** with the infohash. Click **Copy infohash**.
**Say:** *"I drop in a file. weStream splits it into pieces, SHA-1 hashes each one into
a single fingerprint — the infohash — and announces itself in a distributed hash
table. That infohash is the only thing I need to share."*

### 0:28 — Discover from another peer (≈20s)
**Screen:** Node 1 → **Add Stream** → paste the infohash. The peek readout resolves:
**the file's name** (not the hash) **· N peers in swarm · size · pieces**.
**Say:** *"On a second peer I paste just that infohash. It does an iterative
FIND_VALUE across the DHT and comes back with the file's real name and how many peers
are holding it — before I've downloaded a single byte."*

### 0:48 — Watch while it downloads (the money shot) (≈30s)
**Screen:** Click **Watch now**. Video starts playing almost immediately. Point at the
**sliding-window strip**: cells go missing → in-flight (blue) → have (purple) right at
the playhead. Then **seek to the middle** — the window marker jumps there and those
pieces light up and fill.
**Say:** *"I hit watch — and it plays while it's still downloading. This strip is the
real piece map around the playhead: blue is in-flight, purple is downloaded. And when
I seek ahead… the engine moves its priority window to exactly where I jumped, and
fetches those pieces first. That's streaming over BitTorrent."*

### 1:18 — A real swarm (≈30s)
**Screen:** Start node 2 as a **second seed** of the same file. On the leecher, show
the **SEEDERS / LEECHERS** tiles tick to 2 seeders, and the **DOWNLOAD/UPLOAD**
throughput meters. Flip to both seed windows — both show **upload** activity.
**Say:** *"Now a second peer seeds the same file. The downloader connects to both at
once and pulls different pieces from each — a genuine swarm. Watch the upload meters:
both seeders are feeding it in parallel, not one doing all the work."*

### 1:48 — The glass box: the DHT itself (≈25s)
**Screen:** Node 0 → **DHT Inspector**. Pan over the **node ID**, the **k-bucket
bars**, the **live RPC feed** (PING / FIND_NODE / STORE / FIND_VALUE scrolling), and
the **stored keys**.
**Say:** *"And none of this is faked. Here's the engine's glass box — the node's
160-bit ID, its k-buckets, and every Kademlia RPC happening live over UDP. XOR
distance, iterative lookups, the routing table — all hand-written, zero dependencies."*

### 2:13 — Close / CTA (≈12s)
**Screen:** Back to the player, video running; cut to the GitHub repo / Releases page.
**Say:** *"Pure-JDK Kademlia engine, an Electron and React UI, and a one-click Windows
installer. It's all on GitHub — link below. Thanks for watching."*

---

## Production tips

- **Cut the dead air.** Hashing a big file and the first buffering take a second or
  two — trim those in the edit so the pace stays tight.
- **Zoom in** on the sliding-window strip and the SEEDERS/LEECHERS tiles during their
  beats; they're small but they're the proof.
- **Lower thirds** for the jargon as it appears: "infohash = SHA-1 of the pieces",
  "k-bucket", "watch-while-download (HTTP Range / 206)".
- **One honest caveat to keep it credible:** this demo is several peers on one machine
  over loopback. Don't claim cross-internet P2P — that's the deferred bootstrap/NAT
  work. Showing a real swarm on one box is plenty.
- **Length:** aim for under 3 minutes. If you need a longer cut, expand the DHT
  Inspector beat (explain a single FIND_VALUE hop-by-hop) rather than padding the rest.

## Optional B-roll / longer-form beats

- The `check.sh` suite running green (engine credibility for a technical audience).
- A quick `Add Stream` of a second, different file to show the Library filling up.
- The Swarm map screen (radial peer view) while pieces flow.
