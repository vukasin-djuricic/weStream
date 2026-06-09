# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo actually is

The active codebase (`KiDS-vezbe9/`) is a **Chord DHT** simulation framework — a university course project (RAF KIDS, vezbe 9). It simulates a distributed system by launching the bootstrap server and N "servent" nodes as **separate JVM processes on localhost**, each on its own port, communicating via Java-serialized objects over per-message TCP sockets.

`Readme.tex` describes an **aspirational** "weStream" P2P video streaming engine built on a **Kademlia** DHT (XOR metric, k-buckets, bitfield gossip, chunk streaming). **None of that exists in code yet.** There is no Kademlia, no streaming, no UDP — only the Chord ring. Treat `Readme.tex` as a target spec / direction, not a description of the current system. When asked to implement "weStream" features, you are evolving the Chord framework toward that vision.

## Build & run

This is a plain **IntelliJ IDEA** project (`weStream.iml`), no Maven/Gradle. The repo is a **single root**: sources in `src/` (packages `app` = process startup/bootstrap, `core` = node identity + routing state, `cli`, `servent` = transport/messaging), compiled classes in `out/production/weStream/`. (Historically the code lived in a nested `KiDS-vezbe9/` subfolder with its own IntelliJ project — that nesting has been flattened; if you see references to `KiDS-vezbe9` anywhere, they are stale.)

Two entry points (both have `main`):
- `app.MultipleServentStarter` — the normal way to run. Launches `app.BootstrapServer` then spawns `SERVENT_COUNT` `app.ServentMain` processes, redirecting each node's stdin/stdout/stderr to `chord/input/serventN_in.txt`, `chord/output/serventN_out.txt`, `chord/error/serventN_err.txt`. Type `stop` in its console to kill all processes. It spawns the child JVMs with classpath `out/production/weStream`, so **compile first** (IntelliJ build, or the `javac` line below).
- `app.ServentMain <servent_list.properties> <serventId>` — run a single node manually.

Compile manually from the repo root:
```
javac -d out/production/weStream $(find src -name '*.java')
```
(The child-process classpath in `MultipleServentStarter` was previously hardcoded with Windows backslashes — `out\\production\\KiDS-vezbe9` — which broke on macOS/Linux. It now uses forward slashes and the `weStream` module name.)

There is **no test suite**. "Testing" means running the simulation and reading the per-node output files in `chord/output/`. Scripted scenarios are driven by feeding commands through `chord/input/serventN_in.txt` (one CLI command per line).

## Dependency policy

**Default: zero runtime dependencies.** Do not add third-party libraries on a whim. This is a pedagogical concurrency/distributed-systems project — the point is to implement routing, k-buckets, the sliding-window picker, and async logging *by hand*, and the JDK already covers the core needs (`java.util.concurrent` for `BlockingQueue`/`Semaphore`/`CountDownLatch`/`ExecutorService`, `java.security.MessageDigest` for SHA-1 160-bit Kademlia IDs, `java.net`/NIO for sockets). Staying zero-dep also keeps the "clone and run" simplicity: no Maven/Gradle, trivial classpath. The blueprint rules (#3–#6) explicitly mandate hand-rolled implementations.

**Logging is NOT an exception** — implement a zero-dependency async logger (`BlockingQueue` + a daemon consumer thread that does the string formatting and file I/O off the network threads). See the logging note in Conventions. Do **not** pull in SLF4J/Logback unless the project later needs MDC, log rotation, or runtime reconfiguration.

**Treat these two areas separately — here a dependency may genuinely earn its keep:**
1. **Message deserialization (security).** The current `MessageUtil.readMessage` uses `ObjectInputStream.readObject()` on bytes from arbitrary peers — a classic deserialization/RCE vector and brittle across versions. Prefer replacing it with an explicit, length-prefixed (de)serializer. That can and should be done **zero-dep**; a serialization library (Protobuf/Jackson) is acceptable only if a hand-rolled format proves insufficient. Either way, do not keep trusting native Java serialization in a real P2P path.
2. **Tests.** The project has none today. Introducing **JUnit** (dev/test scope only, not a runtime dependency) is acceptable and encouraged when adding real tests — a hand-rolled test runner is not worth it.

**Only when/if this stops being a simulation and becomes a real P2P streaming service** do heavyweight runtime deps (e.g. Netty for async NIO under high churn, Protobuf for the wire format) become a net win over hand-rolling. Until then, default to no.

Whenever you do add any dependency, you must also introduce the build tooling to manage it (a `pom.xml`/`build.gradle`) — there is none today — and call that out explicitly, since it changes how the project is built and run.

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

### Kademlia routing — `core.kademlia.*` (in progress, coexists with Chord)
The migration toward the weStream blueprint is being built **alongside** the live Chord code, not as a replacement yet. Phase 1 (foundation) exists and compiles, but is **not wired into the running system** — `ServentMain`/handlers still use Chord. Don't assume Kademlia is active.

Present today (pure data structures, no networking):
- `NodeId` — 160-bit id (`BigInteger`), `fromPort(port)` = SHA-1 of `"localhost:"+port` (deterministic for reproducible runs). `distance` = XOR; `bucketIndex` = highest-set-bit of the distance (−1 for self).
- `Contact` — Kademlia peer reference (`NodeId` + host + port + `lastSeen`); equality is by `NodeId` only. The Kademlia analogue of `core.ServentInfo`.
- `KBucket` — capacity `k` (=20), least-recently-seen at head. `update()` refreshes/inserts, or returns the LRS contact as an **eviction candidate** when full (caller PINGs it; `replaceLeastRecentlySeen` only if dead). Fully `synchronized` per blueprint rule #3.
- `RoutingTable` — `NodeId.ID_BITS` (160) buckets indexed by `bucketIndex`; `findClosest(target, count)` is the query behind FIND_NODE/FIND_VALUE.

Not yet built (next phases): RPC messages (`PING`/`STORE`/`FIND_NODE`/`FIND_VALUE`) + handlers with request/response correlation, the iterative `nodeLookup` driver (α=3, no busy-wait), and swapping discovery/join off Chord.

### Messaging — `servent.message.*` + `servent.message.util.*`
- Messages are immutable, `Serializable`. `BasicMessage` is the base; equality/hash use `(messageId, senderPort)`. `MessageType` enum drives dispatch.
- **Send:** `MessageUtil.sendMessage(msg)` spawns a `DelayedMessageSender` thread that sleeps a **random 500–1500 ms** before opening a socket and writing the object. This is intentional jitter; ordering between messages is **not** guaranteed (non-FIFO).
- **Receive:** `SimpleServentListener` accepts connections (1s socket timeout to allow clean shutdown), reads one object per socket, and dispatches via a `switch` on `MessageType` to a handler, run on a `newWorkStealingPool()` thread pool. Handlers in `servent.handler.*` are **stateless, one per message type** — prefer keeping them stateless. `NullHandler` is the fallback.

To add a message type: add to `MessageType`, create the `Message` subclass, write a `*Handler`, and register a `case` in `SimpleServentListener.run()`'s switch. Missing the switch entry means the message silently falls through to `NullHandler`.

### CLI — `cli.CLIParser` + `cli.command.*`
First whitespace-delimited token is the command name; rest is the arg string. **Actually registered commands** (in `CLIParser` constructor): `info`, `pause`, `successor_info`, `dht_get`, `dht_put`, `stop`. Note the class-level Javadoc in `CLIParser` lists `ping`/`broadcast`/`causal_broadcast` etc. — those are **not registered** and not implemented; ignore that stale comment. To add a command, implement `CLICommand` and add it to the `commandList`.

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
