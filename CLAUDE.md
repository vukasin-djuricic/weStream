# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status & roadmap (READ FIRST)

**Where we are (2026-06):** the project has migrated from a legacy **Chord** DHT (the original course framework) to a **Kademlia**-based P2P engine, the foundation of the aspirational "weStream" (BitTorrent download + Popcorn-Time streaming). **The runnable app now runs Kademlia** — launching `ServentMain` / `MultipleServentStarter` boots a `KademliaService` over UDP (Phase 3 done). The Chord classes still compile but are dormant (never started), kept as reference. First thing to do in a fresh session: run `./check.sh` (50 checks) to confirm green.

**Done:**
- Phase 1 — routing primitives: `NodeId` (160-bit, XOR), `Contact`, `KBucket` (k=20, LRS), `RoutingTable`.
- Phase 2 — `UdpTransport` + RPC (PING/FIND_NODE/STORE/FIND_VALUE), `CompletableFuture` correlation + scheduler timeouts, iterative `nodeLookup` (α=3), `bootstrap`, `storeValue`/`findValue`.
- Hardening (codec bounds, wire-source routing, STORE cap) + regression suite `./check.sh`.
- Phase 3 — Kademlia wired into the runnable app. `ServentMain` boots `UdpTransport` + `KademliaService` + `CLIParser`; non-seed nodes `bootstrap(seed)` on a dedicated `kad-bootstrap` thread (node 0 = seed). `AppConfig` holds `kademliaService`/`transport`/`myPort`/`isSeedNode`/`SEED_HOST`/`SEED_PORT`; slim `readConfig` (only `servent_count` + `serventN.port`, no `chord_size`/`bs.port`, no `ServentInfo`). CLI: `dht_put`/`dht_get` → `storeValue`/`findValue` (string key → SHA-1 `NodeId`, value = UTF-8 bytes), `successor_info` replaced by `routing_info`, `info` prints `kademliaService.self()`, `stop` closes the transport. `MultipleServentStarter` spawns N `ServentMain` nodes with no bootstrap-server JVM, targeting a new `kademlia/` test dir (mirrors `chord/`). Verified end-to-end: store on node 0, `dht_get` resolves on node 4 via `findValue`. Chord kept dormant (Option A).

**Next — Phase 4: transfer/streaming layer** (`transfer/`: pieces, bitfield gossip, sliding-window picker). Phase 5 — media/player (`media/`). Before/with Phase 4, consider folding in the deferred engine gaps below when robustness matters.

**Key decisions (do not re-litigate — see memory + Dependency policy):** zero-dependency applies ONLY to `core.kademlia` (pure JDK); UI/media/packaging may use modern libs. Player = JavaFX + vlcj/libVLC via jpackage (single-window native app); Gradle introduced when that layer lands. Connectivity LAN-first, NAT traversal deferred behind the `Transport` interface. Commits use no `Co-Authored-By` trailer.

**Known engine gaps (deferred):** no RPC retry on UDP loss; serial awaits in lookup/store/findValue (a dead node costs a full timeout); `findValue` doesn't iterate through NODES; no STORE TTL/republish. **Unbounded local `store`:** the `MAX_STORE_KEYS` cap is enforced ONLY on the inbound STORE handler — the originator's local `store.put` in `storeValue` (and any future cache-on-read in `findValue`) bypass it, so a heavy writer/reader grows the key→value map without bound. Fix = route every write through a bounded store (LRU cap + TTL) and add republish; only then add `findValue` cache-on-read. Note: the DHT `store` is for SMALL values (infohash → peer/location metadata) — actual piece/media bytes belong in the Phase-4 transfer layer's own bounded cache, never in the DHT map. (Marked inline as `TODO(phase4-hardening)` in `KademliaService.storeValue`/`findValue`.)

## What this repo actually is

The repo is a **single root** (`src/`, `test/`, `chord/`, `kademlia/`). It contains **two layers side by side**: the new **Kademlia engine** (`core.kademlia` — pure JDK), which now **drives the runnable app** (`ServentMain` boots it over UDP), and the legacy **Chord DHT** simulation (`core.chord`, `servent.*`, `app.BootstrapServer`, `app.ServentInitializer` — the original RAF KIDS course framework), which still compiles but is **dormant** (no longer started by any entry point), kept as reference.

The (dormant) Chord side simulated a distributed system by launching a bootstrap server and N "servent" nodes as **separate JVM processes on localhost**, each on its own port, over Java-serialized TCP messages. The live Kademlia app reuses the same multi-process-on-localhost harness shape (`MultipleServentStarter`) but over UDP with no bootstrap server (node 0 is the seed). `Readme.tex` is the **aspirational** weStream spec (Kademlia + bitfield gossip + chunk streaming); treat it as direction, not current state — the streaming/transfer layers don't exist yet.

The root `digest.txt` (~140KB) is a **generated gitingest dump of the whole repo** — gitignored, not source. Don't read it (use the real files), don't edit it, don't commit it.

## Build & run

This is a plain **IntelliJ IDEA** project (`weStream.iml`), no Maven/Gradle. The repo is a **single root**: sources in `src/` (packages `app` = process startup/bootstrap, `core` = node identity + routing state, `cli`, `servent` = transport/messaging), compiled classes in `out/production/weStream/`. (Historically the code lived in a nested `KiDS-vezbe9/` subfolder with its own IntelliJ project — that nesting has been flattened; if you see references to `KiDS-vezbe9` anywhere, they are stale.)

Two entry points (both have `main`), now Kademlia:
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

**Build tooling:** introducing **Gradle** is expected once the app/UI/media layer lands (to pull JavaFX + vlcj and drive `jpackage`/`jlink`). Gradle is dev tooling, not a runtime dependency, and does **not** relax the `core.kademlia` import rule above. The engine phases (Kademlia RPC, lookup) are still written and compiled as plain JDK today — defer Gradle until the player/UI work actually begins.

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
