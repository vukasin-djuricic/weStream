// Electron main process — wraps the React renderer in a native, frameless window.
// The app draws its own title bar (traffic-light dots + window controls), so we
// run frameless (frame:false) and wire the controls over IPC (see preload.cjs).
const { app, BrowserWindow, ipcMain } = require("electron");
const path = require("path");

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
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
