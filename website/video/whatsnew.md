# What's new in 0.2.7

`out/chronotif-0.2.7.mp4`: about 59 seconds, vertical 1080×1920. It shows only what changed in
0.2.7 (#62–#65, #68). The features video already covers what came before. Built from
`trailer/whatsnew.html` by `trailer/render.mjs --page whatsnew.html`, then `mux.sh` adds the music.

It's motion graphics, like the Search video. No take recorded the pull, the edge handle or the new
settings, and filming them would need the full demo-mode setup on the phone. The phone screen is
drawn in dp to match `Panel.kt` and `PanelView.kt` (a pull fades the list in over 96 dp, and the
blur comes in once the panel settles), `EdgeHandle.kt` (shapes, sizes, styles and colours) and
`SetupActivity.kt` (the rail, the expanded sidebar, the Edge handle and Tips pages). The apps and
notifications are the same made-up ones as the other videos.

Music: "Re Adusjtment" (FMA's spelling) by HoliznaCC0, from the album
[PHONK - AURA FARMING](https://freemusicarchive.org/music/holiznacc0/phonk-aura-farming) (CC0 1.0, no
credit required; tagged Instrumental).

Render: `node trailer/render.mjs raw/whatsnew-silent.mp4 --page whatsnew.html && ./mux.sh raw/whatsnew-silent.mp4 raw/music/phonk-readjustment.mp3 out/chronotif-0.2.7.mp4 9.4`.
The track starts at 9.4 s, so its drop (12 s into the track) lands as the title card gives way.
The site plays a 720p copy, `../public/video/release-0.2.7.mp4`, on chronotif.com/releases under 0.2.7.

## Chapters

| Time | Chapter | Caption | What it shows |
|---|---|---|---|
| 0:00 | (title) | **Chronotif**. What's new in 0.2.7. | Title card |
| 0:02 | Follows your finger | Swipe down on the status bar. The panel comes down with you. | A pull from the left half of the status bar, slowing and carrying on, then settling open |
| 0:08 | Changed your mind? | Let go early and it slides back. It doesn't count as a check. | A short pull that slides back, then a full one: New still holds its cards |
| 0:14 | Opens at once | The header is there at once, your notifications right after. Reopening is quicker still. | A tap on the handle: header first, cards fill in; closed and reopened, everything is already there |
| 0:19 | Easier to hit | A half circle the size of a fingertip, and all of it responds. | Close-up: the old thin line becomes the half circle, a finger covers it |
| 0:25 | Move it | Hold it until it buzzes, then drag it to either side, any height. | Hold, buzz, drag to the left edge lower down |
| 0:31 | Make it yours | A shape, a style and colours, under Settings → Edge handle. | Tab, Circle, Large half circle, With count, green when New; the live handle changes with each pick |
| 0:38 | Steps aside | In fullscreen apps the handle steps aside, and comes back after. | A Glimmer photo going full screen |
| 0:42 | Settings, in sections | Each section with a summary, and choices side by side. | The menu opens the sidebar of sections; Tips is picked |
| 0:48 | Tips | A Tips page lists the gestures you can't see. | The Tips page scrolling |
| 0:53 | Help shape it | Ideas or bugs? Tell us. | End card |

## Not in the video

- Swiping the status bar right after closing no longer opens the system shade (a fix, nothing to see).
- The handle's side, usual colour and position slider, and "Hide in landscape".
- Settings reopening on the last section.
