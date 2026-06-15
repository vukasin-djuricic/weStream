// weStream — Phase 5 Player · design tokens (Direction A: Midnight Neon)
// Plain JS so you can import into React, Tailwind config, CSS-in-JS, anything.

export const tokens = {
  color: {
    // surfaces
    bgApp: "#0f0d15",
    bgChrome: "#141019",
    bgPanel: "#131019",
    surface: "#15111d",
    surface2: "#1a1623",
    surfaceInset: "#100d17",
    hover: "#1d1826",
    // borders
    border: "#221d2c",
    borderSoft: "#1c1826",
    borderStrong: "#2c2638",
    borderViolet: "#34284a",
    // text
    textHi: "#f4f1f8",
    text: "#e7e1ef",
    textMid: "#b3aac0",
    textSoft: "#8b8299",
    textLo: "#756c85",
    textDim: "#5f5670",
    // brand + semantic
    accent: "#c64ff0",
    accentDeep: "#9b3ec9",
    accentSoft: "#c08fe8",
    cyan: "#6cc8e8", // download
    pink: "#ee7fb0", // upload
    green: "#46d39a", // healthy / live
    greenText: "#74e3b0",
    amber: "#f4bf4f",
    red: "#f0795e",
  },
  gradient: {
    primary: "linear-gradient(135deg, #c64ff0, #9b3ec9)",
    youNode: "linear-gradient(150deg, #c64ff0, #7c2fd0)",
    progress: "linear-gradient(90deg, #9b3ec9, #c64ff0)",
    featureCard: "linear-gradient(120deg, #1d1430, #15111d 70%)",
  },
  fill: {
    accent12: "rgba(198,79,240,0.13)",
    accent14: "rgba(198,79,240,0.14)",
    green10: "rgba(70,211,154,0.10)",
    greenBorder: "rgba(70,211,154,0.28)",
  },
  font: {
    ui: "'Manrope', system-ui, sans-serif",
    mono: "'JetBrains Mono', monospace",
  },
  radius: { sm: 6, md: 11, lg: 14, xl: 16, xxl: 18, full: 999 },
  layout: { titleBar: 46, sidebar: 230, playerRail: 322 },
};

export default tokens;
