// Exposes minimal, safe bridges to the renderer (contextIsolation stays on):
//   window.ws        — frameless window controls (over IPC)
//   window.westream  — this node's engine API port / id (from main's additionalArguments),
//                      so api.js talks to the JVM this window spawned (no ?api= hack needed).
const { contextBridge, ipcRenderer } = require("electron");

function argValue(flag) {
  const hit = process.argv.find((a) => a.startsWith(flag + "="));
  return hit ? hit.slice(flag.length + 1) : null;
}

const apiPort = argValue("--ws-api-port");
const nodeId = argValue("--ws-node-id");

contextBridge.exposeInMainWorld("ws", {
  minimize: () => ipcRenderer.send("win:minimize"),
  maximize: () => ipcRenderer.send("win:maximize"),
  close: () => ipcRenderer.send("win:close"),
});

contextBridge.exposeInMainWorld("westream", {
  apiPort: apiPort ? Number(apiPort) : null,
  nodeId: nodeId ? Number(nodeId) : null,
  apiBase: apiPort ? `http://127.0.0.1:${apiPort}` : null,
});
