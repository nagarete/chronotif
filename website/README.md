# chronotif.com

The landing page for Chronotif: plain HTML, CSS and a little JavaScript, built with
[Vite](https://vite.dev). There are no analytics or cookies, and no third-party requests except
Cloudflare Turnstile on the feedback page. The only font (Newsreader) is self-hosted from
`@fontsource-variable`. A small Cloudflare Worker serves it with security headers (see *Security
headers*) and emails Feedback posted to `/feedback` (see *Feedback*).

Everything for the site, including `node_modules/` and the build output, stays inside
`website/`. The Android app isn't affected.

```
index.html               the page (all copy lives here)
src/style.css            styles
vite.config.js           resolves the newest release for the download links
releases.mjs             renders src/releases.json into /releases and the homepage teaser
worker/index.js          the Worker: www redirect, security headers, video Range requests, /feedback
worker/feedback.js       checks a Feedback post and writes its email (tested by `npm test`)
feedback.html, src/feedback.js   the site's feedback form
src/main.js              masthead + scroll reveal (skipped with reduced motion)
src/demo.js              the interactive NEW / EARLIER illustration, modelled on core/Attention.kt
public/                  copied as-is: favicons, og.png, robots.txt, sitemap.xml
public/screens/          screenshots, metadata stripped (generated)
scripts/redact-screenshots.sh   promo takes and install screenshots → public/screens/
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

`dist/` is a self-contained static site, so any static host can serve it. The build also writes
`worker/video-sizes.json` (git-ignored), which the Worker needs to answer Range requests.

To check the Worker itself, run `npx wrangler dev` after a build. It serves `dist/` through
`worker/index.js` locally, with no Cloudflare account.

## Security headers

`worker/index.js` adds a Content-Security-Policy, `X-Content-Type-Options`, `Referrer-Policy`,
`Cross-Origin-Opener-Policy` and `Permissions-Policy` to every response. `run_worker_first` in
`wrangler.jsonc` sends assets through the Worker too, so a `_headers` file wouldn't apply.

The policy allows only the site's own files. Keep it that way:

- No inline `<script>` or `<style>`, and no `style=""` in markup. Vite emits both as files.
  Set dynamic styles through `element.style`, as `src/demo.js` does.
- A new font, image, video or script from another origin needs its own entry in `CSP`.
- After changing either, load `/`, `/releases` and `/feedback` under `npx wrangler dev` and check
  the browser console for CSP violations.
- `/feedback` alone also allows `challenges.cloudflare.com` for Turnstile's script and frame.

Another host would need the same headers in its own config.

## Feedback

`POST /feedback` takes Feedback from the app (*Settings → Send feedback*) and from
`feedback.html`, checks it (`worker/feedback.js`) and emails it with the `FEEDBACK_MAIL` binding.
Nothing is stored. It needs, on the `chronotif-website` Worker in the Cloudflare dashboard:

- Email Routing on for chronotif.com, with the maintainer's inbox as a verified destination address.
  `feedback@chronotif.com` forwards there too, so a sender can also just email it.
- The secrets `FEEDBACK_TO` (that inbox, kept out of the repository) and `TURNSTILE_SECRET` (the
  secret of the Turnstile widget whose site key is in `vite.config.js`). Set them under *Settings →
  Variables and secrets* as **Secret**; `wrangler deploy` keeps secrets but drops plain variables.

`FEEDBACK_LIMIT` allows three posts a minute per IP address. Without the secrets, `/feedback` answers
503. To try it locally, put both in `website/.dev.vars` (git-ignored; Turnstile's test secret
`1x0000000000000000000000000000000AA` passes any token) and run `npx wrangler dev`: mail is written
to a `.eml` file under `.wrangler/` instead of being sent.

## Screenshots and privacy

The app screenshots are frames from the promo takes in `video/raw/takes/`, recorded in demo mode
(see `video/README.md`). They show only made-up notifications from the demo apps, so nothing is
blurred. To add or replace one, edit the `frames` list in `scripts/redact-screenshots.sh` with a
take and a time inside its safe range, then run `npm run screens` (needs ffmpeg and ImageMagick 7).
It writes 480 px and 800 px WebP files to `public/screens/`.

The install-guide screenshots are real captures. Keep them in `screenshots-raw/`, which is
git-ignored and never copied to `dist/`. The same script crops them and blurs the account details.

Always look at the output before committing it.

`npm run og` regenerates the 1200×630 social image `public/og.png` from `scripts/og.html` using
headless Chromium (set `CHROME=/path/to/chrome` if `chromium` isn't on your `PATH`).

## Download links

*Download stable* links to the APK from GitHub's latest stable release.
*Download nightly* links to the newest `nightly-<version>-<build>` APK, ordered by version
and build number. Both buttons appear at the top and bottom of the page. Nightly release
notes explain the changes still being tested.

`vite.config.js` resolves the links at build time from GitHub's public API. The stable tag,
file name and size come from that release. Visitors' browsers never call GitHub's API.

- CI redeploys the site after every published release (stable or nightly). See *Deployment*.
  Redeploy by hand after changing which release is marked latest on GitHub.
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

`.github/workflows/website.yml` builds and deploys the site on its own:

- A pull request that touches `website/` gets a build check. The Android build in `build.yml`
  skips pull requests and pushes that only touch `website/`.
- Merging such a change to `main` builds the site, then runs `wrangler deploy` in a separate
  step. Only that step gets the Cloudflare token, so `npm ci` and the build never see it.
  Changes to `src/releases.json` don't deploy on merge, because the app bundles that file and
  its `next` notes go live with a release.
- `build.yml` calls it after every published release, so the download buttons follow GitHub.
- *Actions → website → Run workflow* deploys `main` by hand.

Deploying needs two repository secrets. Until both are set, the workflow only builds:

- `CLOUDFLARE_API_TOKEN`: a token from the *Edit Cloudflare Workers* template.
- `CLOUDFLARE_ACCOUNT_ID`: the account that owns the `chronotif-website` Worker.

## Keeping it accurate

Every product claim on the page comes from the app's README, manifest and source. When the app
changes, check the page:

- Minimum Android version: `minSdk` in `app/build.gradle.kts` (currently 30 = Android 11).
- Permissions and install steps: `AndroidManifest.xml` and the README's *Install* section.
- Network and updates: `Updater.kt`, `ExchangeRates.kt` and `UsageData.kt` (the network code),
  the README's *Updates* section and `docs/usage-data.md`. The privacy section describes exactly
  what the update check and Usage data send.
- "What counts as a check": `CHECK_DWELL_MS` in `ui/PanelActivity.kt` and `LockSession` in
  `LockPolicy.kt`.
- Stable APK size and tag come from GitHub's latest stable release. Nightly downloads are
  resolved separately from nightly tags. See *Download links*.
