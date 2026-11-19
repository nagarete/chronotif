import { defineConfig } from "vite";
import { releasesHtml, releaseTeaser } from "./releases.mjs";

const REPO = "nagarete/chronotif";
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

function download(rel, nightly) {
  if (rel.draft) return null;
  const match = nightly
    ? rel.tag_name?.match(/^nightly-(\d+)\.(\d+)\.(\d+)-(\d+)$/)
    : !rel.prerelease && rel.tag_name?.match(/^v(\d+)\.(\d+)\.(\d+)$/);
  const apk = rel.assets?.find((a) => a.name.endsWith(".apk"));
  if (!match || !apk) return null;
  return {
    tag: rel.tag_name,
    releaseUrl: rel.html_url,
    apkName: apk.name,
    apkUrl: apk.browser_download_url,
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
    APK_SIZE: stable.apkSize.replace(" ", "&nbsp;"),
    NIGHTLY_APK_URL: nightly.apkUrl,
    NIGHTLY_RELEASE_URL: nightly.releaseUrl,
    RELEASES: releasesHtml({ nightlyReleaseUrl: nightly.releaseUrl }),
    RELEASE_TEASER: releaseTeaser(),
  };
  return {
    build: {
      rollupOptions: { input: { main: "index.html", releases: "releases.html" } },
    },
    plugins: [
      {
        name: "chronotif-release",
        transformIndexHtml: (html) =>
          html.replace(/%CHRONOTIF_(\w+)%/g, (m, key) => {
            if (!(key in vars)) throw new Error(`Unknown placeholder ${m}`);
            return vars[key];
          }),
      },
    ],
  };
});
