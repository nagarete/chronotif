# Trailer

`out/chronotif-trailer.mp4`: 31 seconds, vertical 1080×1920, motion graphics with no phone
footage. Built from `trailer/trailer.html` by `trailer/render.mjs`.

Music: "Tranquil Mindscape" by HoliznaCC0, from the album
[Public Domain Lofi](https://freemusicarchive.org/music/holiznacc0/public-domain-lofi)
(CC0 1.0, no credit required).

All the apps and people in it are made up: Murmur (chat), Daybook (calendar), Fernbank (bank),
Parcelly (deliveries), Maya Chen, Emma Wilson and the Hiking crew group.

## Scenes

| Time | Caption | What it shows |
|---|---|---|
| 0:00–0:03 | Notifications pile up. | Six cards fall in and land in a messy heap. |
| 0:03–0:06 | Chronotif puts them in order. *Newest first.* | The heap straightens into one list, newest at the top. |
| 0:06–0:10 | What's *new* stays in front. The rest steps back. | The list splits at a "LAST CHECK" line. The two newest cards stay full size under **New**. The older ones shrink and dim under **Earlier**. |
| 0:10–0:13 | A chat stacks up, newest on top. | Three messages from Maya Chen arrive one at a time and stack, the newest on top, with a count badge. The stack joins New. |
| 0:14–0:16 | A burst from one app folds into a stack. | In Earlier, three Parcelly updates that arrived close together fold into one stack. The list stays in time order. |
| 0:17–0:20 | On the lock screen, too. | The view zooms out into a phone, and a lock-screen clock (9:41) fades in above the same list. |
| 0:20–0:23 | Your notifications never leave your phone. *No accounts. No analytics. No backend.* | The phone blurs and dims behind the words. |
| 0:23–0:27 | Free and *open source.* *Read every line. Change it. Build it yourself.* | Real code from `core/Attention.kt`, revealed line by line: the `sections()` function that sorts newest first and puts each notification in New or Earlier, with the `item.alertTime > lastCheck` test highlighted. Below it: `github.com/nagarete/chronotif`. |
| 0:27–0:31 | (end card) | The logo builds itself, then "Chronotif", "Free and open source, for Android", `chronotif.com` and `github.com/nagarete/chronotif`. |

## What it gets across

1. Newest first: the core rule.
2. New and Earlier, split at your last check.
3. Conversation stacks.
4. App stacks in Earlier, without breaking time order.
5. The lock-screen view.
6. Privacy: nothing leaves the phone.
7. Open source, with the repository address.

The trailer follows the same ordering rules as the app (`docs/notification-behavior.md`). If the
app's behaviour changes, update the animation to match.
