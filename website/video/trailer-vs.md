# Trailer: stock Android vs Chronotif

`out/chronotif-vs-stock.mp4`: 40 seconds, vertical 1080×1920, motion graphics with no phone
footage. Built from `trailer/vs.html` by `trailer/render.mjs --page vs.html`. The first trailer
shows what Chronotif does; this one starts from what's wrong with the stock shade and then puts the
two side by side.

Music: "Tranquil Mindscape" by HoliznaCC0 (CC0 1.0), the same track as the first trailer.

All the apps and people are made up: Murmur (chat), Daybook (calendar), Fernbank (bank), Parcelly
(deliveries), Glimmer (photos), Maya Chen, Emma Wilson, Leo Park and the Hiking crew group. The
stock shade is a generic Android layout: an ongoing backup on top, Conversations, alerting
notifications ranked by importance with one app's updates bundled, then Silent.

## Scenes

| Time | Caption | What it shows |
|---|---|---|
| 0:00–0:03 | You pick up your phone. *And try to work out what happened.* | The stock shade on its own. |
| 0:03–0:07 | Old things sit on top. *A like from 2 hours ago, above a reminder from just now.* | Glimmer (2h) and Daybook (now) ringed, then every timestamp lights up out of order. |
| 0:07–0:11 | What's new since you last looked? *Nothing tells you. So you read it all again.* | A question mark pops onto every card. |
| 0:11–0:15 | So you clear all… *…and lose what you still needed.* | Clear all sweeps everything but the backup, then it rewinds. |
| 0:15–0:19 | Same notifications. *Side by side.* | Stock shrinks left, Chronotif enters right. ✕ Sorted by app and importance / ✓ Newest first. |
| 0:19–0:23 | Something new arrives. | A parcel update: stock folds it into the Parcelly bundle mid-list; Chronotif puts it on top of New. |
| 0:23–0:26 | You look, then put the phone down. | Chronotif moves everything to Earlier and dims it; stock doesn't change. |
| 0:26–0:30 | Next time, what's new is obvious. | A Hiking crew message: on stock it looks like every other card; on Chronotif it's alone under New. |
| 0:30–0:35 | Stock Android vs Chronotif | A table: order, what's new, what you've seen, always-running items. |
| 0:35–0:40 | (end card) | Logo, "Chronotif", "Free and open source, for Android", `chronotif.com`, the repository. |

The Chronotif side follows `docs/notification-behavior.md`: newest first, a returning chat goes back
to the top of New, and App stacks only in Earlier. If the app's behaviour changes, update it.

Render: `node trailer/render.mjs raw/vs-silent.mp4 --page vs.html && ./mux.sh raw/vs-silent.mp4 raw/music/tranquil.mp3 out/chronotif-vs-stock.mp4`.
