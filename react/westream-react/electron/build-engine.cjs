// Build the bundled engine for packaging: compile src, pack a runnable jar, and
// jlink a minimal JRE into react/westream-react/build/{engine,runtime}. electron-builder
// then copies those into the installer as extraResources (see package.json "build").
//
// JDK tools (javac/jar/jdeps/jlink) are resolved from JAVA_HOME — set it to a JDK 19+
// (locally e.g. C:\Program Files\Java\jdk-19; in CI, actions/setup-java sets it).
// The jlinked runtime is platform-native, so run this on the OS you're packaging for.
const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const JAVA_HOME = process.env.JAVA_HOME;
if (!JAVA_HOME || !fs.existsSync(JAVA_HOME)) {
  console.error("JAVA_HOME is not set to a JDK 19+ install. e.g. (Windows):\n" +
    '  set JAVA_HOME=C:\\Program Files\\Java\\jdk-19');
  process.exit(1);
}
const tool = (name) => path.join(JAVA_HOME, "bin", name + (process.platform === "win32" ? ".exe" : ""));

const REPO_ROOT = path.resolve(__dirname, "..", "..", "..");
const SRC = path.join(REPO_ROOT, "src");
const CONFIG = path.join(REPO_ROOT, "kademlia", "servent_list.properties");
const OUT = path.join(__dirname, "..", "build");
const CLASSES = path.join(OUT, "classes");
const ENGINE = path.join(OUT, "engine");
const RUNTIME = path.join(OUT, "runtime");
const JAR = path.join(ENGINE, "westream-engine.jar");

const run = (bin, args) => {
  console.log(">", path.basename(bin), args.join(" ").slice(0, 120));
  execFileSync(bin, args, { stdio: ["ignore", "inherit", "inherit"] });
};

// Fresh output tree (jlink refuses to write into an existing dir).
fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(CLASSES, { recursive: true });
fs.mkdirSync(ENGINE, { recursive: true });

// 1. Compile every .java under src/ (the engine + app + HTTP API — pure JDK).
const sources = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith(".java")) sources.push(p);
  }
})(SRC);
run(tool("javac"), ["-d", CLASSES, ...sources]);

// 2. Pack a runnable jar (Main-Class = app.ServentMain) + bundle the node config.
run(tool("jar"), ["--create", "--file", JAR, "--main-class", "app.ServentMain", "-C", CLASSES, "."]);
fs.copyFileSync(CONFIG, path.join(ENGINE, "servent_list.properties"));

// 3. Minimal JRE: ask jdeps which modules the jar needs, then jlink just those.
//    jdk.httpserver (com.sun.net.httpserver, the HTTP API) + java.base are always
//    required; add them explicitly in case jdeps under-reports the com.sun usage.
let detected = "";
try {
  detected = execFileSync(tool("jdeps"),
    ["--print-module-deps", "--ignore-missing-deps", JAR]).toString().trim();
} catch (e) {
  console.warn("jdeps failed; falling back to java.base,jdk.httpserver");
}
const mods = new Set(detected.split(",").map((m) => m.trim()).filter(Boolean));
mods.add("java.base");
mods.add("jdk.httpserver");
const moduleList = [...mods].join(",");
console.log("jlink modules:", moduleList);
// (No --compress: the numeric form is deprecated on newer JDKs; the size hit is small.)
run(tool("jlink"), ["--add-modules", moduleList,
  "--strip-debug", "--no-man-pages", "--no-header-files",
  "--output", RUNTIME]);

console.log("\nEngine packaged:\n  jar:     " + JAR + "\n  runtime: " + RUNTIME);
