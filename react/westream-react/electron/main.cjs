// Electron main process — wraps the React renderer in a native, frameless window
// AND owns the lifecycle of this node's Java engine. On boot it spawns the
// pure-JDK headless node (app.ServentMain) as a child process, health-checks its
// local HTTP API, hands the API port to the renderer (via preload), and kills the
// JVM on exit. One Electron window = one peer node (a 1:1 client simulation).
const { app, BrowserWindow, ipcMain, dialog } = require("electron");
const path = require("path");
const fs = require("fs");
const http = require("http");
const { spawn } = require("child_process");

// Native "open file" dialog for the Share screen — returns an absolute on-disk
// path the engine can seed. Registered once (ipcMain.handle throws on a dup
// channel), so it lives at module scope rather than inside createWindow. A
// sandboxed <input type=file> would not expose the real path, hence the dialog.
ipcMain.handle("dialog:pickFile", async () => {
  const r = await dialog.showOpenDialog({
    properties: ["openFile"],
    filters: [
      { name: "Video", extensions: ["mp4", "mkv", "webm", "avi", "mov", "m4v"] },
      { name: "All files", extensions: ["*"] },
    ],
  });
  return r.canceled || r.filePaths.length === 0 ? null : r.filePaths[0];
});

// Where the engine + config live and which java to spawn, in the two modes:
//  - dev (run from the repo): the plain-javac classes + `java` on PATH.
//  - packaged: the bundled jar + a jlinked JRE shipped as extraResources
//    (see electron/build-engine.cjs + package.json "build").
const PACKAGED = app.isPackaged;
const REPO_ROOT = path.resolve(__dirname, "..", "..", ".."); // electron/ is 3 deep (dev only)
const ENGINE = PACKAGED
  ? {
      java: path.join(process.resourcesPath, "runtime", "bin",
        process.platform === "win32" ? "java.exe" : "java"),
      jar: path.join(process.resourcesPath, "engine", "westream-engine.jar"),
      config: path.join(process.resourcesPath, "engine", "servent_list.properties"),
    }
  : {
      java: "java",
      classpath: path.join(REPO_ROOT, "out", "production", "weStream"),
      config: path.join(REPO_ROOT, "kademlia", "servent_list.properties"),
    };
const CONFIG = ENGINE.config;

// Which node this window runs (override with WS_NODE_ID=1 to launch a second peer).
const NODE_ID = parseInt(process.env.WS_NODE_ID || "0", 10);

/** Read servent<id>.port from the Kademlia config; derive the HTTP API port. */
function resolvePorts(nodeId) {
  let udpPort = 1100 + nodeId * 100; // sensible fallback if the file can't be read
  try {
    const text = fs.readFileSync(CONFIG, "utf8");
    const m = text.match(new RegExp(`^servent${nodeId}\\.port\\s*=\\s*(\\d+)`, "m"));
    if (m) udpPort = parseInt(m[1], 10);
  } catch (e) {
    console.warn(`[engine] couldn't read ${CONFIG}: ${e.message}; assuming UDP ${udpPort}`);
  }
  return { udpPort, apiPort: 11470 + (udpPort - 1100) };
}

const { udpPort, apiPort } = resolvePorts(NODE_ID);
let engine = null;

/** Spawn the Java engine as a child process; pipe its logs through, keep stdin open. */
function startEngine() {
  console.log(`[engine] starting node ${NODE_ID} (UDP ${udpPort}, API ${apiPort})`);
  // Packaged: run the bundled jar with the jlinked JRE. Dev: run the javac classes
  // with `java` on PATH. cwd holds the engine's scratch cache/ — in a packaged app
  // it must be writable (Program Files is not), so use the per-user data dir.
  const args = PACKAGED
    ? ["-jar", ENGINE.jar, CONFIG, String(NODE_ID)]
    : ["-cp", ENGINE.classpath, "app.ServentMain", CONFIG, String(NODE_ID)];
  const cwd = PACKAGED ? app.getPath("userData") : REPO_ROOT;
  // stdin is a live pipe we never write to — ServentMain's CLI Scanner parks on it
  // (an EOF would crash that thread), while the engine runs on its own threads.
  engine = spawn(ENGINE.java, args, { cwd, stdio: ["pipe", "pipe", "pipe"] });
  engine.stdout.on("data", (d) => process.stdout.write(`[engine ${NODE_ID}] ${d}`));
  engine.stderr.on("data", (d) => process.stderr.write(`[engine ${NODE_ID}] ${d}`));
  engine.on("error", (e) => console.error(`[engine] spawn failed: ${e.message} ` +
    `(java: ${ENGINE.java})`));
  engine.on("exit", (code, sig) => console.log(`[engine] exited (code=${code}, signal=${sig})`));
}

function stopEngine() {
  if (engine && !engine.killed) {
    engine.kill(); // SIGTERM — the JVM is a single process, no tree to walk
    engine = null;
  }
}

/** Poll the engine's /api/status until it answers (just logs readiness; the
 *  renderer polls independently and shows "CONNECTING…" until then). */
function awaitEngine(retries = 40) {
  const url = `http://127.0.0.1:${apiPort}/api/status`;
  const tick = (left) => {
    const req = http.get(url, (res) => {
      res.resume();
      if (res.statusCode === 200) console.log(`[engine] API ready on ${apiPort}`);
      else retry(left);
    });
    req.on("error", () => retry(left));
    req.setTimeout(800, () => req.destroy());
  };
  const retry = (left) => {
    if (left > 0) setTimeout(() => tick(left - 1), 500);
    else console.warn(`[engine] API not reachable on ${apiPort} after ~20s`);
  };
  tick(retries);
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1340,
    height: 840,
    minWidth: 600,
    minHeight: 540,
    frame: false, // custom title bar lives in the React app
    backgroundColor: "#0f0d15",
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      // Hand the engine's API port + node id to the renderer (preload reads these).
      additionalArguments: [`--ws-api-port=${apiPort}`, `--ws-node-id=${NODE_ID}`],
    },
  });

  win.once("ready-to-show", () => win.show());

  // Dev: load the Vite dev server (hot reload). Prod: load the built bundle.
  const devUrl = process.env.ELECTRON_START_URL;
  if (devUrl) {
    win.loadURL(devUrl);
  } else {
    win.loadFile(path.join(__dirname, "..", "dist", "index.html"));
  }

  ipcMain.removeAllListeners("win:minimize");
  ipcMain.removeAllListeners("win:maximize");
  ipcMain.removeAllListeners("win:close");
  ipcMain.on("win:minimize", () => win.minimize());
  ipcMain.on("win:maximize", () => (win.isMaximized() ? win.unmaximize() : win.maximize()));
  ipcMain.on("win:close", () => win.close());
}

app.whenReady().then(() => {
  startEngine();   // spawn the JVM once, before the UI
  awaitEngine();
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow(); // engine already running
  });
});

// One window = one node: closing it shuts the whole peer down (engine included).
app.on("window-all-closed", () => app.quit());
app.on("before-quit", stopEngine);
app.on("will-quit", stopEngine); // belt-and-braces if a quit path skips before-quit
