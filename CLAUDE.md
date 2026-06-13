# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status & roadmap (READ FIRST)

**Where we are (2026-06):** the project has migrated from a legacy **Chord** DHT (the original course framework) to a **Kademlia**-based P2P engine, the foundation of the aspirational "weStream" (BitTorrent download + Popcorn-Time streaming). **The runnable app now runs Kademlia** — launching `ServentMain` / `MultipleServentStarter` boots a `KademliaService` over UDP (Phase 3 done). The Chord classes still compile but are dormant (never started), kept as reference. A **BitTorrent-style transfer layer** (`core.transfer`) now rides on top: nodes `share`/`download` files, discovering seeds via the DHT (Phase 4 done). **Phase 5 (JavaFX player UI) is underway — Increment 1 (build system + app shell + theme + navigable screens, DHT inspector wired live) is done.** First thing to do in a fresh session: run `./check.sh` (54 Kademlia + 46 transfer checks) to confirm green.

**Done:**
- Phase 1 — routing primitives: `NodeId` (160-bit, XOR), `Contact`, `KBucket` (k=20, LRS), `RoutingTable`.
- Phase 2 — `UdpTransport` + RPC (PING/FIND_NODE/STORE/FIND_VALUE), `CompletableFuture` correlation + scheduler timeouts, iterative `nodeLookup` (α=3), `bootstrap`, `storeValue`/`findValue`.
- Hardening (codec bounds, wire-source routing, STORE cap) + regression suite `./check.sh`.
- Phase 3 — Kademlia wired into the runnable app. `ServentMain` boots `UdpTransport` + `KademliaService` + `CLIParser`; non-seed nodes `bootstrap(seed)` on a dedicated `kad-bootstrap` thread (node 0 = seed). `AppConfig` holds `kademliaService`/`transport`/`myPort`/`isSeedNode`/`SEED_HOST`/`SEED_PORT`; slim `readConfig` (only `servent_count` + `serventN.port`, no `chord_size`/`bs.port`, no `ServentInfo`). CLI: `dht_put`/`dht_get` → `storeValue`/`findValue` (string key → SHA-1 `NodeId`, value = UTF-8 bytes), `successor_info` replaced by `routing_info`, `info` prints `kademliaService.self()`, `stop` closes the transport. `MultipleServentStarter` spawns N `ServentMain` nodes with no bootstrap-server JVM, targeting a new `kademlia/` test dir (mirrors `chord/`). Verified end-to-end: store on node 0, `dht_get` resolves on node 4 via `findValue`. Chord kept dormant (Option A).

- Phase 4 — **transfer/streaming layer** (`core.transfer`, pure JDK), built as four green increments. Piece model: `TorrentMetadata` (infohash = SHA-1 over pieceSize‖totalLength‖pieceCount‖piece hashes), `PieceHasher`, bit-packed thread-safe `Bitfield`, `PieceStore` (the SHA-1 integrity gate; `verifyAndMarkExisting` for seeds). Wire: `TransferMessageType`/`TransferMessage`/`TransferCodec` — hand-rolled length-prefixed binary **with explicit 4-byte framing** (TCP is a stream), bounds-checked like `MessageCodec`; `TcpPeerServer` (daemon accept loop). Transfer: persistent `PeerConnection` (1 socket/peer, reader thread, framed sends, pipelined REQUESTs), `PiecePicker` interface + `SlidingWindowPicker` (blueprint #6, settable playhead = the Phase-5 player seam) + `RarestFirstPicker`, `DownloadSession` (event-driven pipeline, `CountDownLatch` completion, no busy-wait) + `SeedSession`. Integration: `TransferService` on `AppConfig` (each `share` = its own ephemeral TCP server; DHT value = seed endpoint + small metadata, keyed by infohash; single-seed for now); CLI `share <path>` / `download <infohash-hex> <outpath>`; started in `ServentMain`, closed by `stop`. Verified end-to-end in `TransferCheck` (real UDP+TCP: share→findValue→download→byte-identical) and a scripted `kademlia/` scenario (node 0 shares `sample.bin`, node 4 downloads it).

**Phase 5 — media/player UI (IN PROGRESS).** Decided direction (unchanged): **one JavaFX window per node**, Gradle as build tooling only (does **NOT** relax the pure-JDK rule for `core.kademlia`/`core.transfer`), build incrementally (shell + control screens + sliding-window strip first, then vlcj/libVLC video), recreate the committed `design_handoff_phase5_player/` spec faithfully (Direction A "Midnight Neon"; **Swarm map = the hero**).

- **Increment 1 — DONE.** Build system + app shell + theme + navigable screens; the engine→UI seam proven live on the DHT inspector.
  - *Engine read-seam (pure JDK, in `src/`):* `RoutingTable.bucketSizes()`, `KademliaService.storedKeyCount()`/`storedKeys()`, `DownloadSession.progress()` (`Progress` record + per-piece `MISSING`/`IN_FLIGHT`/`HAVE` byte array — the input the sliding-window strip renders). Each covered by a new `./check.sh` assertion (54+46).
  - *`app.NodeRuntime` (pure JDK):* the headless node boot (`UdpTransport`→`KademliaService`→`TransferService` + `joinNetwork()`), extracted from `ServentMain` so **both** the CLI process and the JavaFX window are front-ends over the same runtime. `ServentMain` is now just config-parse + CLI loop over a `NodeRuntime`; behavior unchanged. **The headless path stays pure-JDK** — `NodeRuntime`/`ServentMain`/`AppConfig` import no JavaFX, so `./check.sh`'s plain `javac src test` (no JavaFX on classpath) still compiles. This is how we "keep headless mode": `ServentMain` IS the headless front-end; the GUI is the sibling `ui.WeStreamApp`, never a flag on `ServentMain`.
  - *Gradle:* committed wrapper (`./gradlew`, Gradle 8.10.2) + `build.gradle`/`settings.gradle`/`gradle.properties`. **Non-default source layout on purpose:** `sourceSets.main.java.srcDirs = ['src','ui']`, resources = `['resources']`, so the existing flat repo + IntelliJ + `check.sh` keep working. UI lives in `ui/` (NOT `src/`) precisely so `check.sh`'s plain javac never tries to compile JavaFX. Pinned to **Temurin 21** (toolchain + `org.gradle.java.home`, since Gradle 8.10 isn't certified on the machine's default Corretto 25) and **JavaFX 21.0.5** (`org.openjfx.javafxplugin`). `test/` is the hand-rolled main-method suite driven by `check.sh`, deliberately NOT wired as a Gradle test set.
  - *UI (`ui/ui/`, package `ui`):* `WeStreamApp` (Application; args `<port> [seed]`, boots `NodeRuntime`, loads the two TTFs, shows an undecorated stage). `AppShell` (title bar + 230px sidebar nav + footer + `StackPane` view-swap). `Ui` (styled-node factory mirroring `DESIGN_TOKENS.md`). `Icons` (hand-drawn line icons via JavaFX shapes — no icon-font dep). Five screens built **programmatically** (not FXML — faster to wire live data; theme.css carries fidelity, can migrate to FXML later): `DhtView` **wired live** (identity, k-bucket bars from `bucketSizes()`, contacts, stored keys, on a 1s FX `Timeline`; RPC log still **mocked** — needs an engine event stream), `SwarmView` (the hero — stat cards, polar graph well with rotating YOU orbit + tinted animated flow lines, peer nodes/table from the **real** routing table; per-peer tint/size/rate derived from contact-id hash, speeds/latency mocked), `PlayerView`/`LibraryView`/`AddStreamView` (faithful **static** layouts; video surface = the vlcj seam, sliding-window strip = the `setPlayhead` seam).
  - *Fidelity pass — DONE.* All 5 screens + shell recreated to match `reference_screens/*.png` (a **UI Designer subagent** audited code-vs-spec; its Top-10 + most MEDIUM findings were applied). Shell now has the title-bar centre cluster (DHT-CONNECTED/peers/speeds), 4-dot brand mark, two-tone wordmark, line-icon window controls, sectioned (BROWSE/ENGINE) sidebar with the correct nav order + active inset-bar (via `.nav-item.active` CSS). **JavaFX-CSS gotchas fixed:** no `Ndeg` gradient angles (use `to <side>`/`from..to..`), no `repeating-linear-gradient` (use `linear-gradient(from..to.., repeat, …)`), and `-fx-letter-spacing` **does not exist** in JavaFX (silently ignored — caption tracking is omitted, not faked). Per DESIGN_TOKENS, **JetBrains Mono** is on all metric values (stat numbers, k-bucket counts, speeds, ids, timestamps).
  - *Responsiveness pass — DONE.* **No global ScrollPane** — `AppShell` content is a `StackPane` that fills the window; each screen is stretched (`maxHeight=MAX` in `register()`) and grows dynamically (`VBox.setVgrow` on the Swarm well / DHT two-column). Only overflow-prone lists carry their own `ScrollPane`: the **Swarm peer table** (max 220) and the **DHT RPC log**. Global padding trimmed to 16; designed to fit a 1080p viewport without a global scrollbar. Deep-purple radial glows + `DropShadow` glows (you-node, play buttons, active peer nodes) per "Midnight Neon".
  - *Run:* `./gradlew run --args="1100 seed"` (seed) / `./gradlew run --args="1200"` (joins via 1100). **Verified each pass:** compiles, launches, scene builds with **zero CSS errors and no exceptions**. ⚠️ **NOT visually verified** — every session so far has had no attached display (`screencapture` fails; `CVDisplayLink -6661`), so fidelity vs the PNGs and "no scrollbar / YOU centered" were confirmed structurally only. **A human must run `./gradlew run` on a real 1080p display and eyeball it.**
- **⚠️ ALL OF PHASE 5 IS UNCOMMITTED** (working tree only, branch `master`). New files: `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/`, `gradlew*`, `resources/`, `ui/`, `src/app/NodeRuntime.java`. Modified: `src/app/ServentMain.java`, `src/core/kademlia/{KademliaService,RoutingTable}.java`, `src/core/transfer/DownloadSession.java`, `test/**`, `.gitignore`, `CLAUDE.md`. Run `git status` first in a fresh session. Commit convention: NO `Co-Authored-By` trailer.
- **Increment 2 (next):** embed **vlcj/libVLC** video into `PlayerView`'s surface (PixelBuffer→`ImageView`) + watch-while-downloading driving `SlidingWindowPicker.setPlayhead`; add an engine RPC-event stream to feed the DHT inspector's live log; wire `TransferService` share/download + `DownloadSession.progress()` into Library/Add-Stream and the sliding-window strip. Later: `jpackage`/`jlink` native bundle. (vlcj is the one task that genuinely needs a display to verify — do it at a real machine.)

Before/with later Phase 5 increments, consider folding in the deferred engine/transfer gaps below when robustness matters.

**Key decisions (do not re-litigate — see memory + Dependency policy):** zero-dependency applies ONLY to `core.kademlia` (pure JDK); UI/media/packaging may use modern libs. Player = JavaFX + vlcj/libVLC via jpackage (single-window native app); Gradle introduced when that layer lands. Connectivity LAN-first, NAT traversal deferred behind the `Transport` interface. Commits use no `Co-Authored-By` trailer.

**Known engine gaps (deferred):** no RPC retry on UDP loss; serial awaits in lookup/store/findValue (a dead node costs a full timeout); `findValue` doesn't iterate through NODES; no STORE TTL/republish. **Unbounded local `store`:** the `MAX_STORE_KEYS` cap is enforced ONLY on the inbound STORE handler — the originator's local `store.put` in `storeValue` (and any future cache-on-read in `findValue`) bypass it, so a heavy writer/reader grows the key→value map without bound. Fix = route every write through a bounded store (LRU cap + TTL) and add republish; only then add `findValue` cache-on-read. Note: the DHT `store` is for SMALL values (infohash → peer/location metadata) — actual piece/media bytes belong in the Phase-4 transfer layer's own bounded cache, never in the DHT map. (Marked inline as `TODO(phase4-hardening)` in `KademliaService.storeValue`/`findValue`.)

**Known transfer gaps (deferred, Phase 4 skeleton):** single-seed only — the DHT holds one value per infohash (latest announce wins), so no multi-peer swarming yet (the engine's one-value-per-key `store` would need a peer-list/merge announce); no choking/unchoking or upload fairness (only matters with many peers); no endgame mode; `download` re-pulls the whole file (no resume/partial); the full small metadata rides in the DHT value, which is fine for modest files but would need a metadata-vs-peerlist split for very large ones; downloaders serve pieces they hold but don't re-announce themselves as seeds. The `SlidingWindowPicker` is built and unit-tested but the app's `download` uses `RarestFirstPicker`; wiring the window to a real playhead is the Phase-5 player's job.

## What this repo actually is

The repo is a **single root** (`src/`, `test/`, `chord/`, `kademlia/`). It contains **two layers side by side**: the new **Kademlia engine** (`core.kademlia` — pure JDK), which now **drives the runnable app** (`ServentMain` boots it over UDP), and the legacy **Chord DHT** simulation (`core.chord`, `servent.*`, `app.BootstrapServer`, `app.ServentInitializer` — the original RAF KIDS course framework), which still compiles but is **dormant** (no longer started by any entry point), kept as reference.

The (dormant) Chord side simulated a distributed system by launching a bootstrap server and N "servent" nodes as **separate JVM processes on localhost**, each on its own port, over Java-serialized TCP messages. The live Kademlia app reuses the same multi-process-on-localhost harness shape (`MultipleServentStarter`) but over UDP with no bootstrap server (node 0 is the seed). The `core.transfer` layer adds a parallel TCP channel for bulk piece transfer on top. `Readme.tex` is the **aspirational** weStream spec (Kademlia + bitfield gossip + chunk streaming); the DHT and transfer layers now exist (Phases 1–4); the media **player** (Phase 5) is now under way — the JavaFX shell + screens exist (`ui/`, Increment 1), but video playback (vlcj/libVLC) does not yet.

The root `digest.txt` (~140KB) is a **generated gitingest dump of the whole repo** — gitignored, not source. Don't read it (use the real files), don't edit it, don't commit it.

## Build & run

This started as a plain **IntelliJ IDEA** project (`weStream.iml`). As of Phase 5 there is **also a Gradle build** (`build.gradle` + committed `./gradlew` wrapper) — but ONLY for the JavaFX UI layer; the engine + headless CLI app still compile and run as plain JDK (IntelliJ build or the `javac` line below) and `./check.sh` does not touch Gradle. The repo is a **single root**: engine + app + CLI in `src/` (packages `app` = process startup/bootstrap + `NodeRuntime`, `core` = identity/routing/transfer, `cli`, `servent` = dormant Chord messaging), the JavaFX UI in `ui/` (package `ui`), bundled resources in `resources/` (`theme.css`, `fonts/`), compiled (plain-javac) classes in `out/production/weStream/`. (Historically the code lived in a nested `KiDS-vezbe9/` subfolder — that nesting has been flattened; references to `KiDS-vezbe9` anywhere are stale.)

Three entry points (all have `main`), now Kademlia:
- `ui.WeStreamApp` (Gradle: `./gradlew run --args="<port> [seed]"`) — the **Phase-5 JavaFX window** for one node. `1100`/`seed` = the seed; any other port bootstraps to `127.0.0.1:1100`. GUI front-end over the same `NodeRuntime` as `ServentMain`.
- `app.MultipleServentStarter` — the normal way to run. Spawns `SERVENT_COUNT` `app.ServentMain` processes (node 0 = seed, started first; no bootstrap-server JVM), redirecting each node's stdin/stdout/stderr to `kademlia/input/serventN_in.txt`, `kademlia/output/serventN_out.txt`, `kademlia/error/serventN_err.txt`. Type `stop` in its console to kill all processes. It spawns the child JVMs with classpath `out/production/weStream`, so **compile first** (IntelliJ build, or the `javac` line below).
- `app.ServentMain <servent_list.properties> <serventId>` — run a single node manually (id 0 = seed; others bootstrap to `servent0.port`). CLI commands: `info`, `pause <ms>`, `routing_info`, `dht_put <key> <value>`, `dht_get <key>`, `stop`.

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

**Player / UI (decided direction):** **JavaFX (UI) + vlcj/libVLC (embedded video — plays everything via the VLC engine), packaged with `jpackage` + `jlink`** into a native installer with an embedded JRE and bundled libVLC. vlcj's callback/PixelBuffer rendering blits libVLC frames into a JavaFX node, so video and JavaFX controls live in the **same single window** (true native app feel). Alternative if a web-style UI is preferred: **JCEF** (embedded Chromium, "Electron in Java") — heavier (~200MB+). **Avoid** JavaFX MediaView (weak codecs) as the player.

**Build tooling:** **Gradle is now present** (introduced in Phase 5 Increment 1 — committed `./gradlew`, Gradle 8.10.2, pinned to Temurin 21 + JavaFX 21.0.5 via `org.openjfx.javafxplugin`). It pulls JavaFX (and later vlcj, `jpackage`/`jlink`). Gradle is dev tooling, not a runtime dependency, and does **not** relax the `core.kademlia`/`core.transfer` import rule above — that rule is import-level and independently enforced by `./check.sh` (plain `javac src test`, no JavaFX on the classpath). The non-default `sourceSets` (`srcDirs = ['src','ui']`) keep the flat repo + IntelliJ + `check.sh` all working; UI code lives in `ui/` (never `src/`) so plain javac never sees JavaFX.

**Logging:** implement a zero-dependency async logger for the engine (`BlockingQueue` + daemon consumer thread, formatting/I-O off the network threads — see Conventions). The app/UI layer may use a real logging framework if convenient.

**Other notes:** Don't trust native Java serialization (`ObjectInputStream.readObject`) on bytes from arbitrary peers — use an explicit length-prefixed binary format for the engine wire protocol (hand-rolled, zero-dep). JUnit is fine for tests (dev/test scope).

## Configuration (`chord/servent_list.properties`)

```
servent_count=5      # number of nodes
chord_size=64        # max Chord keys; MUST be a power of 2 (sets ring size & finger-table depth)
bs.port=2000         # bootstrap server port
servent0.port=1100   # each node's listener port; MUST be in range 1000-2000
...
```
A node's Chord ID is derived purely from its port: `chordHash(port) = 61 * port % CHORD_SIZE` (`ChordState.chordHash`). Two ports that hash to the same ID collide — see the commented collision-test line in the properties file.

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

Not yet built (next phases): swapping discovery/join off Chord onto Kademlia in the runnable app; the piece/transfer layer (`transfer/`) and streaming window; NAT traversal; media/player (`media/`).

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
