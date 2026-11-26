# Chronotif

Chronotif separates notifications that arrived since the last check from those already checked.

## Language

**Panel**:
Chronotif's notification screen, opened on demand on top of whatever is in use. Wherever Android allows, what's underneath keeps running, as it does under the system shade.
_Avoid_: Shade (the system's), drawer

**New**:
Notifications that brought new activity since the user's last meaningful check. For a conversation, only its messages that arrived since that check are New. A conversation whose New messages were all swiped away isn't New, even if checked messages remain.

**Earlier**:
Still-active notifications the user has already checked, receded but still newest first. A conversation folds into a Conversation stack, and one app's neighbouring cards fold into an App stack. A conversation can sit in New and Earlier at once, split by message.

**Conversation stack**:
A conversation shown as one card per message, collapsed to its latest message with a count until opened.

**App stack**:
One app's cards that sit next to each other in Earlier and arrived within 30 minutes of the newest of them, collapsed to that newest card until opened. Opening it shows its cards in the same place, so it never puts an old card above a newer one from another app. A Conversation stack with several messages never joins one.
_Avoid_: Group, per-app stack (the old design that gathered all of an app's cards)

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

**Snooze**:
Hiding a notification for a chosen time, from its Preview. Android hides it everywhere and posts it again when the time is up; it then counts as New.
_Avoid_: Remind me, later

**Wake session**:
One continuous period from the display waking until it sleeps. Chronotif and the stock lock screen share this period.

**Double-tap to sleep**:
Double-tapping empty space on the lock-screen view puts the display to sleep, ending the Wake session, as double-tapping the stock lock screen does on phones that offer it. Like the display timing out, it is not a check.
_Avoid_: Double-tap to lock (the phone is already locked), screen off

**Lock-screen shortcut**:
One of two buttons in the bottom corners of Chronotif's lock-screen view, each set by the user to the flashlight, the camera, the QR scanner, Do Not Disturb, an app, or nothing. Holding one uses it; a tap only says to hold. The stock lock screen's own shortcuts are a different thing and are covered while Chronotif's view shows.
_Avoid_: Quick affordance, corner button, quick action

**QR scanner**:
Scanning a code with Google Lens, from a Lock-screen shortcut or the Chronotif QR scanner tile. Chronotif has no scanner of its own, so without Lens it isn't available.
_Avoid_: Code scanner, Scanner (HyperOS's own)

**Off**:
Chronotif's standing surfaces (the edge handle, the status-bar swipe and the lock-screen view) are withheld. Notifications are still tracked and the panel can still be opened on purpose, e.g. from the Quick Settings tile. The user's surface settings are kept for when Chronotif is turned back on.
_Avoid_: Disabled, Paused

**Pile**:
How the lock-screen view holds New at rest: below the clock, the newest card whole with the next ones as slivers behind it, still newest first. Unfolding it lays the same cards out in the same order. Its card can be opened on its own, as it would be in the list, while the rest stays folded. It is a resting state of the view, not a grouping.
_Avoid_: Stack (Conversation and App stacks), Peek, compact view

**Media player**:
A playback control for an app that is playing or paused, shown above everything else and never counted as New. On the lock-screen view at rest, the newest one shows its artwork large above it (ADR 0012). A paused or stopped Media player may be swiped away using Android's normal cancellation for clearable notifications, or the sending app's deleteIntent for protected media. The player stays visible if no real dismissal occurs. Active playback cannot be dismissed.
_Avoid_: Now playing card

**Running**:
Notifications for work that continues in the background (always-running services, connected devices, step counters, downloads, navigation, timers). They sit collapsed below Earlier and never count as New. Ongoing calls are not Running; they stay at the top with Media players.
_Avoid_: Ongoing, Background

**Status-bar swipe**:
Opening the panel by swiping down on, or tapping, the notification side of the status bar, in place of the system shade.
_Avoid_: Top swipe, top-edge gesture

**Edge handle**:
A small handle on the side of the screen, in a shape and style the user picks, that works without the accessibility service. Each Handle gesture does the Handle action the user set for it; holding it and dragging moves it. Unless the user turns it off, it is tinted while anything is New.
_Avoid_: Edge bar, sidebar, handle strip

**Handle gesture**:
One of the ways to touch the edge handle that the user can give an action: tap, double-tap, hold, swipe down, swipe up or swipe inward.
_Avoid_: Trigger, binding, shortcut

**Handle action**:
What a Handle gesture does: open the Panel or Search, Sleep, one of the Lock-screen shortcut's actions, or nothing.
_Avoid_: Shortcut, command, binding

**Sleep**:
The Handle action that turns the display off from whatever is on screen, ending the Wake session. Whether the phone then locks is the phone's own setting. Like the display timing out, it is not a check.
_Avoid_: Lock, screen off

**Search**:
Opened by pulling down at the top of the Panel or the lock-screen view, or from Chronotif's handle, tile, widget or app shortcut. It finds and opens apps, contacts and calendar events, answers quick math and currency, and hands anything else to the web. It never searches notifications.
_Avoid_: Spotlight, App search

**Stable**:
A tested release of Chronotif, tagged `vX.Y.Z`, and the update channel that receives only those. The default channel.
_Avoid_: Production, latest

**Nightly**:
A build of the next Stable while it is still being tested, and the update channel that receives those builds.
_Avoid_: Beta, preview, dev build

**Release notes**:
The plain-language account of what a Stable changed for the people using it, plus one entry for the next Stable while it is in Nightly. Changes that don't reach the app, such as website or build work, are left out.
_Avoid_: Changelog, What's Changed (GitHub's generated list of pull requests)

**Tutorial**:
A hands-on run through Chronotif in the real Panel, with its own place in Settings above Tips, which says the same in writing. It comes in parts by topic, each taken on its own. Each step asks you to try one thing for real on sample cards from made-up people and apps, and shows when it worked. While a part runs, your own notifications are hidden, nothing reaches Android or another app, and nothing counts as a check.
_Avoid_: Tour, Onboarding, Demo (demo mode is the debug build's setup for recording the promo video)

**What's new**:
The sheet over the Panel's cards on its first open after an update, listing the Release notes not shown before. While it shows, the Panel isn't seen, so it counts toward no check.
_Avoid_: Changelog, update dialog

**Usage data**:
Anonymous counts, speeds, settings and crash reports Chronotif sends to improve the app, tied to a random install ID and the country, never to anything from notifications, contacts, calendar or Search. On unless turned off; turning it off sends one last note that it was turned off, then nothing.
_Avoid_: Telemetry, Analytics, Tracking

**Feedback**:
A message a user writes and sends on purpose, from the app or the website, to the maintainer privately. It is never public; the maintainer files an issue from it when it needs work. It is not Usage data: nothing is sent until the user taps Send.
_Avoid_: Bug report, Issue (an issue is public and lives on GitHub)

**Support**:
Paying the maintainer by choice, through Ko-fi, outside the app. It unlocks nothing and the app records nothing about it. Its place is the last section of Settings, which the Support sheet points to.
_Avoid_: Donate, Tip, Premium, Pro (nothing is paid for)

**Support sheet**:
The sheet that now and then slides up over Settings, never over the Panel or the lock screen, asking whether Chronotif is worth supporting and leading to the Support section. Closing it costs nothing.
_Avoid_: Donation popup, Nag, Toast
