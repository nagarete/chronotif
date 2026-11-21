# What's new in 0.2.7, part 1: the panel and the edge handle

`out/chronotif-0.2.7-1-silent.mp4`: about 58 seconds, vertical 1080×1920, silent. It shows only what
0.2.7 changed in the panel and the edge handle (release-note lines 1–7). The other parts cover the
rest of 0.2.7; the features video covers what came before. Built from `trailer/whatsnew.html` by
`trailer/render.mjs --page whatsnew.html`.

It's motion graphics, like the Search video. The phone screen is drawn in dp to match `Panel.kt`
and `PanelView.kt` (a pull fades the list in over 96 dp, and the blur comes in once the panel
settles), `EdgeHandle.kt` (shapes, sizes, styles and colours) and Settings → Edge handle as
`SetupActivity.kt` draws it in 0.2.7, measured from a Robolectric render of the real page. The apps
and notifications are the same made-up ones as the other videos.

Music: "Re Adusjtment" by HoliznaCC0 (CC0, Instrumental). `./mux.sh out/chronotif-0.2.7-1-silent.mp4 raw/music/phonk-readjustment.mp3 out/chronotif-0.2.7-1.mp4 9.4` starts it at 9.4 s, so its drop lands as the title card gives way.

Render: `node trailer/render.mjs raw/part1-silent.mp4 --page whatsnew.html && cp raw/part1-silent.mp4 out/chronotif-0.2.7-1-silent.mp4`.

## Chapters

| Time | Chapter | Caption | What it shows |
|---|---|---|---|
| 0:00 | (title) | **Chronotif**. What's new in 0.2.7, part 1: the panel and the edge handle. | Title card |
| 0:02 | Follows your finger | Swipe down on the status bar. The panel comes down with you. | A pull from the left half of the status bar, slowing and carrying on, then settling open |
| 0:08 | Changed your mind? | Let go early and it slides back. It doesn't count as a check: New stays New. | A short pull that slides back, then a full one: New still holds its cards |
| 0:14 | Opens at once | The header is there at once, your notifications right after. Reopening is quicker still. | A tap on the handle: header first, cards fill in; closed and reopened, everything is already there |
| 0:19 | Straight back in | Closed it too soon? Swipe again right away: the panel opens, not the system shade. | A tap on empty space closes the panel; a pull from the status bar straight after opens it again |
| 0:24 | Easier to hit | A half circle the size of a fingertip, and all of it responds. | Close-up: the old thin line becomes the half circle, a finger covers it |
| 0:30 | Move it | Hold it until it buzzes, then drag it to either side, any height. | Hold, buzz, drag to the left edge lower down |
| 0:36 | Hold for Search | Hold it without moving and let go: Search opens. | Hold, buzz, let go in place: Search with the latest apps and the keyboard |
| 0:41 | Make it yours | Under Settings → Edge handle: a shape, a style, a colour. Or no light at all. | Tab, Circle, Bold, green when New, then Don't change: the live handle stays grey |
| 0:49 | Steps aside | In fullscreen apps the handle steps aside, and comes back after. | A Glimmer photo going full screen |
| 0:52 | Help shape it | Ideas or bugs? Tell us. | End card |

## Not in the video

- The handle's side, usual colour, position slider and Hide in landscape are on screen in Settings
  but not tapped; they aren't new in 0.2.7.
- Settings in sections, the drawer, the new look and the Tips page: part 5.
