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
5. `./edit.sh` cuts `out/chronotif-promo.mp4` (add `MUSIC=track.mp3` for sound). Then `./scenes.sh restore` and uninstall the demo apps.
