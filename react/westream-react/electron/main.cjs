// Electron main process — wraps the React renderer in a native, frameless window
// AND owns the lifecycle of this node's Java engine. On boot it spawns the
// pure-JDK headless node (app.ServentMain) as a child process, health-checks its
// local HTTP API, hands the API port to the renderer (via preload), and kills the
// JVM on exit. One Electron window = one peer node (a 1:1 client simulation).
const { app, BrowserWindow, ipcMain } = require("electron");
const path = require("path");
const fs = require("fs");
const http = require("http");
const { spawn } = require("child_process");

// The repo root holds the compiled engine + the Kademlia config (electron/ is 3 deep).
const REPO_ROOT = path.resolve(__dirname, "..", "..", "..");
const CLASSPATH = path.join(REPO_ROOT, "out", "production", "weStream");
const CONFIG = path.join(REPO_ROOT, "kademlia", "servent_list.properties");

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
  // stdin is a live pipe we never write to — ServentMain's CLI Scanner parks on it
  // (an EOF would crash that thread), while the engine runs on its own threads.
  engine = spawn("java", ["-cp", CLASSPATH, "app.ServentMain", CONFIG, String(NODE_ID)], {
    cwd: REPO_ROOT,
    stdio: ["pipe", "pipe", "pipe"],
  });
  engine.stdout.on("data", (d) => process.stdout.write(`[engine ${NODE_ID}] ${d}`));
  engine.stderr.on("data", (d) => process.stderr.write(`[engine ${NODE_ID}] ${d}`));
  engine.on("error", (e) => console.error(`[engine] spawn failed: ${e.message} ` +
    `(is 'java' on PATH and is ${CLASSPATH} compiled?)`));
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
    minWidth: 1100,
    minHeight: 720,
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
