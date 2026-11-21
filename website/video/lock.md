# What's new in 0.2.7, part 2: the lock screen

`out/chronotif-0.2.7-2-silent.mp4`: about 55 seconds, vertical 1080×1920, silent; a version with
music follows once its track is chosen. Part 2 of the 0.2.7 series covers what 0.2.7 changed on the
lock-screen view: the Pile and the big clock, the album art, the clock and the date, and
double-tap to sleep (release-note lines 16, 17, 19 and 20). Built from `trailer/lock.html` by
`trailer/render.mjs --page lock.html`.

It's motion graphics, like part 1. The lock-screen view is drawn in dp to match `LockActivity.kt`
(date 18 sp, clock 76 sp, Tap to unlock), `LockPile.kt` (slivers inset 14 and 30 dp, the count under
them) and `MediaPlayerView.kt` (the 64 dp thumbnail the artwork shrinks into). Settings → Lock screen
is a Robolectric render of the real page, `trailer/shots/settings-lock.png`, with the Double-tap to
sleep switch drawn over it so it can flip. The apps, people and notifications are the same made-up
ones as the other videos; the album "Night Drive" by "Lumen Coast", the alarms and the calendar are
made up too.

The dashed "Display sleeps in …" label is the video's own note, not something Chronotif shows: the
real wait is Android's shortest screen timeout (6 s on HyperOS), shortened here.

Music: "Phonk ish" by HoliznaCC0 (CC0, Instrumental). `./mux.sh out/chronotif-0.2.7-2-silent.mp4 raw/music/phonk-ish.mp3 out/chronotif-0.2.7-2.mp4 13.7` starts it at 13.7 s, so its drop (16 s in) lands as the title card gives way.

Render: `node trailer/render.mjs raw/part2-silent.mp4 --page lock.html && cp raw/part2-silent.mp4 out/chronotif-0.2.7-2-silent.mp4`.

## Chapters

| Time | Chapter | Caption | What it shows |
|---|---|---|---|
| 0:00 | (title) | **Chronotif**. What's new in 0.2.7, part 2: the lock screen. | Title card |
| 0:02 | Room for the time | The clock stays big. New waits at the bottom, as a pile. | The phone wakes: the clock, Maya's card as the Pile, two slivers and "2 more" |
| 0:07 | See everything | Tap the pile or drag it up. Back puts it away again. | A tap unfolds it, Back folds it; then a drag up, the clock pushed away, and Back again |
| 0:15 | Music wakes it | Press play and the lock screen comes up on its own: the art large, over a blur of it. | The screen off, then Chronotif's lock screen with the artwork, the player and the Pile |
| 0:21 | Into the player | Drag up and the art shrinks into the player as the list opens. | The artwork shrinking into the player's thumbnail as the player rises to the top |
| 0:26 | Clock and date | Tap the clock for your alarms, the date for your calendar. Both ask you to unlock first. | Clock tapped, the fingerprint lights, Alarms; date tapped, the calendar at today |
| 0:33 | Double-tap to sleep | Double-tap empty space and the screen goes black at once. The phone sleeps a few seconds later. | A double tap in the gap; black, a countdown note, then "Display off" |
| 0:39 | Changed your mind? | Double-tap the black screen before it sleeps, and it's all back. | Black, then a second double tap brings the view back |
| 0:44 | Your call | Turn it off under Settings → Lock screen → Double-tap to sleep. | The real Lock screen page; the switch flips off |
| 0:49 | Help shape it | Ideas or bugs? Tell us. | End card |

## Not in the video

- Replying, buttons that don't open the app, Running and Search on the lock screen, Hide sensitive
  content, and the new-install default (*Whenever there are notifications*, the "N earlier" line):
  part 3.
- With *Double-tap to sleep* off, a single tap asks to unlock at once (no wait to see): only named
  in the Settings page's description.
