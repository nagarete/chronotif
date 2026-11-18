// Records the website's interactive demo as a vertical clip, straight from the built dist/.
// Usage: PLAYWRIGHT_CORE=/path/to/node_modules/playwright-core node demo-capture.mjs <out-dir> [--still]
import { createRequire } from "node:module";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { extname, join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE ?? "playwright-core");
const dist = join(dirname(fileURLToPath(import.meta.url)), "../dist");
const out = process.argv[2] ?? "raw/demo";
const still = process.argv.includes("--still");
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml",
  ".webp": "image/webp", ".png": "image/png", ".woff2": "font/woff2", ".ico": "image/x-icon" };

await mkdir(out, { recursive: true });
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM ?? "/usr/bin/chromium" });
const page = await browser.newPage({ viewport: { width: 540, height: 960 }, deviceScaleFactor: 2, colorScheme: "dark" });
await page.route("https://chronotif.local/**", async (route) => {
  let path = new URL(route.request().url()).pathname;
  if (path.endsWith("/")) path += "index.html";
  try {
    route.fulfill({ body: await readFile(join(dist, path)), contentType: types[extname(path)] ?? "application/octet-stream" });
  } catch {
    route.fulfill({ status: 404 });
  }
});
await page.goto("https://chronotif.local/");
await page.evaluate(() => {
  const demo = document.querySelector(".demo");
  document.body.replaceChildren(demo);
  demo.querySelector(".demo__controls").style.display = "none";
  demo.querySelector("figcaption")?.remove();
});
await page.addStyleTag({ content: `
  html, body { margin: 0; height: 100%; overflow: hidden; }
  body { display: grid; place-items: center; }
  .demo { margin: 0; transform: scale(var(--s, 1)); transform-origin: center; }
` });
await page.evaluate(() => {
  const d = document.querySelector(".demo").getBoundingClientRect();
  document.body.style.setProperty("--s", Math.min(500 / d.width, 900 / d.height).toFixed(3));
});
await page.waitForTimeout(500);
if (still) {
  await page.screenshot({ path: join(out, "still.png") });
  await browser.close();
  process.exit(0);
}

const cdp = await page.context().newCDPSession(page);
const frames = [];
cdp.on("Page.screencastFrame", async ({ data, metadata, sessionId }) => {
  const file = join(out, `f${String(frames.length).padStart(5, "0")}.jpg`);
  frames.push({ file, t: metadata.timestamp });
  writeFile(file, Buffer.from(data, "base64"));
  cdp.send("Page.screencastFrameAck", { sessionId }).catch(() => {});
});
await cdp.send("Page.startScreencast", { format: "jpeg", quality: 95, maxWidth: 1080, maxHeight: 1920 });
const click = (action) => page.evaluate((a) => document.querySelector(`[data-demo="${a}"]`).click(), action);
const marks = [];
const mark = (name) => marks.push({ name, t: Date.now() / 1000 });
// The story: a new message arrives, you check, they step back, another arrives on top.
mark("start"); await page.waitForTimeout(1800);
mark("arrive"); await click("arrive"); await page.waitForTimeout(2600);
mark("check"); await click("check"); await page.waitForTimeout(3000);
mark("arrive2"); await click("arrive"); await page.waitForTimeout(3000);
mark("end");
await cdp.send("Page.stopScreencast");
await page.waitForTimeout(300);
// ffmpeg concat list: each frame lasts until the next one, so the clip keeps real timing.
const lines = frames.map((f, i) => `file '${f.file.split("/").pop()}'\nduration ${((frames[i + 1]?.t ?? f.t + 0.5) - f.t).toFixed(4)}`);
await writeFile(join(out, "frames.txt"), lines.join("\n") + `\nfile '${frames.at(-1).file.split("/").pop()}'\n`);
await writeFile(join(out, "marks.json"), JSON.stringify({ first: frames[0]?.t, marks }, null, 2));
await browser.close();
console.log(`${frames.length} frames`);
