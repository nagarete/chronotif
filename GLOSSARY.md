# Chronotif

Chronotif separates notifications that arrived since the last check from those already checked.

## Language

**Panel**:
Chronotif's notification screen, opened on demand on top of whatever is in use. Wherever Android allows, what's underneath keeps running, as it does under the system shade.
_Avoid_: Shade (the system's), drawer

**New**:
Notifications that brought new activity since the user's last meaningful check. For a conversation, only its messages that arrived since that check are New.

**Earlier**:
Still-active notifications the user has already checked, folded into stacks: one per conversation, otherwise one per app. A conversation can sit in New and Earlier at once, split by message.

**Conversation stack**:
A conversation shown as one card per message, collapsed to its latest message with a count until opened.

**Conversation history**:
The messages Android currently supplies for a conversation, including messages already checked. It is distinct from the Earlier section.

**Avatar**:
The picture of the person or group a conversation is with, shown in place of the app icon (which shrinks to a badge on it). Only conversations have one, and it is never shown when content is hidden.
_Avoid_: Large icon, profile pic

**Attachment**:
An image, or a video's still frame, sent inside a message. Its card shows it as a thumbnail; the Preview shows it large, and from there it opens full screen and can be saved without opening the app.
_Avoid_: Picture (a non-conversation notification's single image, which opens the same way)

**Preview**:
One card held up on its own over a dimmed screen by pressing and holding it: its full text, its Attachment large, and the app's buttons. Nothing in the app is opened or marked read.
_Avoid_: Peek, long-press menu

**Wake session**:
One continuous period from the display waking until it sleeps. Chronotif and the stock lock screen share this period.

**Off**:
Chronotif's standing surfaces (the edge handle, the status-bar swipe and the lock-screen view) are withheld. Notifications are still tracked and the panel can still be opened on purpose, e.g. from the Quick Settings tile. The user's surface settings are kept for when Chronotif is turned back on.
_Avoid_: Disabled, Paused

**Media player**:
A playback control for an app that is playing or paused, shown above everything else. It stays while paused, until the app removes it, and never counts as New.
_Avoid_: Now playing card

**Running**:
Notifications for work that continues in the background (always-running services, connected devices, step counters, downloads, navigation, timers). They sit collapsed below Earlier and never count as New. Ongoing calls are not Running; they stay at the top with Media players.
_Avoid_: Ongoing, Background

**Status-bar swipe**:
Opening the panel by swiping down on, or tapping, the notification side of the status bar, in place of the system shade.
_Avoid_: Top swipe, top-edge gesture
