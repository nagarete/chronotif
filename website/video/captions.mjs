// Renders each caption in captions.json as a transparent 1080x1920 PNG, in the site's type.
// Usage: PLAYWRIGHT_CORE=... node captions.mjs <captions.json> <out-dir>
import { createRequire } from "node:module";
import { readFile, mkdir } from "node:fs/promises";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE ?? "playwright-core");
const captions = JSON.parse(await readFile(process.argv[2], "utf8"));
const out = process.argv[3] ?? "raw/captions";
const font = (await readFile(join(here,
  "../node_modules/@fontsource-variable/newsreader/files/newsreader-latin-opsz-normal.woff2"))).toString("base64");

const css = `
  @font-face { font-family: Newsreader; src: url(data:font/woff2;base64,${font}) format("woff2"); font-weight: 200 800; }
  html, body { margin: 0; width: 1080px; height: 1920px; background: transparent; }
  body { font-family: "Noto Sans", system-ui, sans-serif; color: #f4f1ea; }
  .cap { position: absolute; left: 90px; right: 90px; top: 120px; height: 300px; display: grid; place-items: center;
         text-align: center; font: 500 60px/1.22 Newsreader, serif; letter-spacing: -0.01em; text-wrap: balance; }
  .cap b { font-weight: 700; color: #9db8ff; }
  .cap.kick { top: 100px; height: 340px; font-size: 56px; }
  .kicker { font: 600 30px/1 "Noto Sans", sans-serif; letter-spacing: .16em; text-transform: uppercase; color: #9db8ff; margin-bottom: 26px; }
  .card { position: absolute; inset: 0; display: grid; place-content: center; text-align: center; gap: 28px;
          background: radial-gradient(120% 70% at 50% 35%, #1b2236 0%, #0b0c10 60%); }
  .big { font: 600 150px/1 Newsreader, serif; letter-spacing: -0.03em; }
  .small { font: 400 52px/1.3 Newsreader, serif; color: #c9c6bf; max-width: 820px; margin: 0 auto; text-wrap: balance; }
  .url2 { font: 500 36px/1.3 "Noto Sans Mono", monospace; color: #c9c6bf; }
  .url { margin-top: 30px; font: 600 46px/1 "Noto Sans", sans-serif; color: #9db8ff; letter-spacing: 0.02em; }
`;

await mkdir(out, { recursive: true });
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM ?? "/usr/bin/chromium" });
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
for (const [name, c] of Object.entries(captions)) {
  const body = c.kind
    ? `<div class="card"><div class="big">${c.big}</div><div class="small">${c.small}</div>${c.url ? `<div class="url">${c.url}</div>` : ""}${c.url2 ? `<div class="url2">${c.url2}</div>` : ""}</div>`
    : c.kicker
      ? `<div class="cap kick"><div><div class="kicker">${c.kicker}</div>${c.text}</div></div>`
      : `<div class="cap"><div>${c.text}</div></div>`;
  await page.setContent(`<style>${css}</style>${body}`);
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: join(out, `${name}.png`), omitBackground: !c.kind });
}
await browser.close();
console.log(Object.keys(captions).length, "captions");
