# Search video

`out/chronotif-search.mp4`: 64 seconds, vertical 1080×1920. It covers what came after the features
video: Search (#55–#59) and the settings gear in the panel header (#54). Built from
`trailer/search.html` by `trailer/render.mjs --page search.html`, then `mux.sh` adds the music.

It's motion graphics, not phone footage. Filming Search on a real phone would show its installed
apps, contacts and calendar, because demo mode only hides notifications. The phone screen is
drawn in dp to match `SearchOverlay.kt` and the settings screen, then scaled to the same phone
frame and caption style as the features video. The apps, people, numbers and events are all made
up: Murmur, Daybook, Fernbank, Parcelly, Glimmer, Hum Music, Emma Wilson, Emeka Obi, and a weekly
"Design review".

Music: "Brazilian Phonk Bounce for Dance Reels" by Alex Morgan, from the album
[Phonk & Drift](https://freemusicarchive.org/music/alex-morgan/phonk-drift) (CC BY 4.0). The end card
credits it, as the licence requires. The track page says it's AI-generated.

Render: `node trailer/render.mjs raw/search-silent.mp4 --page search.html && ./mux.sh raw/search-silent.mp4 raw/music/am-bounce.mp3 out/chronotif-search.mp4 1.25`.
The track starts at 1.25 s, so its first drop (3.5 s into the track) lands as the title card gives way.

## Chapters

| Time | Chapter | Caption | What it shows |
|---|---|---|---|
| 0:00 | (title) | **Search**. New in Chronotif: apps, answers, people and plans, from the panel. | Title card |
| 0:02 | Pull down for Search | Pull down on the panel. The apps that just notified are right there. | The panel pulled down, Search opening with Suggestions (Murmur, Daybook, Fernbank, Parcelly, the panel's apps, newest first) |
| 0:08 | Apps | Type a few letters to open any app. | `mu` finds Murmur, then Hum Music (a later word); `mur` leaves Murmur |
| 0:11 | Answers | Quick math, units and the time anywhere. Tap to copy. | `12*7.5+3` = 93, `5 km in mi`, `time in tokyo`, then a tap on the card |
| 0:19 | Currencies | The European Central Bank's daily rates. Off until you tap to turn them on. | `100 usd`: the card offers to turn rates on, a tap, then 86.12 EUR with the rate's date |
| 0:25 | Off until you say so | Contacts and calendar wait behind the new gear in the panel. | The gear opens Settings; *Find contacts* and *Find calendar events* each ask for Android's permission |
| 0:32 | Contacts | Find someone, then message or call them in one tap. | `em` finds Emma Wilson (WhatsApp) and Emeka Obi, with message and call buttons |
| 0:36 | Calendar | Your events too. A weekly one shows once, at its next date. | `design` finds Design review and Design sprint demo |
| 0:40 | Lock screen | Pull down on the lock screen too. Contacts and events stay hidden until you unlock. | `emma` on the lock-screen view: only *Unlock to see all results* and the web row |
| 0:46 | Settings and the web | Jump to a Settings page, or hand the rest to your browser. | `wifi` finds Wi-Fi; `chronotif.com` becomes "Open chronotif.com" |
| 0:51 | More ways in | A home-screen widget, a Quick Settings tile, the app icon, or a long press on the edge handle. | The Search widget on a home screen opening Search |
| 0:55 | Private | Nothing you type is sent. Contacts and events are read on your phone and never stored. | Card |
| 0:58 | Help shape it | Ideas or bugs? Tell us. `github.com/nagarete/chronotif/issues`, `chronotif.com`, music credit | End card |

The made-up exchange rate (86.12 EUR for 100 USD) and the times are for the picture only.
If Search's layout or rules change (`docs/notification-behavior.md`, Search), update the page and
this table.

## Not in the video

- Currency without a target picks the phone's country by its mobile network; the video assumes a
  euro country.
- *Call from Search* (WhatsApp calls, the video-call button) and *Show more* for contacts.
- The Quick Settings tile, the app-icon shortcut and the edge handle are named, not shown.
