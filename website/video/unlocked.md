# What's new in 0.2.7, part 3: without unlocking

`out/chronotif-0.2.7-3.mp4` (with music) and `out/chronotif-0.2.7-3-silent.mp4`: 56 seconds, vertical
1080×1920. It covers what 0.2.7 lets you do on the lock-screen view without unlocking, and the new
installs' defaults. Built from `trailer/unlocked.html` by `trailer/render.mjs --page unlocked.html`.

It's motion graphics, like parts 1 and 2. The lock-screen view, the Preview and the keyboard are
copied from `trailer/lock.html` (`LockActivity.kt`, `LockPile.kt`, `CardPreview.kt`); Search follows
`trailer/search.html` (`SearchOverlay.kt`); Settings → Lock screen follows SetupActivity's dark
Material 3 page, laid out from a Robolectric render of the real screen. The apps, people and
notifications are the same made-up ones as the other videos; Stride, the step counter, is made up too.

Music: none. No CC0 instrumental phonk is left that another video doesn't use, and the silent cut is the one to post.

Render: `node trailer/render.mjs raw/part3-silent.mp4 --page unlocked.html && cp raw/part3-silent.mp4 out/chronotif-0.2.7-3-silent.mp4`.

## Chapters

| Time | Chapter | Caption | What it shows |
|---|---|---|---|
| 0:00 | (title) | **Chronotif**. What's new in 0.2.7, part 3: without unlocking. | Title card |
| 0:02 | Reply, still locked | Hold a card and reply from its Preview, without unlocking. | Hold Maya's card, Reply, type, send; "Sent to Maya Chen" |
| 0:11 | Its buttons too | An app's buttons that don't open it, like Mark as read, just work. | Emeka's message on the Pile, Mark as read from its Preview; Maya's card is on top again |
| 0:17 | Running | What's running sits below Earlier, as in the panel. | The list dragged open, "1 running" tapped, Stride's walk under Running |
| 0:23 | Search, still locked | Pull down to find contacts and events. Opening an app still asks you to unlock. | `em` finds Emma and Emeka; Emma's message button asks to unlock to open Murmur; `design` finds two events |
| 0:31 | Hide sensitive content | Turn it on and contacts and events wait behind the unlock. | Settings → Lock screen, the switch turned on; `em` now shows "Unlock to see all results" |
| 0:38 | New installs | Chronotif shows whenever there are notifications. Updating keeps your settings. | Show on lock screen set to Whenever there are notifications; a wake with nothing New: the clock and "2 earlier", tapped to open Earlier |
| 0:45 | Currencies, at once | New installs convert currencies without asking first. | `100 usd` gives 86.12 EUR at once, with the rate's date |
| 0:50 | Help shape it | Ideas or bugs? Tell us. | End card |

## Not in the video

- Apps that mark a reply or button as needing an unlock (the Preview then asks to unlock first).
- "Unlock to see all results" tapped and the full results after the unlock.
