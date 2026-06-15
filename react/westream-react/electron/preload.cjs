// Exposes minimal, safe window-control hooks to the renderer (frameless window).
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("ws", {
  minimize: () => ipcRenderer.send("win:minimize"),
  maximize: () => ipcRenderer.send("win:maximize"),
  close: () => ipcRenderer.send("win:close"),
});
