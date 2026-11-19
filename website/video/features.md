# Features video

`out/chronotif-features.mp4`: 64 seconds, vertical 1080×1920. It walks through every feature
Chronotif has so far, one chapter each. Built by `./edit.sh features` from `cuts/features.sh`
(segments) and `cuts/features.json` (captions).

The chapters are filmed on a real phone (a Xiaomi 17T Pro) running the debug build in demo mode,
plus one clip of the website's interactive demo. Every notification comes from the made-up demo
apps in `poster/`. None of the phone's real notifications or home screen appear.

Music: "Brazilian Phonk With Heavy Bass Drop" by Alex Morgan, from the album
[Phonk & Drift](https://freemusicarchive.org/music/alex-morgan/phonk-drift) (CC BY 4.0). The end card
credits it, as the licence requires. It replaced "Bubbles" by HoliznaCC0 (CC0) on 20 Nov 2026.
Built with `MUSIC=raw/music/am-heavy-bass.mp3 MUSIC_START=1.25 ./edit.sh features`, so the first drop
lands as the title card gives way.

## Chapters

Each chapter has a small label above its caption.

| Time | Chapter | Caption | Footage |
|---|---|---|---|
| 0:00 | (title) | **Chronotif**. Every feature, in about a minute. | Title card |
| 0:02 | New and Earlier | Everything since you last looked is **New**. The rest steps back to **Earlier**. | Phone: the panel open, split into New and Earlier |
| 0:05 | What counts as a check | Open the panel and close it. What it showed moves to **Earlier**. | Website demo: a check moving cards to Earlier |
| 0:09 | Newest first | A new message lands on top and brings its chat back to **New**. | Phone: a Hiking crew message arrives and the chat jumps to the top |
| 0:13 | Conversation stacks | A chat stacks up. Tap it to read every message. | Phone: Maya Chen's stack opened |
| 0:16 | Preview | Press and hold any card for a Preview, without opening the app. | Phone: press and hold on a card |
| 0:19 | Photos | Tap the photo to see it full screen. Zoom, save or share. | Phone: the photo viewer, zooming in |
| 0:25 | Swipe away | Swipe one message out of a chat. Only that message goes. | Phone: one message swiped out of a stack |
| 0:28 | App stacks | A burst from one app folds into a stack, still in time order. | Phone: the Parcelly stack in Earlier, opened |
| 0:32 | Media | Music stays on top, with play, pause and a seek bar. | Phone: the Hum player paused, played and scrubbed |
| 0:40 | Running | Always-running services fold into one pill. | Phone: the Running pill opening |
| 0:43 | Clear | Clear sweeps **Earlier** away. Music and Running stay. | Phone: Clear tapped |
| 0:46 | Lock screen | When the screen turns on, what's new is up front. No unlocking. | Phone: the lock-screen view, then Maya's stack opened |
| 0:53 | Private | Your notifications never leave your phone. No accounts, no analytics, no backend. | Card |
| 0:56 | Open source | Free, GPL-3.0. Read every line, change it, build it yourself. `github.com/nagarete/chronotif` | Card |
| 1:00 | Help shape it | Ideas or bugs? Tell us. Chronotif gets better from what you report. `github.com/nagarete/chronotif/issues`, `chronotif.com`, music credit | End card |

## Why it ends on feedback

Chronotif has no analytics, so what people report is the only way to learn what to improve. The
end card sends viewers to the GitHub issues page, which has bug-report and feature-request
templates.

## Not in the video

- The empty card titled with an app's name (such as "Daybook") that can appear after swiping a
  card away. Android 16 keeps the automatic group summary after its last child is removed. This
  is a known bug, and the swipe chapter stops before it shows.
- Calls. Chronotif shows ongoing and incoming calls first, but no take recorded one.

Search and the settings gear came later and have their own video (`search.md`).

When a feature is added or changed, add or update its chapter here and in `cuts/features.*`.
