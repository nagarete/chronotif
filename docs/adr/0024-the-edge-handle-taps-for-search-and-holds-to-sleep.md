# The edge handle taps for Search and holds to sleep

Supersedes ADR 0022 in part: its defaults.

ADR 0022 kept the old map as the default, so nobody's handle changed: tap, swipe down and swipe inward opened the Panel, and hold opened Search. Using the gestures, the user settled on a different map and asked for it as the default: tap and swipe inward open Search, swipe down opens the Panel, hold is Sleep, and double-tap and swipe up do nothing. The Panel already has the status-bar swipe and the Quick Settings tile, and a swipe down on the handle reads as pulling the Panel down, so tap is free for Search, which has no other way in from inside an app. Hold suits Sleep: it's deliberate, and the buzz before the screen goes off says what's coming.

New installs get the new map. An install from before 0.3.3 keeps the map it had, written into each gesture it never set, as ADR 0015 did for the lock screen and currency rates, so nobody's handle changes under their finger.

## Considered Options

- **The new map for everyone who never picked**: one map to describe, but an update would turn a tap that opened the Panel into one that opens Search, with no word of it.
- **Keep the old defaults**: nothing changes, but every new user would start from a map the maintainer doesn't use.

## Consequences

- Docs and the website describe the new map; the release notes say existing installs keep theirs and where to change it.
- The tutorial's steps follow whichever gesture is set, so they say *Swipe down on the edge handle* for the Panel and *Tap the edge handle* for Search on a new install.
