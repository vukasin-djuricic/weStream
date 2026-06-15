# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status & roadmap (READ FIRST)

**Where we are (2026-06):** the project has migrated from a legacy **Chord** DHT (the original course framework) to a **Kademlia**-based P2P engine, the foundation of the aspirational "weStream" (BitTorrent download + Popcorn-Time streaming). **The runnable app now runs Kademlia** — launching `ServentMain` / `MultipleServentStarter` boots a `KademliaService` over UDP (Phase 3 done). The Chord classes still compile but are dormant (never started), kept as reference. A **BitTorrent-style transfer layer** (`core.transfer`) now rides on top: nodes `share`/`download` files, discovering seeds via the DHT (Phase 4 done). **Phase 5 (player UI) is functionally complete and PIVOTED to Electron + React** — a single Electron/React window per node (`react/westream-react/`) over a pure-JDK **local HTTP API** (`src/app/api/`), with all screens wired live including `<video>` watch-while-download streaming; the earlier JavaFX attempt (`ui/`) is now a dormant reference. First thing in a fresh session: run `./check.sh` (54 Kademlia + 46 transfer + **58 API** checks) to confirm green.

**Done:**
- Phase 1 — routing primitives: `NodeId` (160-bit, XOR), `Contact`, `KBucket` (k=20, LRS), `RoutingTable`.
- Phase 2 — `UdpTransport` + RPC (PING/FIND_NODE/STORE/FIND_VALUE), `CompletableFuture` correlation + scheduler timeouts, iterative `nodeLookup` (α=3), `bootstrap`, `storeValue`/`findValue`.
- Hardening (codec bounds, wire-source routing, STORE cap) + regression suite `./check.sh`.
- Phase 3 — Kademlia wired into the runnable app. `ServentMain` boots `UdpTransport` + `KademliaService` + `CLIParser`; non-seed nodes `bootstrap(seed)` on a dedicated `kad-bootstrap` thread (node 0 = seed). `AppConfig` holds `kademliaService`/`transport`/`myPort`/`isSeedNode`/`SEED_HOST`/`SEED_PORT`; slim `readConfig` (only `servent_count` + `serventN.port`, no `chord_size`/`bs.port`, no `ServentInfo`). CLI: `dht_put`/`dht_get` → `storeValue`/`findValue` (string key → SHA-1 `NodeId`, value = UTF-8 bytes), `successor_info` replaced by `routing_info`, `info` prints `kademliaService.self()`, `stop` closes the transport. `MultipleServentStarter` spawns N `ServentMain` nodes with no bootstrap-server JVM, targeting a new `kademlia/` test dir (mirrors `chord/`). Verified end-to-end: store on node 0, `dht_get` resolves on node 4 via `findValue`. Chord kept dormant (Option A).

- Phase 4 — **transfer/streaming layer** (`core.transfer`, pure JDK), built as four green increments. Piece model: `TorrentMetadata` (infohash = SHA-1 over pieceSize‖totalLength‖pieceCount‖piece hashes), `PieceHasher`, bit-packed thread-safe `Bitfield`, `PieceStore` (the SHA-1 integrity gate; `verifyAndMarkExisting` for seeds). Wire: `TransferMessageType`/`TransferMessage`/`TransferCodec` — hand-rolled length-prefixed binary **with explicit 4-byte framing** (TCP is a stream), bounds-checked like `MessageCodec`; `TcpPeerServer` (daemon accept loop). Transfer: persistent `PeerConnection` (1 socket/peer, reader thread, framed sends, pipelined REQUESTs), `PiecePicker` interface + `SlidingWindowPicker` (blueprint #6, settable playhead = the Phase-5 player seam) + `RarestFirstPicker`, `DownloadSession` (event-driven pipeline, `CountDownLatch` completion, no busy-wait) + `SeedSession`. Integration: `TransferService` on `AppConfig` (each `share` = its own ephemeral TCP server; DHT value = seed endpoint + small metadata, keyed by infohash; single-seed for now); CLI `share <path>` / `download <infohash-hex> <outpath>`; started in `ServentMain`, closed by `stop`. Verified end-to-end in `TransferCheck` (real UDP+TCP: share→findValue→download→byte-identical) and a scripted `kademlia/` scenario (node 0 shares `sample.bin`, node 4 downloads it).

**Phase 5 — media/player UI: PIVOTED to Electron + React (2026-06-15), and now functionally complete.** The player/UI is a **single Electron + React (Vite) window per node** (a "Mini Stremio") in `react/westream-react/`, talking to the Java engine over a **local HTTP API**. The earlier **JavaFX** attempt (`ui/`, Gradle, Increment 1) is kept in the tree but **DORMANT — reference only, like Chord** (never started, not the live UI). The zero-dependency rule on `core.kademlia`/`core.transfer` is unchanged; the UI layer may use any modern lib.

*Why the pivot:* the committed design was authored as inline-styled HTML/flex/grid (a near-direct React port), and embedding video is trivial with a `<video>` tag over our HTTP streamer vs. wrestling vlcj/libVLC into a JavaFX node.

**Java HTTP API — DONE (`src/app/api/`, pure JDK, OUTSIDE `core.*`).** `ApiServer` wraps the JDK's `com.sun.net.httpserver.HttpServer` (so `./check.sh`'s plain `javac src test` compiles it with no extra classpath — the engine's import rule stays intact); hand-rolled `Json` (writer + a flat-object reader for POST bodies). Owned/started by `NodeRuntime`; **API port = `11470 + (udpPort - 1100)`** (node 0 → 11470, node 1 → 11570). Cached thread pool (a long `/stream` response must not starve `/api/progress` polls), permissive **CORS + OPTIONS** at the single `requireMethod` chokepoint (the renderer is a different origin: Vite dev server / `file://` / the `<video>` tag). Endpoints, all covered by `test/app/api/ApiCheck` (real UDP+TCP+HTTP; `./check.sh` runs **58 API checks**) and verified live cross-process:
  - `GET /api/status` (nodeId/host/udp+api port/uptime/peerCount), `GET /api/routing` (bucketSizes[160] + contacts).
  - `POST /api/dht/put` `{key,value}` / `GET /api/dht/get?key=` → `storeValue`/`findValue` with the **same SHA-1 key derivation as the CLI** (interoperable).
  - `POST /api/share` `{path}` → metadata+infohash; `POST /api/download` `{infohash[,out]}` → non-blocking `TransferService.startDownload` (uses `SlidingWindowPicker`, tracked in an `active` map; 404 if not announced); `GET /api/progress?infohash=` → live `{have,inFlight,total,peers,pieceStates[]}`.
  - `GET /stream/<40-hex>` → HTTP **Range/206** video (Content-Length from metadata, body filled as pieces arrive = **watch-while-download** via `PieceStore.awaitPiece` park/notify; a Range seek moves the `SlidingWindowPicker` playhead). New engine seam for this: `NodeId.fromHex`, `DownloadSession.store()/setPlayhead()`, `TransferService.session()/seedStore()/startDownload()`.

**React UI — DONE (all screens wired live).** `react/westream-react/` (`.gitignore` excludes `node_modules`/`dist`). `src/api.js` = fetch client (base URL from `window.westream` → `?api=<port>` dev override → seed 11470). `src/hooks.js` = `usePoll` (setTimeout chain, cleans up on unmount) + `useStatus`/`useRouting`@1s + `useProgress`@500ms + transforms (`bucketsFromSizes`, `buildSwarmFrom`, `stripFromProgress`, `formatId`/`shortId`/`formatUptime`). `src/WeStreamApp.jsx` (one component file, programmatic styles via a `css()` helper — NOT the JavaFX code): **DHT inspector** (identity/uptime/k-bucket bars/counts), **Swarm** (peers + count from the routing table; per-peer cosmetics hashed from id, speeds/latency honestly "—"), **Add-Stream** (controlled infohash + path inputs → `startDownload`/`share`), and **Player** (real `<video src=streamUrl(infohash)>` + live sliding-window strip from `pieceStates`) are all live, with mock fallback until the first fetch. **Still mock:** the Library shares/downloads grids (needs a transfers-list endpoint) and the DHT-screen RPC log (needs an engine RPC event stream).

**Electron — DONE.** `electron/main.cjs` spawns the headless node (`java -cp out/production/weStream app.ServentMain kademlia/servent_list.properties <id>`, id via `WS_NODE_ID`, default 0) as a child from the repo root, health-checks `/api/status`, keeps the child stdin pipe open (the CLI `Scanner` parks; an EOF would crash that thread), and kills the JVM on window-all-closed/before-quit/will-quit. `electron/preload.cjs` exposes `window.westream {apiPort,nodeId,apiBase}` (via `additionalArguments`) so `api.js` targets this window's JVM with no `?api=`. contextIsolation stays on. **One Electron window = one peer node.**

**How to run:** compile the engine first (`javac -d out/production/weStream $(find src -name '*.java')`), then `cd react/westream-react && npm run app:dev` (Electron spawns the JVM itself) — or browser-only: start a node manually + `npm run dev` + open `http://localhost:5173/?api=11470`. A second peer: `WS_NODE_ID=1 npm run app:dev` (node 1, API 11570). **⚠️ video pixels never eyeballed** — no display in any session; the whole data path (api.js client, `/api/progress` pieceStates, `/stream` Range/206) is verified live, but a human must run it on a real display to watch actual playback.

**Optional polish remaining:** `/api/transfers` list endpoint → live Library grids; an engine RPC-event stream → live DHT RPC log; `electron-builder` (or `jpackage`/`jlink`) native bundle with a bundled JRE for the spawned Java. Also consider folding in the deferred engine/transfer gaps below when robustness matters.

**Key decisions (do not re-litigate — see memory + Dependency policy):** zero-dependency applies ONLY to `core.kademlia`/`core.transfer` (pure JDK, import-level, enforced by `./check.sh`); UI/media/packaging may use modern libs. **Player = Electron + React + HTML5 `<video>` over the local HTTP API** (JavaFX + vlcj is the abandoned earlier direction, now dormant reference). Connectivity LAN-first, NAT traversal deferred behind the `Transport` interface. Commits use no `Co-Authored-By` trailer.

**Known engine gaps (deferred):** no RPC retry on UDP loss; serial awaits in lookup/store/findValue (a dead node costs a full timeout); `findValue` doesn't iterate through NODES; no STORE TTL/republish. **Unbounded local `store`:** the `MAX_STORE_KEYS` cap is enforced ONLY on the inbound STORE handler — the originator's local `store.put` in `storeValue` (and any future cache-on-read in `findValue`) bypass it, so a heavy writer/reader grows the key→value map without bound. Fix = route every write through a bounded store (LRU cap + TTL) and add republish; only then add `findValue` cache-on-read. Note: the DHT `store` is for SMALL values (infohash → peer/location metadata) — actual piece/media bytes belong in the Phase-4 transfer layer's own bounded cache, never in the DHT map. (Marked inline as `TODO(phase4-hardening)` in `KademliaService.storeValue`/`findValue`.)

**Known transfer gaps (deferred, Phase 4 skeleton):** single-seed only — the DHT holds one value per infohash (latest announce wins), so no multi-peer swarming yet (the engine's one-value-per-key `store` would need a peer-list/merge announce); no choking/unchoking or upload fairness (only matters with many peers); no endgame mode; `download` re-pulls the whole file (no resume/partial); the full small metadata rides in the DHT value, which is fine for modest files but would need a metadata-vs-peerlist split for very large ones; downloaders serve pieces they hold but don't re-announce themselves as seeds. The `SlidingWindowPicker` is built and unit-tested but the app's `download` uses `RarestFirstPicker`; wiring the window to a real playhead is the Phase-5 player's job.

## What this repo actually is

The repo is a **single root** (`src/`, `test/`, `chord/`, `kademlia/`). It contains **two layers side by side**: the new **Kademlia engine** (`core.kademlia` — pure JDK), which now **drives the runnable app** (`ServentMain` boots it over UDP), and the legacy **Chord DHT** simulation (`core.chord`, `servent.*`, `app.BootstrapServer`, `app.ServentInitializer` — the original RAF KIDS course framework), which still compiles but is **dormant** (no longer started by any entry point), kept as reference.

The (dormant) Chord side simulated a distributed system by launching a bootstrap server and N "servent" nodes as **separate JVM processes on localhost**, each on its own port, over Java-serialized TCP messages. The live Kademlia app reuses the same multi-process-on-localhost harness shape (`MultipleServentStarter`) but over UDP with no bootstrap server (node 0 is the seed). The `core.transfer` layer adds a parallel TCP channel for bulk piece transfer on top. `Readme.tex` is the **aspirational** weStream spec (Kademlia + bitfield gossip + chunk streaming); the DHT and transfer layers now exist (Phases 1–4); the media **player** (Phase 5) is functionally complete as an **Electron + React** window (`react/westream-react/`) over a pure-JDK local HTTP API (`src/app/api/`), with real `<video>` watch-while-download streaming. The earlier **JavaFX** shell (`ui/`, Increment 1) is kept as a dormant reference (never started), same as Chord.

The root `digest.txt` (~140KB) is a **generated gitingest dump of the whole repo** — gitignored, not source. Don't read it (use the real files), don't edit it, don't commit it.

## Build & run

This started as a plain **IntelliJ IDEA** project (`weStream.iml`). The engine + app + HTTP API + headless CLI all compile and run as **plain JDK** (IntelliJ build or the `javac` line below); `./check.sh` does not touch any build tool. The live **player UI is Electron + React** in `react/westream-react/` (its own `npm`/Vite build — see "Run" below). A **dormant Gradle build** (`build.gradle` + committed `./gradlew`) remains ONLY for the abandoned JavaFX `ui/` layer; it is no longer the live UI and is not needed to run the app. The repo is a **single root**: engine + app + CLI + HTTP API in `src/` (packages `app` = process startup/bootstrap + `NodeRuntime` + `app.api` HTTP server, `core` = identity/routing/transfer, `cli`, `servent` = dormant Chord messaging), the **dormant JavaFX UI in `ui/`** (package `ui`), the **live Electron/React UI in `react/westream-react/`**, bundled JavaFX resources in `resources/`, compiled (plain-javac) classes in `out/production/weStream/`. (Historically the code lived in a nested `KiDS-vezbe9/` subfolder — that nesting has been flattened; references to `KiDS-vezbe9` anywhere are stale.)

Three entry points (all have `main`), now Kademlia:
- **The live UI: Electron + React** (`react/westream-react/`) — `cd react/westream-react && npm run app:dev` opens one node's window; Electron auto-spawns the Java engine (`app.ServentMain`, node id via `WS_NODE_ID`, default 0) and talks to it over the local HTTP API. A second peer: `WS_NODE_ID=1 npm run app:dev`. (Browser-only dev without Electron: start a node manually, `npm run dev`, open `http://localhost:5173/?api=11470`.) **Compile the engine first** (`javac` line below).
- `ui.WeStreamApp` (Gradle: `./gradlew run --args="<port> [seed]"`) — the **dormant JavaFX window** (reference only; superseded by the Electron/React UI). Still compiles/runs as a sibling front-end over the same `NodeRuntime`, but not the live path.
- `app.MultipleServentStarter` — the normal way to run. Spawns `SERVENT_COUNT` `app.ServentMain` processes (node 0 = seed, started first; no bootstrap-server JVM), redirecting each node's stdin/stdout/stderr to `kademlia/input/serventN_in.txt`, `kademlia/output/serventN_out.txt`, `kademlia/error/serventN_err.txt`. Type `stop` in its console to kill all processes. It spawns the child JVMs with classpath `out/production/weStream`, so **compile first** (IntelliJ build, or the `javac` line below).
- `app.ServentMain <servent_list.properties> <serventId>` — run a single node manually (id 0 = seed; others bootstrap to `servent0.port`). **Use the live Kademlia config `kademlia/servent_list.properties`** (the canonical one `MultipleServentStarter` runs), e.g. `java -cp out/production/weStream app.ServentMain kademlia/servent_list.properties 0` for the seed. (`chord/servent_list.properties` also boots — `readConfig` only reads `servent_count` + `serventN.port` — but it is the legacy Chord-era file and its node ports differ from the `kademlia/` one, so prefer `kademlia/`.) CLI commands: `info`, `pause <ms>`, `routing_info`, `dht_put <key> <value>`, `dht_get <key>`, `stop`.

Compile manually from the repo root:
```
javac -d out/production/weStream $(find src -name '*.java')
```
(The child-process classpath in `MultipleServentStarter` was previously hardcoded with Windows backslashes — `out\\production\\KiDS-vezbe9` — which broke on macOS/Linux. It now uses forward slashes and the `weStream` module name.)

**Validation:** run `./check.sh` after every change — it compiles `src` + `test` and runs `core.kademlia.KademliaCheck`, a zero-dependency regression suite (identity/routing/codec unit checks + an end-to-end group over real UDP sockets). It exits non-zero on any failure, so the same command works in a git hook or CI. Test code lives in `test/` (kept out of `src/` so it never ships in the app) but in package `core.kademlia` to reach package-internal types. When you fix a bug, add a check that would have caught it.

For the **app** (Kademlia) side there is no automated suite: "testing" means running the simulation (`java -cp out/production/weStream app.MultipleServentStarter`) and reading the per-node output files in `kademlia/output/`, with scripted scenarios fed through `kademlia/input/serventN_in.txt` (one CLI command per line). The committed scenario stores `hello=world` on node 0 and resolves `dht_get hello` on node 4. The dormant **Chord** side, if ever revived, used the parallel `chord/` dir the same way.

## Dependency policy

**The zero-dependency rule applies ONLY to the Kademlia / P2P engine — not the whole project.** This is a deliberate, user-set boundary:

- **`core.kademlia` (and the hand-written DHT/P2P engine logic: routing, k-buckets, lookup, piece selection, sliding-window picker) MUST stay pure JDK.** No third-party libraries — this is where the learning is, and it must be implemented by hand. The JDK covers it: `java.util.concurrent` (`BlockingQueue`/`Semaphore`/`CountDownLatch`/`CompletableFuture`/`ExecutorService`), `java.security.MessageDigest` (SHA-1 160-bit ids), `java.net` (UDP `DatagramSocket` for RPC, TCP for data transfer). The constraint is **import-level**: even once Gradle exists, nothing under `core.kademlia` may import outside the JDK. Blueprint rules #3–#6 mandate hand-rolled implementations here.
- **Everything else (UI, media/player, packaging, frontend, NAT traversal plumbing) MAY use modern libraries.** The goal there is simply: make it work, be as modern as Java allows, and ship as a single-window native app. Hand-rolling is not a virtue outside the engine.

**Player / UI (decided direction — DONE):** **Electron + React (Vite) + HTML5 `<video>`** talking to the Java engine over a **pure-JDK local HTTP API** (`src/app/api/`, `com.sun.net.httpserver`), one Electron window per node (Electron auto-spawns the JVM). Video streams from the engine's `GET /stream/<infohash>` (HTTP Range/206, watch-while-download). Packaging (later) via `electron-builder` (or `jpackage`/`jlink`) with a bundled JRE for the spawned Java. *(Abandoned earlier direction, now dormant `ui/`: JavaFX + vlcj/libVLC packaged with jpackage — dropped because the React port of the design was near-direct and `<video>` over the HTTP streamer is far simpler than vlcj-in-JavaFX.)*

**Build tooling:** the live UI builds with **`npm`/Vite + Electron** in `react/westream-react/` (deps: react, vite, electron, etc.). A **dormant Gradle build** (`./gradlew`, 8.10.2, Temurin 21 + JavaFX 21.0.5) remains only for the abandoned JavaFX `ui/` — not needed for the live app. Neither build tool relaxes the `core.kademlia`/`core.transfer` import rule (import-level, independently enforced by `./check.sh`'s plain `javac src test`, no third-party libs on the classpath). The new HTTP API lives in `src/app/api/` and uses only the JDK (`com.sun.net.httpserver`), so it compiles under that same plain javac. Gradle's non-default `sourceSets` (`srcDirs = ['src','ui']`) keep the flat repo + IntelliJ working; JavaFX code lives in `ui/` (never `src/`) so plain javac never sees it.

**Logging:** implement a zero-dependency async logger for the engine (`BlockingQueue` + daemon consumer thread, formatting/I-O off the network threads — see Conventions). The app/UI layer may use a real logging framework if convenient.

**Other notes:** Don't trust native Java serialization (`ObjectInputStream.readObject`) on bytes from arbitrary peers — use an explicit length-prefixed binary format for the engine wire protocol (hand-rolled, zero-dep). JUnit is fine for tests (dev/test scope).

## Configuration

**Live (Kademlia) config — `kademlia/servent_list.properties`** is what the runnable app uses (`MultipleServentStarter` and the manual `ServentMain` example above). The Kademlia `readConfig` is slim — it reads ONLY:
```
servent_count=5      # number of nodes
servent0.port=1100   # each node's UDP listener port; MUST be in range 1000-2000 (node 0 = seed)
servent1.port=1200
...                   # node 4 = 1500 in the kademlia/ file
```
A node's 160-bit Kademlia id is derived inside the engine from `SHA-1(host:port)` (`NodeId.fromEndpoint`), not from the config. The HTTP API port for each node is `11470 + (udpPort - 1100)` (so node 0 → 11470, node 1 → 11570).

**Legacy (Chord) config — `chord/servent_list.properties`** is the dormant Chord-era file (also carries `chord_size=64` / `bs.port=2000`, which the Kademlia path ignores; note its node 4 port is **1600**, not 1500). It still boots a Kademlia node but is NOT canonical — prefer the `kademlia/` file. For reference, a node's Chord ID was `chordHash(port) = 61 * port % CHORD_SIZE` (`ChordState.chordHash`); colliding ports share an ID (see the commented collision-test line).

## Architecture

### Node lifecycle / join protocol
1. `ServentMain` starts three threads: `SimpleServentListener` (socket listener), `CLIParser` (reads commands from stdin), `ServentInitializer` (joins the ring).
2. `ServentInitializer` sends `"Hail"` + own port to the bootstrap over a raw socket. Bootstrap replies with a **random active node's port**, or `-1` if this is the first node.
3. Non-first nodes send a `NEW_NODE` message toward that random node. It is routed around the ring (`getNextNodeForKey`) until it reaches the node that should be the newcomer's successor, which replies with `WELCOME` (handing over the key range and value subset the newcomer now owns). On collision, a `SORRY` is returned instead.
4. On `WELCOME`, the newcomer calls `ChordState.init`, confirms to the bootstrap with `"New"` + port, then floods an `UPDATE` so every node rebuilds its finger table.

The bootstrap (`BootstrapServer`) handles joins **sequentially** (single SPOF for bootstrapping only) and is not part of steady-state routing — the comments note a real system would use an always-on backbone instead.

### Chord routing — `core.chord.ChordState`
The brain of the system. Holds:
- `successorTable` — the finger table, length `log2(CHORD_SIZE)`. Entry `i` targets ID `(myId + 2^(i+1)) % CHORD_SIZE`, built by `updateSuccessorTable()` from the sorted `allNodeInfo` list.
- `predecessorInfo`, `valueMap` (the local DHT key→value store).
- Key ownership: `isKeyMine(key)` (handles ring overflow at the wrap-around point). `getNextNodeForKey(key)` is the core hop logic — pick the closest finger that does not overshoot the key, with explicit overflow handling.
- `putValue`/`getValue` store/fetch locally if the key is ours, otherwise forward via `PUT` / `ASK_GET` messages.

When editing routing math, the overflow cases (where the ring wraps past CHORD_SIZE) are the subtle part and the easiest place to introduce bugs.

### Kademlia engine — `core.kademlia.*` (drives the runnable app)
This is now what the app runs: `ServentMain` boots a `UdpTransport` + `KademliaService` (see "app entry / boot path" below), the CLI maps `dht_put`/`dht_get`/`routing_info` onto it, and the engine has fully **replaced** Chord as the live path (Chord is dormant). Strictly **pure JDK** (no imports outside `java.*`).

Identity & routing (phase 1):
- `NodeId` — 160-bit id (`BigInteger`). `fromEndpoint(host, port)` / `fromPort(port)` = SHA-1; `distance` = XOR; `bucketIndex` = highest-set-bit of the distance (−1 for self); `toBytes()`/`fromValueBytes()` are the 20-byte wire form.
- `Contact` — peer reference (`NodeId` from `host:port` + `lastSeen`); equality by `NodeId`.
- `KBucket` — capacity k (=20), least-recently-seen at head; `update()` returns the LRS contact as an **eviction candidate** when full (PING it; `replaceLeastRecentlySeen` only if dead). Fully `synchronized` (rule #3).
- `RoutingTable` — 160 buckets; `findClosest(target, count)` powers FIND_NODE/FIND_VALUE.

Transport & RPC (phase 2):
- `Transport` (interface) + `UdpTransport` — one `DatagramSocket` per node for send+receive. **Kademlia talks only to `Transport`**, so a NAT-traversing transport (UPnP/ICE-WebRTC/relay) can be added later without touching the engine. Bulk piece transfer will use TCP separately.
- `Message` / `MessageType` / `MessageCodec` — RPC pairs PING↔PONG, FIND_NODE↔NODES, STORE↔STORED, FIND_VALUE↔(VALUE|NODES). Hand-rolled **length-prefixed binary** codec (NOT Java serialization — that's an RCE vector on peer input). `txId` correlates responses.
- `KademliaService` — the live node. Inbound RPCs handled on the transport receive thread (non-blocking: table lookup + reply); responses complete a pending `CompletableFuture`; timeouts enforced by a scheduler (no busy-wait, rule #4). `bootstrap(peer)`, iterative `nodeLookup(target)` (α=3 parallel rounds), `ping`, `storeValue`, `findValue`. **Blocking ops (`nodeLookup`/`ping`/`findValue`) must NOT be called from the receive thread** — they wait on futures completed by that thread, so doing so deadlocks. Hardened against hostile input: routing learns the **wire** source address (not the self-reported, spoofable endpoint), the codec rejects out-of-range type ordinals and over-long length fields (no OOM on bad packets), and `STORE` is capped (`MAX_STORE_KEYS`) against flooding. Covered by `./check.sh` (codec-fuzz, k-bucket eviction, an in-memory multi-hop chain lookup, a drop-transport timeout, and a real-UDP end-to-end group).
  - **Known gaps (not yet done):** no RPC retry on UDP loss; `nodeLookup`/`storeValue`/`findValue` await serially (a dead node costs a full timeout); `findValue` relies on the initial lookup rather than iterating through returned NODES; no STORE TTL/republish. See the agent-review notes — fold these in when robustness matters.

Built since: discovery/join now runs on Kademlia (Phase 3), the piece/transfer layer + streaming window exist (`core.transfer`, Phase 4), and the media player is the Electron/React UI over the `src/app/api/` HTTP API (Phase 5). Still not built: NAT traversal (deferred behind the `Transport` interface).

### Messaging — `servent.message.*` + `servent.message.util.*`
- Messages are immutable, `Serializable`. `BasicMessage` is the base; equality/hash use `(messageId, senderPort)`. `MessageType` enum drives dispatch.
- **Send:** `MessageUtil.sendMessage(msg)` spawns a `DelayedMessageSender` thread that sleeps a **random 500–1500 ms** before opening a socket and writing the object. This is intentional jitter; ordering between messages is **not** guaranteed (non-FIFO).
- **Receive:** `SimpleServentListener` accepts connections (1s socket timeout to allow clean shutdown), reads one object per socket, and dispatches via a `switch` on `MessageType` to a handler, run on a `newWorkStealingPool()` thread pool. Handlers in `servent.handler.*` are **stateless, one per message type** — prefer keeping them stateless. `NullHandler` is the fallback.

To add a message type: add to `MessageType`, create the `Message` subclass, write a `*Handler`, and register a `case` in `SimpleServentListener.run()`'s switch. Missing the switch entry means the message silently falls through to `NullHandler`.

### CLI — `cli.CLIParser` + `cli.command.*`
First whitespace-delimited token is the command name; rest is the arg string. **Registered commands** (in `CLIParser` constructor): `info` (prints `kademliaService.self()`), `pause`, `routing_info` (prints `routingTable()` contacts), `dht_get` (`findValue`), `dht_put` (`storeValue`), `stop` (closes the `UdpTransport`). `dht_put <key> <value>` / `dht_get <key>` use **string** keys (SHA-1 → `NodeId`) and UTF-8 byte values. These blocking commands run on the `CLIParser` thread (safe — never on the UDP receive thread). To add a command, implement `CLICommand` and add it to the `commandList`.

## Mandatory coding rules (weStream blueprint)

These are hard constraints from `Readme.tex` for code written toward the Kademlia/streaming target. They are non-negotiable when implementing new functionality; the existing Chord code is the starting point being evolved toward them.

1. **Package separation is strict.** Network packet schemas → `message` package. Socket read/write → `servent.message.util`. Business logic → `handler` classes implementing `Runnable`. Do not leak one concern into another package.
2. **Messages are strictly immutable.** Never mutate a `Message` to forward it. Add `makeMeASender` / `changeReceiver`-style methods that return **new** instances. (The current `BasicMessage` is immutable but lacks these copy-modifiers — add them rather than introducing setters.)
3. **`KademliaState` must be thread-safe.** It is read/written by multiple handler-pool threads concurrently. All k-bucket / routing-table operations use `ConcurrentHashMap`, `Atomic*` primitives, and explicit locks where needed. (Note: the current `ChordState` is **not** synchronized — when migrating to Kademlia, thread-safety becomes a requirement, not optional.)
4. **No busy-waiting.** Park waiting threads with `BlockingQueue`, `CountDownLatch`, `Semaphore`, etc. Never spin on an empty `while` loop burning CPU.
5. **XOR distance metric.** Distance is `d(x, y) = x ^ y`. Assign a node to the k-bucket indexed by the position of the highest set bit of its XOR distance from this node.
6. **Sliding-window piece picker.** The streaming picker keeps a moving window array of size `W`. Chunk requests for indexes nearer the playhead go to a high-priority queue ahead of farther ones.

## Conventions

- **Logging (target):** migrate off `System.out.println` and the `AppConfig.timestampedStandardPrint` / `timestampedErrorPrint` helpers to **SLF4J + Logback**. Synchronous `System.out` writes plus eager string concatenation block network/handler threads on I/O; use a parameterized logger (`log.debug("Got message {}", msg)` — no `+` concatenation, so the string is only built when the level is enabled) behind a Logback **async appender** so logging never stalls the threads doing routing/streaming work. Per-node file separation (today's `chord/output` / `chord/error`) should be reproduced via Logback file appenders keyed on servent id.
- `MessageUtil.MESSAGE_UTIL_PRINTING` toggles logging of every message send/receive — useful for debugging routing, noisy otherwise.
- Global mutable state lives on `AppConfig` (`myServentInfo`, `chordState`, ports). It's a simulation, so this static-singleton style is the existing idiom.
