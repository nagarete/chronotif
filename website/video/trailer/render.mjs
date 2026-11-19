// Renders trailer.html frame by frame into an mp4 (1080x1920, 30 fps).
// Usage: PLAYWRIGHT_CORE=... node render.mjs <out.mp4> [--page vs.html] [--stills 1,5,9]
import { createRequire } from "node:module";
import { readFile, mkdir, rm } from "node:fs/promises";
import { spawn } from "node:child_process";
import { join, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE ?? "playwright-core");
const outFile = process.argv[2] ?? join(here, "../out/chronotif-trailer.mp4");
const stillsArg = process.argv.indexOf("--stills");
const stills = stillsArg > 0 ? process.argv[stillsArg + 1].split(",").map(Number) : null;
const pageArg = process.argv.indexOf("--page");
const pageFile = pageArg > 0 ? process.argv[pageArg + 1] : "trailer.html";
const FPS = 30;

const font = (await readFile(join(here,
  "../../node_modules/@fontsource-variable/newsreader/files/newsreader-latin-opsz-normal.woff2"))).toString("base64");
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM ?? "/usr/bin/chromium" });
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
await page.goto(pathToFileURL(join(here, pageFile)).href);
await page.addStyleTag({ content: `@font-face { font-family: Newsreader; font-weight: 200 800;
  src: url(data:font/woff2;base64,${font}) format("woff2"); }` });
await page.evaluate(() => document.fonts.ready);

if (stills) {
  const dir = dirname(outFile);
  await mkdir(dir, { recursive: true });
  for (const t of stills) {
    await page.evaluate((t) => window.render(t), t);
    await page.screenshot({ path: join(dir, `still-${t}.png`) });
  }
  await browser.close();
  process.exit(0);
}

await mkdir(dirname(outFile), { recursive: true });
const duration = await page.evaluate(() => window.DURATION);
const ff = spawn("ffmpeg", ["-loglevel", "error", "-y", "-f", "image2pipe", "-framerate", String(FPS), "-c:v", "png", "-i", "-",
  "-c:v", "libx264", "-crf", "16", "-preset", "slow", "-pix_fmt", "yuv420p", "-movflags", "+faststart", outFile], { stdio: ["pipe", "inherit", "inherit"] });
const frames = Math.round(duration * FPS);
for (let f = 0; f < frames; f++) {
  await page.evaluate((t) => window.render(t), f / FPS);
  const png = await page.screenshot({ type: "png" });
  if (!ff.stdin.write(png)) await new Promise((r) => ff.stdin.once("drain", r));
}
ff.stdin.end();
await new Promise((r) => ff.on("close", r));
await browser.close();
console.log(outFile, `${frames} frames`);
