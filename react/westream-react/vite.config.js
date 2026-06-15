import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  base: "./", // relative asset paths so the built bundle loads under file:// in Electron
  server: { port: 5173, open: false },
});
