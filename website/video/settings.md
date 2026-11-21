# What's new in 0.2.7, parts 5 and 6: Settings, the Tutorial and Usage data

Two short videos, vertical 1080×1920, built like the other 0.2.7 parts (`trailer/render.mjs --page …`):

- Part 5, `trailer/settings.html`: Settings. About 65 seconds. Release-note lines 7, 8, 9, 11, 15 and 22.
- Part 6, `trailer/tutorial.html`: the Tutorial and Usage data. About 39 seconds. Lines 23 and 25.

Unlike parts 1 and 2, the phone screens are the app's own. Robolectric rendered them at 412×883 dp
(2 px per dp, dark theme, permissions granted, the clock at 9:41 on Friday 20 November) from a
throwaway test that isn't committed (`VideoShots5Test`, in the style of the screenshot tests: it opens
`SetupActivity`, picks sections, types into the settings search, taps Not now, and runs the
Tutorial's Panel and lock-screen stand-in). They're in `trailer/shots/s-*.jpg`. Tall renders
(2600–4000 dp high) are scrolled under a fixed copy of the search bar. Drawn over them are taps, the
drawer's slide, the flash on a picked result, highlights, and a few things the render can't show:

- the drawer's header and Privacy and about's version say "Chronotif" and "0.2.7", not the debug build's name;
- the microphone in the settings search and the voice-input sheet (Robolectric has no speech service);
- the Pile's "2 more" on the Tutorial's lock-screen stand-in (Robolectric drew it as a blank pill);
- Usage data's switch turned off.

"In your colours" stands in for three wallpapers by turning the hue of the render and of a home-screen
wallpaper together. On a phone the colours come from the wallpaper's tonal palettes (`M3.kt`), so the
shades differ from a plain hue turn, but the idea is the same.

Music: none, for either part. No CC0 instrumental phonk is left that another video doesn't use.

Render: `node trailer/render.mjs raw/part5-silent.mp4 --page settings.html` and
`node trailer/render.mjs raw/part6-silent.mp4 --page tutorial.html`, then copy them to
`out/chronotif-0.2.7-5-silent.mp4` and `out/chronotif-0.2.7-6-silent.mp4`.

## Part 5 chapters

| Time | Chapter | Caption | What it shows | Note |
|---|---|---|---|---|
| 0:00 | (title) | What's new in 0.2.7, part 5: settings. | Title card | |
| 0:02 | A screen per section | Each section gets the whole screen. The menu slides out a list of them. | The menu opens the drawer over General; Edge handle is picked | 7 |
| 0:08 | Back where you left it | Close Settings and open them again: they reopen on the same page. | Settings go home; the icon reopens them on Edge handle | 7 |
| 0:13 | General, first | Chronotif's on switch at the top, then a checklist with a button for each step left. | The switch and "2 steps left" light up; both Allow buttons; "All set" | 8 |
| 0:19 | Light or dark | As your phone is, or as you pick under Settings theme. | General scrolls to Settings theme; Light is picked | 8 |
| 0:24 | In your colours | Settings take your wallpaper's colours: rounded groups, choices side by side. | Three wallpapers in turn, and Edge handle's page in each one's colours | 8 |
| 0:30 | Search settings | Results change with each letter, show where a setting lives and what it's set to. | "colour" typed letter by letter; When something is new is picked and its row flashes on Edge handle's page | 9 |
| 0:38 | Or say it | The microphone hands the search to your phone's voice input. | The mic, the voice sheet hearing "double tap", the results | 9 |
| 0:43 | Tips | A Tips page lists the gestures you can't see, Search's tip among them. | Tips scrolls; the Search tip lights up | 11, 15 |
| 0:48 | Set up Search | Settings → Search starts with a checklist: allow a step, say Not now, or add the widget and tile. | 4 steps left; Not now on Find contacts makes it 3; the widget's and tile's Add | 22 |
| 0:54 | What Search can do | Below its settings, the list of what Search can do, with examples to try. | Scrolls down to What Search can do | 15 |
| 0:59 | Help shape it | Ideas or bugs? Tell us. | End card | |

## Part 6 chapters

| Time | Chapter | Caption | What it shows | Note |
|---|---|---|---|---|
| 0:00 | (title) | What's new in 0.2.7, part 6: the Tutorial and Usage data. | Title card | |
| 0:02 | Tutorial | Try Chronotif hands-on under Settings → Tutorial, in three parts. | Notifications, Search and Lock screen light up in turn; Start | 23 |
| 0:08 | The real panel | Made-up notifications, one step at a time. Your own stay hidden and untouched. | Step 1's card, a swipe on the edge handle, the Panel with the samples and "✓ That's it" | 23 |
| 0:15 | And the lock screen | Practise the pile, replying while locked and double-tap to sleep, on a stand-in. | The lock-screen stand-in with the Pile and step 1's card | 23 |
| 0:21 | Usage data | Chronotif now sends anonymous Usage data. Never anything from your notifications. | General's Usage data notice, its Turn off and OK | 25 |
| 0:27 | Your call | Turn it off any time in Privacy and about. What's sent lists every field. | The Usage data switch turned off; What's sent lights up | 25 |
| 0:33 | Help shape it | Ideas or bugs? Tell us. | End card | |

## Not in the videos

- The Tutorial's Search part is only named, not shown.
- The step in the Tutorial's Panel is shown done rather than played step by step.
