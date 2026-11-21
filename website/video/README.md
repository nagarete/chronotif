# Promo video

A vertical (1080×1920) promo cut from two sources: takes recorded on a real phone, and the
website's interactive demo recorded in headless Chromium. Nothing in it is real. The phone shows
only made-up notifications posted by the demo apps in `poster/`.

## Keeping real notifications out

- **Demo mode** (debug build only, `DemoMode.kt`): Chronotif shows nothing but packages under
  `app.chronotif.demo.`. It stays on across restarts and can only be switched from adb.
- **Stable Chronotif sits out**: `scenes.sh setup` pauses its listener, so its edge handle and
  lock-screen view (which would show real notifications) are gone. `scenes.sh restore` brings it back.
- **Do Not Disturb** on the phone stops real banners from dropping over a take.
- Raw footage goes in `raw/` and renders in `out/`, both git-ignored. Check every frame before
  anything is published.

## Steps

1. Build and install the debug app and the demo apps (HyperOS asks to approve each new install):
   `./gradlew assembleDebug` and `../../gradlew -p poster assembleDebug` (with `ANDROID_HOME` set),
   then `adb install` each APK in `poster/build/outputs/apk/*/debug/`.
2. `./scenes.sh setup`, then `./scenes.sh earlier` about 40 minutes before the takes. Cards that
   aren't chats show their real posting time, so they have to age.
3. Record the takes (`./scenes.sh rec NAME SECS` while driving them with `./scenes.sh lock|fresh|panel|arrive|tap`). A take can start or pass through the home screen: check every take and pick cut points that skip it.
4. `node demo-capture.mjs raw/demo` records the website demo from `../dist` (run `vite build` first).
   `node captions.mjs raw/captions` renders `captions.json` as overlays. Both need
   `PLAYWRIGHT_CORE` pointing at an installed `playwright-core` and use `/usr/bin/chromium`.
5. `./edit.sh NAME` cuts `out/chronotif-NAME.mp4` from `cuts/NAME.sh` (segments) and `cuts/NAME.json`
   (captions). Add `MUSIC=raw/music/x.mp3` (and `MUSIC_START=seconds`) for sound; `mux.sh` levels it. Then `./scenes.sh restore` and uninstall the demo apps.

## Motion-graphics trailer

`trailer/trailer.html` is a self-contained animation: `render(t)` sets every element from the time
in seconds, so `trailer/render.mjs` steps through it frame by frame into `out/chronotif-trailer.mp4`.
`node trailer/render.mjs out/x.mp4 --stills 2,8.5` writes stills next to the output to check a
change. `trailer/vs.html` is a second trailer (`--page vs.html`) that sets the stock Android shade
beside Chronotif; see `trailer-vs.md`. `trailer/search.html` is the Search video (`--page search.html`,
see `search.md`). The 0.2.7 video comes in six parts, each with its page and notes: `whatsnew.html`
(the panel and the edge handle, `whatsnew.md`), `lock.html` (the lock screen, `lock.md`), `unlocked.html`
(without unlocking, `unlocked.md`), `preview.html` (the Preview and the panel, `preview.md`), and
`settings.html` and `tutorial.html` (settings, then the Tutorial and Usage data, `settings.md`). Parts 5
and 6 show real screens rendered with Robolectric, kept in `trailer/shots/`. Each part renders to
`out/chronotif-0.2.7-N-silent.mp4`; parts 1 and 2 also have a music version, `out/chronotif-0.2.7-N.mp4`. They show the same made-up apps and people as the phone takes, and must follow the same
ordering rules as the app (`docs/notification-behavior.md`): newest first, Conversation stacks,
and App stacks only in Earlier.

## Takes in raw/takes (17T Pro, 18 Nov 2026)

Kept locally only. Two takes pass through the real home screen: never use those ranges.

| Take | Shows | Safe range |
|---|---|---|
| `lock` | Lock-screen view: New, then Maya's stack opened | 0–14 s |
| `panel` | Panel overview, Maya's stack opened, parcel stack opened, running pill | 1.6–40 s (**home screen before 1.5 s**) |
| `arrive` | A message brings Hiking crew back to the top of New | all |
| `check` | Panel reopened after a check: everything in Earlier | 4.0–10 s (**home screen 2.0–3.5 s**) |
| `preview` | Press and hold: Preview, then the photo viewer with zoom | all |
| `player` | Pause, play, dragging the seek bar | all |
| `swipe` | One message swiped out of a stack, then a card | all (a bare "Daybook" card appears after the card swipe; see below) |
| `running` | The running pill opening | all |
| `clear` | Clear sweeps Earlier; the player and Running stay | all |

Swiping a card can leave an empty card titled with the app's name: Android 16 keeps the
automatic group summary after its child goes, and Chronotif shows it.

Driving the panel over adb: a swipe up closes the panel once its list is short, so don't scroll with
`input swipe` unless the list is long. Screenshot only after checking that the panel or lock view is
the window in front, or a missed step captures the home screen.

## Music

All tracks are by HoliznaCC0 on the Free Music Archive, released under CC0 1.0 (public domain; no
credit needed, use anywhere). The licence was checked on each track's own page (2026-11-18 and
2026-11-19). Use only CC0 music, so nothing needs crediting, and only tracks the page tags
Instrumental. The files are kept in `raw/music/`, not in git. Each video uses a different track.

| Video | Track | Source |
|---|---|---|
| Trailer | Tranquil Mindscape | freemusicarchive.org/music/holiznacc0/public-domain-lofi/tranquil-mindscape-lofi-happy-reflection/ |
| Features | Phonk Remix | freemusicarchive.org/music/holiznacc0/phonk-aura-farming/phonk-remix/ |
| Search | Pantheon | freemusicarchive.org/music/holiznacc0/phonk-aura-farming/pantheon/ |
| 0.2.7, part 1 | Re Adusjtment (FMA's spelling) | freemusicarchive.org/music/holiznacc0/phonk-aura-farming/re-adusjtment/ |
| 0.2.7, part 2 | Phonk ish | freemusicarchive.org/music/holiznacc0/phonk-aura-farming/phonk-ish/ |

Trailer: `node trailer/render.mjs raw/trailer-silent.mp4 && ./mux.sh raw/trailer-silent.mp4 raw/music/tranquil.mp3 out/chronotif-trailer.mp4`.
Features: `MUSIC=raw/music/phonk-remix.mp3 MUSIC_START=28.75 ./edit.sh features`.
Search: see `search.md`. 0.2.7: see `whatsnew.md` and `lock.md`; parts 3 to 6 have no music.

## On the website

The site plays smaller copies (720×1280, about 1.5–2.5 MB each) kept in git at
`../public/video/`, each with a WebP poster:

| File | From | Poster at | Where |
|---|---|---|---|
| `vs-stock.mp4` | `out/chronotif-vs-stock-br-drift.mp4` ([trailer-vs.md](trailer-vs.md)) | 17 s | Homepage |
| `release-0.2.5.mp4` | `out/chronotif-features.mp4` | 12 s | /releases, 0.2.5 |
| `release-0.2.6.mp4` | `out/chronotif-search.mp4` | 4 s | /releases, 0.2.6 |
| `release-0.2.7-1.mp4` to `-6.mp4` | `out/chronotif-0.2.7-N-silent.mp4` | 1 s (title card) | /releases, 0.2.7 (one player, a list of parts) |

A release's video is named after its version, and its entry in `../src/releases.json` gives the
music credit shown under it. A release told in parts lists them under `videos` (`release-<version>-<n>.mp4`),
which the page plays in one player with a list to switch parts; 0.2.7's parts are silent. The release copies carry the same CC0 tracks as `out/`; rebuild
them whenever a video is re-scored, so the site and its credits never fall behind. After re-rendering a video, rebuild its copy and poster:

```sh
ffmpeg -i out/chronotif-vs-stock-br-drift.mp4 -vf scale=720:1280 -c:v libx264 -preset slow -crf 26 \
  -pix_fmt yuv420p -movflags +faststart -c:a aac -b:a 96k ../public/video/vs-stock.mp4
ffmpeg -ss 17 -i out/chronotif-vs-stock-br-drift.mp4 -frames:v 1 -c:v libwebp -quality 80 \
  -vf scale=720:-1 ../public/video/vs-stock-poster.webp
```
