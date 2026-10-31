# Notification wake and conversation behavior

Agreed behavior from the October 2026 bug-report interview. Implemented with automated regression coverage; verification on the reporting HyperOS device is pending.

## Wake session

An untouched notification wake is one continuous session that lasts as long as the stock lock screen would have. Chronotif remains visible until the display sleeps, without an additional interval showing the stock lock screen. Back explicitly reveals the stock lock screen.

The first reported failure was approximately ten seconds of Chronotif followed by approximately ten seconds of the stock lock screen, caused by an inactivity timer that finished the activity. Removing that timer exposed a second failure: a window covering the keyguard gets the normal screen timeout (the one used while an app is open), so the display stayed on for the full screen-timeout setting. Chronotif's window now requests the keyguard's own short timeout (`config_lockScreenDisplayTimeout`, ten seconds when the ROM doesn't define it). Touches extend it as they do on the stock lock screen. The device's display settings are not changed.

## Conversation stacks

Revised in the October 2026 interview comparing Chronotif with iOS (ADR 0002). Each conversation that lists its messages is shown as a Conversation stack: one card per incoming message, under the conversation's title and Avatar, with the message's own time.

Its messages are split at the last check. Those sent since then stack in New, and the ones already checked stack in Earlier, so one conversation can appear in both. At least the latest message is New when the conversation is, because message times come from the sender's clock. There is no limit on how many messages a stack holds, beyond what Android currently supplies.

A stack with one message is a plain card. A stack with more is collapsed: the latest message, a message count on the Avatar, and slivers underneath. Tapping it opens one card per message, newest first, under a header with the conversation's title, Show less and ×.

- Tapping a message card opens the chat.
- Its chevron shows the app's buttons; Reply and Mark as read act on the whole conversation.
- Swiping one message card hides only that message in Chronotif, because Android can't remove one message from a notification. The chat stays in the system shade.
- Swiping a collapsed stack, or tapping ×, clears the whole conversation, which removes its stacks from both sections.

In Earlier, each such conversation has its own stack; other notifications still stack by app. Your own replies aren't cards. Apps that send only the latest text, with no list of messages, can't be split: they stay one card per notification and stack by app. When content is hidden on the lock screen, a conversation is one card with no messages.

This covers messages available in active Android notifications. It does not require an archive of messages Android no longer supplies.

## New incoming messages

A new incoming message returns its conversation to New, including when the app sends the update silently or marks it to alert only once. Timestamp refreshes, progress updates, and updates with no new incoming message do not return a checked conversation to New merely because they were reposted.

For structured conversations, incoming message timestamps identify arrivals. Outgoing replies and changes to historic messages do not count. For apps that supply only a message title and body, changed text identifies an arrival; Android supplies no incoming-message identity in that format. Identical plain-text messages cannot be distinguished from reposts.

## Avatars and Attachments

Agreed in the October 2026 interview comparing Chronotif with the stock shade and iOS.

A conversation card leads with its Avatar, badged with the app's icon. A group shows its picture, falling back to the latest sender's photo. A one-to-one chat shows the sender's photo, falling back to the notification's large icon. With no picture at all, the card keeps the plain app icon. Other notifications keep their large icon on the right.

An Attachment shows as a thumbnail on the right of its message's card, as on iOS, on New, Earlier and open stacks alike. A video's thumbnail carries a play mark. A collapsed Conversation stack shows its latest message's thumbnail, with "+n" for the other photos inside. Tapping the thumbnail opens the app, like the rest of the card.

Pressing and holding any card opens its Preview over a dimmed screen, without unlocking. It shows the card's full text, its Attachment (or a picture notification's picture) large, and the app's buttons. Reply types inside the Preview, except on the lock-screen view, where it asks to unlock and continues in the panel. Tapping outside, or Back, closes it. Nothing in the app is opened or marked read.

Tapping the Preview's image opens a full-screen viewer, still without unlocking. In the viewer you pinch or double-tap to zoom, swipe sideways through the conversation's images, and swipe down or press Back to close. Save puts the image in the gallery's Pictures/Chronotif album. Share asks to unlock first on the lock screen, because it opens another app. A video shows only its still frame, so it offers Open in app instead of Save and Share. A notification's expanded picture opens in the same viewer. Viewing doesn't count as a check. When content is hidden, there are no Avatars or Attachments.

Chronotif reads Attachments through the sending app's content links, which Android lets notification listeners read. Many apps send a shrunk preview rather than the original, so a saved image can be lower quality than the one in the chat. An Attachment that can't be read just isn't shown.

## Calls, media and Running

Agreed in the October 2026 interview about always-running services crowding the top of the panel.

Ongoing calls come first, then Media players, then New and Earlier. Every other ongoing notification is Running: always-running services, connected devices, step counters, downloads, navigation and timers. Running sits below Earlier behind an "N running" pill and collapses again each time the panel opens. When Running is all there is, only the pill shows.

A media player stays a Media player while paused, even though apps drop the ongoing flag when they pause. It never falls into Earlier. Calls, Media players and Running never count as New, and none of them bring up the lock-screen view by themselves. Running never appears on the lock-screen view.

A Media player is drawn from the app's media session, like the system shade's player: artwork, title and artist, previous / play-pause / next, and a seek bar you can drag with elapsed and total time. It has no output (cast) button, because Android offers apps no public way to open the output picker. Apps that share no session fall back to their notification actions. When several apps have players, all of them show, newest first. A player can be swiped away only while paused, and only if the app allows dismissal. On the lock-screen view, players follow the same privacy rules as other cards: when content is hidden, only the app name and controls show.

## Verification cases

- Leave a notification wake untouched. Chronotif remains visible and the display sleeps after the stock lock-screen timeout (about ten seconds), not the normal screen timeout, without an extra stock lock-screen interval.
- Press Back while Chronotif is visible. The stock lock screen appears.
- Receive several messages in one conversation. New shows a collapsed stack with the count on the Avatar; tapping it shows one card per message. Messages from before the last check sit in the conversation's own Earlier stack.
- Swipe one message card away. Only that message disappears; the chat stays in the system shade. Swipe the collapsed stack and the conversation is cleared everywhere.
- Check a conversation, then receive another incoming message through a silent update of its notification. The conversation returns to New.
- Refresh a checked notification without a new incoming message. It remains in Earlier.
- Open the panel with only always-running services active. Only the "N running" pill shows; tapping it reveals them.
- Receive a photo in X or TikTok. It shows as a thumbnail on its card. Press and hold the card: the Preview shows it large with the app's buttons. Tap the image: the viewer opens, Save puts it in the gallery at once, and the card stays New. (WhatsApp sends no image, only "📷 Photo".)
- Receive three photos in one chat. The collapsed stack's thumbnail shows "+2". In the viewer you can swipe through all three.
- Press and hold a card on the lock-screen view. The Preview and the viewer show without unlocking; Reply and Share ask to unlock.
- Pause music. Its player stays at the top and does not move to Earlier.
- Play music in Spotify or YouTube Music. The player shows artwork, title, artist, transport controls and a seek bar that moves; dragging it seeks.
- With "Hide sensitive content" on, the lock-screen view's player shows the app name and controls but not the track.

## Automated verification

`./gradlew testDebugUnitTest` runs the pure attention-model tests and Android-backed Robolectric tests. Regression tests reproduce the silent message being classified into Earlier and the ten-second activity exit before the fixes. They cover the split of a conversation at the last check, collapsed and opened Conversation stacks, and hiding a single message. They also cover outgoing replies, historic messages, repeated content, plain-text message updates, and redaction.

The lock-activity test verifies that the window requests the keyguard timeout, that inactivity leaves Chronotif visible, and that a screen-off broadcast closes it. On an Android 16 emulator with a 60-second screen timeout, an untouched wake slept after about 11 seconds with Chronotif visible; without the timeout request it stayed on for 60 seconds. HyperOS power management is not emulated. Verify that behavior on the reporting phone.
