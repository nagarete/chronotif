// Renders src/releases.json into the /releases page and the homepage teaser at build time.
// A release video only appears once its file is in public/, so an entry can be written first.
import { existsSync, readFileSync } from "node:fs";

const RELEASES = "https://github.com/nagarete/chronotif/releases";
const root = new URL(".", import.meta.url);
const data = JSON.parse(readFileSync(new URL("src/releases.json", root), "utf8"));

const esc = (s) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

const date = (iso) =>
  new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-GB", {
    day: "numeric", month: "long", year: "numeric", timeZone: "UTC",
  });

function credit(m) {
  return m
    ? `<figcaption class="note">Music: “${esc(m.title)}” by ${esc(m.artist)}, from
        <a href="${esc(m.url)}">${esc(m.album)}</a>
        (<a href="${esc(m.licenseUrl)}">${esc(m.license)}</a>).</figcaption>`
    : "";
}

const player = (v) => `
          <div class="trailer__video">
            <video controls playsinline preload="none" poster="${esc(v.poster)}" width="720" height="1280" aria-label="${esc(v.label)}">
              <source src="${esc(v.src)}" type="video/mp4" />
              <a href="${esc(v.src)}">Download the video (MP4)</a>
            </video>
          </div>`;

const exists = (v) => existsSync(new URL(`public${v.src}`, root));

// One video, or a release told in parts: one player and a list that switches it (src/main.js).
// Without JavaScript the list links to each part's file.
function video(r) {
  const parts = (r.videos ?? []).filter(exists);
  if (parts.length) {
    const list = parts.map((p, i) => `
            <li><a class="parts__item${i === 0 ? " is-current" : ""}" href="${esc(p.src)}" data-poster="${esc(p.poster)}" data-label="${esc(p.label)}"${i === 0 ? ' aria-current="true"' : ""}>
              <span class="parts__n">${i + 1}</span> ${esc(p.title)}</a></li>`).join("");
    return `
        <figure class="release__video">${player(parts[0])}
          <ol class="parts" aria-label="Parts of the video">${list}
          </ol>
        </figure>`;
  }
  const v = r.video;
  if (!v || !exists(v)) return "";
  return `
        <figure class="release__video">${player(v)}
          ${credit(v.music)}
        </figure>`;
}

function entry(r, { next, nightlyReleaseUrl }) {
  const id = `v${r.version}`;
  const meta = next
    ? `<span class="release__tag">Next stable</span> In Nightly now ·
       <a href="${esc(nightlyReleaseUrl)}">Nightly builds</a>`
    : `<time datetime="${r.date}">${date(r.date)}</time> ·
       <a href="${RELEASES}/tag/${id}">APK &amp; checksum</a>`;
  const media = video(r);
  return `
      <article class="release${next ? " release--next" : ""}${media ? " release--video" : ""}" id="${id}" aria-labelledby="${id}-title">
        <div class="release__copy">
          <header>
            <h2 id="${id}-title">${esc(r.version)}</h2>
            <p class="release__meta">${meta}</p>
          </header>
          <p class="release__headline">${esc(r.headline)}</p>
          <ul class="release__notes">
            ${r.notes.map((n) => `<li>${esc(n)}</li>`).join("\n            ")}
          </ul>
        </div>${media}
      </article>`;
}

// Open: the next stable (if any) and the latest stable. Older releases sit behind a toggle.
export function releasesHtml({ nightlyReleaseUrl }) {
  const [latest, ...older] = data.stable;
  const opts = { nightlyReleaseUrl };
  return [
    data.next && entry(data.next, { ...opts, next: true }),
    entry(latest, opts),
    older.length &&
      `
      <details class="releases__older">
        <summary>Show older releases</summary>
        ${older.map((r) => entry(r, opts)).join("")}
      </details>`,
  ].filter(Boolean).join("");
}

export function releaseTeaser() {
  const r = data.next ?? data.stable[0];
  const where = data.next ? `New in ${r.version}, in Nightly now:` : `New in ${r.version}:`;
  return `${where} ${esc(r.headline)} <a href="/releases#v${r.version}">Release notes</a>`;
}
