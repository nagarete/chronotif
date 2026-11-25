import { readdirSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { defineConfig } from "vite";
import { esc, releasesHtml, releaseTeaser } from "./releases.mjs";

const REPO = "nagarete/chronotif";
// The feedback form's Turnstile widget (Cloudflare dashboard → Turnstile). A site key is public by
// design; its secret is the Worker's TURNSTILE_SECRET.
const TURNSTILE_SITE_KEY = "0x4AAAAAAGSNOjQrkSZzuN1e";
const RELEASES = `https://github.com/${REPO}/releases`;

// Resolve both channels at build time, so visitors never call GitHub's API.
// Offline builds link to the release pages rather than guessing unpublished APK URLs.
const fallback = (nightly) => ({
  tag: nightly ? "Nightly" : "Stable",
  releaseUrl: nightly ? `${RELEASES}?q=nightly&expanded=true` : `${RELEASES}/latest`,
  apkUrl: nightly ? `${RELEASES}?q=nightly&expanded=true` : `${RELEASES}/latest`,
  apkName: nightly ? "nightly APK" : "stable APK",
  apkSize: "Small",
  version: "",
});

async function github(path) {
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases${path}`, {
    headers: {
      Accept: "application/vnd.github+json",
      "User-Agent": "chronotif-website-build",
      // CI passes its token to avoid the shared unauthenticated rate limit.
      ...(process.env.GITHUB_TOKEN && { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` }),
    },
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) throw new Error(`GitHub API returned ${res.status}`);
  return res.json();
}

// Release data from the API lands in the page, so only the shapes build.yml publishes are taken:
// a known tag, its APK by exact name (`chronotif-<tag>.apk`, next to a `.sha256` file) and the URLs
// GitHub derives from both. Anything else falls back to the release pages.
function download(rel, nightly) {
  if (rel.draft) return null;
  const match = nightly
    ? rel.tag_name?.match(/^nightly-(\d+)\.(\d+)\.(\d+)-(\d+)$/)
    : !rel.prerelease && rel.tag_name?.match(/^v(\d+)\.(\d+)\.(\d+)$/);
  if (!match) return null;
  const tag = match[0];
  const apkName = `chronotif-${tag}.apk`;
  const apk = rel.assets?.find((a) => a.name === apkName);
  if (!apk || !Number.isSafeInteger(apk.size) || apk.size <= 0) return null;
  const releaseUrl = `${RELEASES}/tag/${tag}`;
  const apkUrl = `${RELEASES}/download/${tag}/${apkName}`;
  if (rel.html_url !== releaseUrl || apk.browser_download_url !== apkUrl) return null;
  return {
    tag,
    releaseUrl,
    apkName,
    apkUrl,
    apkSize: `~${Math.round(apk.size / 10_240) * 10} KB`,
    version: match.slice(1, 4).join("."),
    order: match.slice(1).map(Number),
  };
}

async function release(nightly) {
  if (process.env.CHRONOTIF_OFFLINE !== "1") {
    try {
      if (!nightly) {
        const stable = download(await github("/latest"), false);
        if (!stable) throw new Error("latest stable release has no APK");
        return stable;
      }
      const releases = (await github("?per_page=100"))
        .map((rel) => download(rel, true)).filter(Boolean);
      releases.sort((a, b) => {
        for (let i = 0; i < a.order.length; i++) {
          if (a.order[i] !== b.order[i]) return b.order[i] - a.order[i];
        }
        return 0;
      });
      if (!releases.length) throw new Error("no published nightly with an APK");
      return releases[0];
    } catch (e) {
      console.warn(`[chronotif] Couldn't fetch ${nightly ? "nightly" : "stable"} (${e.message}); linking to releases.`);
    }
  }
  return fallback(nightly);
}

// Replaces each %CHRONOTIF_KEY% with lookup(KEY), and fails the build on an unknown key.
function fill(text, lookup) {
  return text.replace(/%CHRONOTIF_(\w+)%/g, (m, key) => {
    const value = lookup(key);
    if (value === undefined) throw new Error(`Unknown placeholder ${m}`);
    return value;
  });
}

// A JSON-LD block is parsed, filled value by value and written back with JSON.stringify, so a
// value can't break out of its string. "<" is escaped too, so nothing in it can close the script.
function fillJsonLd(json, vars) {
  const data = JSON.parse(json, (_, v) => (typeof v === "string" ? fill(v, (k) => vars[k]) : v));
  return JSON.stringify(data, null, 2).replace(/</g, "\\u003c");
}

// Every placeholder outside a JSON-LD block sits in HTML text or a quoted attribute, and is
// escaped for both. `markup` holds HTML that releases.mjs built and escaped already.
function fillHtml(html, vars, markup) {
  const lookup = (k) => (k in markup ? markup[k] : k in vars ? esc(vars[k]) : undefined);
  return html
    .split(/(<script type="application\/ld\+json">[\s\S]*?<\/script>)/)
    .map((part, i) => {
      if (i % 2 === 0) return fill(part, lookup);
      const json = part.slice(part.indexOf(">") + 1, part.lastIndexOf("</script>"));
      return `<script type="application/ld+json">\n${fillJsonLd(json, vars)}\n    </script>`;
    })
    .join("");
}

// The Worker answers Range requests for these files and needs their sizes (worker/index.js).
function writeVideoSizes(outDir) {
  const dir = join(outDir, "video");
  const sizes = Object.fromEntries(
    readdirSync(dir).map((name) => [`/video/${name}`, statSync(join(dir, name)).size]),
  );
  writeFileSync(new URL("worker/video-sizes.json", import.meta.url), `${JSON.stringify(sizes, null, 2)}\n`);
}

export default defineConfig(async () => {
  const [stable, nightly] = await Promise.all([release(false), release(true)]);
  console.log(`[chronotif] Stable download → ${stable.apkUrl}`);
  console.log(`[chronotif] Nightly download → ${nightly.apkUrl}`);
  const vars = {
    VERSION: stable.version,
    TAG: stable.tag,
    RELEASE_URL: stable.releaseUrl,
    APK_NAME: stable.apkName,
    APK_URL: stable.apkUrl,
    NIGHTLY_APK_URL: nightly.apkUrl,
    NIGHTLY_RELEASE_URL: nightly.releaseUrl,
    TURNSTILE_SITE_KEY,
  };
  const markup = {
    APK_SIZE: esc(stable.apkSize).replace(" ", "&nbsp;"),
    RELEASES: releasesHtml({ nightlyReleaseUrl: nightly.releaseUrl }),
    RELEASE_TEASER: releaseTeaser(),
  };
  let outDir;
  return {
    build: {
      rollupOptions: { input: { main: "index.html", releases: "releases.html", feedback: "feedback.html" } },
    },
    plugins: [
      {
        name: "chronotif-release",
        transformIndexHtml: (html) => fillHtml(html, vars, markup),
      },
      {
        name: "chronotif-video-sizes",
        apply: "build",
        configResolved: (config) => {
          outDir = join(config.root, config.build.outDir);
        },
        closeBundle: () => writeVideoSizes(outDir),
      },
    ],
  };
});
