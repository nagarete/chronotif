# chronotif.com

The landing page for Chronotif. It's a single static page: plain HTML, CSS and a little
JavaScript, built with [Vite](https://vite.dev). There's no backend, analytics, cookies or
third-party requests. The only font (Newsreader) is self-hosted from `@fontsource-variable`.

Everything for the site, including `node_modules/` and the build output, stays inside
`website/`. The Android app isn't affected.

```
index.html               the page (all copy lives here)
src/style.css            styles
vite.config.js           resolves the newest release for the download links
src/main.js              masthead + scroll reveal (skipped with reduced motion)
src/demo.js              the interactive NEW / EARLIER illustration, modelled on core/Attention.kt
public/                  copied as-is: favicons, og.png, robots.txt, sitemap.xml
public/screens/          blurred, metadata-stripped screenshots (generated)
scripts/redact-screenshots.sh   raw screenshots → public/screens/
scripts/og.html, og-image.mjs   source and renderer for public/og.png
screenshots-raw/         original device screenshots (git-ignored, never published)
```

## Local development

Requires Node.js 20.19+ or 22.12+.

```sh
cd website
npm install
npm run dev        # http://localhost:5173 with live reload
```

## Production build

```sh
npm run build      # → website/dist/
npm run preview    # serve dist/ at http://localhost:4173 to check it
```

`dist/` is a self-contained static site, so any static host can serve it.

## Screenshots and privacy

Raw screenshots show real notifications, including other people's names, photos and messages.
Keep them in `screenshots-raw/`, which is git-ignored and never copied to `dist/`. To publish
them, `npm run screens` blurs the personal parts of each card, strips EXIF metadata and writes
480 px and 800 px WebP files to `public/screens/` (needs ImageMagick 7).

To add or replace a screenshot, edit the `shots` list in `scripts/redact-screenshots.sh`. Each
entry gives the regions to blur in the raw image's pixel coordinates. Always look at the output
before committing it.

`npm run og` regenerates the 1200×630 social image `public/og.png` from `scripts/og.html` using
headless Chromium (set `CHROME=/path/to/chrome` if `chromium` isn't on your `PATH`).

## Download links

*Download stable* links to the APK from GitHub's latest stable release, currently v0.2.4.
*Download nightly* links to the newest `nightly-<version>-<build>` APK, ordered by version
and build number. Both buttons appear at the top and bottom of the page. Nightly release
notes explain the changes still being tested.

`vite.config.js` resolves the links at build time from GitHub's public API. The stable tag,
file name and size come from that release. Visitors' browsers never call GitHub's API.

- Rebuild and redeploy after publishing a release or changing the stable release.
- If GitHub can't be reached, or with `CHRONOTIF_OFFLINE=1`, buttons link to the stable and
  nightly release pages. The build never guesses a download URL from an unpublished version.
- The build log prints the stable and nightly download URLs it selected.

## Deployment

The production URL is **https://chronotif.com/**. The canonical link, Open Graph/Twitter tags,
`robots.txt` and `sitemap.xml` already use it. If the domain changes, search for
`chronotif.com` and update every match.

Any static host works. Configure it with:

| Setting           | Value           |
| ----------------- | --------------- |
| Root directory    | `website`       |
| Build command     | `npm ci && npm run build` |
| Output directory  | `dist`          |
| Node version      | 22              |

- **Cloudflare Pages / Netlify / Vercel**: create a project from the GitHub repo using the
  settings above, then add `chronotif.com` (and `www.chronotif.com`, redirecting to the apex)
  as a custom domain and follow the host's DNS instructions.
- **GitHub Pages**: add a workflow that runs the build in `website/` and uploads `website/dist`
  with `actions/upload-pages-artifact` and `actions/deploy-pages`, then set the custom domain
  under *Settings → Pages*.
- **Any web server**: `npm run build`, then copy `dist/` to the server's web root.

The existing Cloudflare Worker and custom domains are configured in `wrangler.jsonc`.
With Cloudflare credentials available, `npm run deploy` builds and publishes the site.

## Keeping it accurate

Every product claim on the page comes from the app's README, manifest and source. When the app
changes, check the page:

- Minimum Android version: `minSdk` in `app/build.gradle.kts` (currently 30 = Android 11).
- Permissions and install steps: `AndroidManifest.xml` and the README's *Install* section.
- Network and updates: `Updater.kt` (the only network code) and the README's *Updates*
  section. The privacy section describes exactly what the update check sends.
- "What counts as a check": `CHECK_DWELL_MS` in `ui/PanelActivity.kt` and `LockSession` in
  `LockPolicy.kt`.
- Stable APK size and tag come from GitHub's latest stable release. Nightly downloads are
  resolved separately from nightly tags. See *Download links*.
