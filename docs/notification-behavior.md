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

In Earlier, each such conversation has its own stack. Your own replies aren't cards. Apps that send only the latest text, with no list of messages, can't be split: they stay one card per notification. When content is hidden on the lock screen, a conversation is one card with no messages.

This covers messages available in active Android notifications. It does not require an archive of messages Android no longer supplies.

## App stacks in Earlier

Agreed in the November 2026 review of Earlier on the phone (ADR 0004, which supersedes ADR 0003).

Earlier is newest first. Cards from one app that sit next to each other in that order fold into an App stack, as long as each arrived within 30 minutes of the stack's newest card. Three T3 Code cards from the last two minutes form one stack. A T3 Code card from 4 hours earlier forms a separate stack with the cards near it, never with the recent ones. A card from another app between two of an app's cards splits them. Grouping all of an app's cards together would pull its older cards above newer ones from other apps.

A collapsed App stack shows its newest card with slivers underneath. Tapping it opens its cards in the same place, under the app's name with Show less and ×. Swiping the collapsed stack, or tapping ×, clears every notification in it. A conversation showing one message is a single card and can join an App stack, for example TikTok's one-message chats. A Conversation stack with several messages stays on its own. New doesn't use App stacks: every New card shows in full. The "N earlier" pill counts cards, not stacks.

## New incoming messages

A new incoming message returns its conversation to New, including when the app sends the update silently or marks it to alert only once. Timestamp refreshes, progress updates, and updates with no new incoming message do not return a checked conversation to New merely because they were reposted.

For structured conversations, incoming message timestamps identify arrivals. A conversation Chronotif sees for the first time (after a restart, or when an app re-alerts old chats) arrived with its latest message, not when it was posted, so yesterday's chat stays in Earlier. Conversations sort by their newest message, the time their cards show. Outgoing replies and changes to historic messages do not count. For apps that supply only a message title and body, changed text identifies an arrival; Android supplies no incoming-message identity in that format. Identical plain-text messages cannot be distinguished from reposts.

## Avatars and Attachments

Agreed in the October 2026 interview comparing Chronotif with the stock shade and iOS.

A conversation card leads with its Avatar, badged with the app's icon. A group shows its picture, falling back to the latest sender's photo. A one-to-one chat shows the sender's photo, falling back to the notification's large icon. With no picture at all, the card keeps the plain app icon. Other notifications keep their large icon on the right. An app that draws its own layout and gives no large icon (TikTok's LIVE alerts carry the host's photo only there) gets the biggest photo it put into that layout instead. Images built into the layout itself, such as the app's logo, don't count.

An Attachment shows as a thumbnail on the right of its message's card, as on iOS, on New, Earlier and open stacks alike. A video's thumbnail carries a play mark. A collapsed Conversation stack shows its latest message's thumbnail, with "+n" for the other photos inside. Tapping the thumbnail opens the app, like the rest of the card.

Pressing and holding any card opens its Preview over a dimmed screen, without unlocking. It shows the card's full text, its Attachment (or a picture notification's picture) large, and the app's buttons. Reply types inside the Preview, except on the lock-screen view, where it asks to unlock and continues in the panel. Tapping outside, or Back, closes it. Nothing in the app is opened or marked read.

Tapping the Preview's image opens a full-screen viewer, still without unlocking. In the viewer you pinch or double-tap to zoom, swipe sideways through the conversation's images, and swipe down or press Back to close. Save puts the image in the gallery's Pictures/Chronotif album. Share asks to unlock first on the lock screen, because it opens another app. A video shows only its still frame, so it offers Open in app instead of Save and Share. A notification's expanded picture opens in the same viewer. Viewing doesn't count as a check. When content is hidden, there are no Avatars or Attachments.

Chronotif reads Attachments through the sending app's content links, which Android lets notification listeners read. Many apps send a shrunk preview rather than the original, so a saved image can be lower quality than the one in the chat. An Attachment that can't be read just isn't shown.

## Calls, media and Running

Agreed in the October 2026 interview about always-running services crowding the top of the panel.

Ongoing calls come first, then Media players, then New and Earlier. Every other ongoing notification is Running: always-running services, connected devices, step counters, downloads, navigation and timers. Running sits below Earlier behind an "N running" pill and collapses again each time the panel opens. When Running is all there is, only the pill shows.

A media player stays a Media player while paused, even though apps drop the ongoing flag when they pause. It never falls into Earlier. Calls, Media players and Running never count as New, and none of them bring up the lock-screen view by themselves. Running never appears on the lock-screen view.

A Media player is drawn from the app's media session, like the system shade's player: artwork, title and artist, previous / play-pause / next, and a seek bar you can drag with elapsed and total time. It has no output (cast) button, because Android offers apps no public way to open the output picker. Apps that share no session fall back to their notification actions. When several apps have players, all of them show, newest first. Paused or stopped Media players can be swiped left or right when the app allows it. A playing player cannot be swiped away. If Android keeps the notification after a swipe, the card returns. On the lock-screen view, players follow the same privacy rules as other cards: when content is hidden, only the app name and controls show.

## Opening the Panel

The status-bar swipe brings the Panel down under the finger, like the system shade: it appears with the first downward movement and follows it. Let go past a third of the screen, or flick down, and it opens; otherwise it slides back up. A tap on the status bar opens it at once, and so do the edge handle and the Quick Settings tile. While the Panel follows the finger, the light dim behind it follows too; the blur comes in once it has opened.

The header and dim show at once and the cards right after. A long list fills in from the top. Every open starts like a new one: cards collapsed, no reply box, scrolled to the top.

Being on screen counts as a check once the Panel's cards have shown for about 1.5 seconds and it has opened fully, or as soon as it's used. For a pull that opens, the 1.5 seconds count from when its cards first showed during the pull. A pull that slides back is never a check.

## Search

Search opens without the accessibility service, so it works alongside banking apps that refuse to run with one (ADR 0008). It opens from five places: a pull-down in the Panel or lock-screen view, a long press on the edge handle, the Chronotif Search Quick Settings tile, a Search home-screen widget (a bar 2 to 5 cells wide, or a round 1x1 button), and the Search shortcut on a long press of the app icon. Launchers that bind gestures, such as Nova, can bind theirs to the shortcut. Opened anywhere but the pull-down, Search has its own window over whatever is on screen, the lock screen included. It never takes the digital-assistant slot, so Gemini keeps the power button.

Pulling down with the list at its top, on the Panel or the lock-screen view, opens Search. On the Panel the pull has to start after the Panel has finished opening. The status-bar swipe that opens it ends when the finger lifts, so it never runs on into Search. A pull shorter than about 80dp springs back. The field gets focus and the keyboard comes up.

Search never searches notifications. Before you type, it shows a row of up to four apps, the ones that notified most recently, newest first, once each. That counts an app whose notification has since been dismissed. Calls, Media players, Running and Chronotif itself don't count. Typing hides the row and shows, top to bottom: an answer, apps, contacts, Settings pages, calendar events, and a web row.

The answer is one card for quick math (`12*7.5+3`, `200 + 15%`), a unit conversion (`5 km in mi`, `30 c` goes to °F), the time in a city or one-time-zone country (`time in tokyo`, `bali time`), or a currency (`100 usd`, `$20`, `rp 50.000 to sgd`). Numbers are read and written the way the phone's language does: in English `50,000` is fifty thousand, in Indonesian `50.000`. Math shows up to 10 significant digits and conversions 6, with trailing zeros dropped. Tapping the card copies the number without grouping, also on the lock screen, since a result reveals nothing.

Currency reads a code, symbol or name before or after the amount (`100 usd`, `$100`, `€20`, `rp 50000`), and an optional target (`100 usd to sgd`). `$` is US dollars and `¥` yen; "pound" stays a unit of mass, so pounds sterling are `£` or `gbp`. Without a target, an amount converts to the currency of the country the phone is in, by its mobile network, then its SIM, then its language's region; an amount already in that currency converts to US dollars. Rupiah, yen, won, forint and krónur show whole amounts, and `rp 50.000` is fifty thousand in any language, since those have no cents. Other currencies show two decimals. The rates are the European Central Bank's daily reference rates, which cover about 30 currencies, and the card shows their date. They download only when *Settings → Search → Currency rates* is on and a currency question meets saved rates older than the newest the ECB should have out. It publishes on working days around 16:00 Frankfurt time, so the app looks for a new day's rates after 16:15 there, and on a bank holiday, when the same rates come back, it looks again at most once an hour. Nothing typed is sent. With the switch off, the card offers to turn it on.

Apps are listed by name: those whose name starts with the query, then those where a later word starts with it ("music" finds YouTube Music), then those containing it anywhere ("tok" finds TikTok), alphabetical within each. Case and accents don't matter. A work-profile or dual copy of an app is its own entry, with the profile's badge.

Contacts and calendar events each need a switch in *Settings → Search*, off until turned on. Turning one on asks for Android's Contacts or Calendar permission, and the switch stays off if it's refused. Both are read on the phone when Search opens and never stored. A contact matches when its name, or a later word in it, starts with the query, so "bu" finds Budi but not Ibu. Typed digits, three or more, also find a contact by number. The best three show, then *Show more* lists up to twenty. Each row has the photo or initial, the name, and the number its buttons use: the one set as default, else a mobile, else the first. Tapping the row opens the contact. For a contact on WhatsApp or WhatsApp Business, the row says so and the message button opens their WhatsApp chat, using WhatsApp when a contact is on both. Otherwise it opens the default SMS app to that number. The call button opens the dialer with the number ready, unless *Settings → Search → Call from Search* is on. That switch asks for Android's Phone permission, because WhatsApp starts a call only for an app holding it. With it on, the call button starts a WhatsApp voice call for a WhatsApp contact and a phone call for anyone else, and WhatsApp contacts get a video call button too. Calendar events match their title the same way, from 30 days ago to a year ahead, and the three soonest show: events not over yet by start time, then past ones, the latest first. A repeating event shows once, at its next time, and so does the same event in two calendars, such as a holiday in two holiday calendars. Tapping one opens it in the calendar app.

Settings pages come from a fixed list of the pages Android lets apps open, matched by name and common words ("wifi", "brightness", "hotspot"), at most four. The web row is always last: text that looks like an address (`klikbca.com`) opens directly in the default browser, anything else is searched there with the engine chosen in *Settings → Search* (Google by default). Go on the keyboard opens the top app, or the web when no app matches. Chronotif sends nothing itself.

Results show while locked, except contacts and calendar events: with either switch on, a row saying *Unlock to see all results* takes their place, whether or not anything matched, so the lock screen doesn't reveal who or what is in them. Tapping it asks to unlock, then shows everything for the same query. Tapping an app, a Settings page or the web row on the lock screen asks to unlock, then opens it. Search shows no notifications, so opening it is never a check. Back, a tap on empty space, or a swipe up closes Search. On the lock-screen view, closing goes back to the notifications rather than unlocking. *Settings → Search → Pull down for Search* turns the pull-down off; the other ways in stay.

## Verification cases

- Leave a notification wake untouched. Chronotif remains visible and the display sleeps after the stock lock-screen timeout (about ten seconds), not the normal screen timeout, without an extra stock lock-screen interval.
- Press Back while Chronotif is visible. The stock lock screen appears.
- Let one app post three notifications within a few minutes, and another notification from it hours earlier, then check. Earlier shows the three as one collapsed App stack and the old one apart from them; tapping the stack opens the three in place.
- Receive several messages in one conversation. New shows a collapsed stack with the count on the Avatar; tapping it shows one card per message. Messages from before the last check sit in the conversation's own Earlier stack.
- Swipe one message card away. Only that message disappears; the chat stays in the system shade. Swipe the collapsed stack and the conversation is cleared everywhere.
- Check a conversation, then receive another incoming message through a silent update of its notification. The conversation returns to New.
- Refresh a checked notification without a new incoming message. It remains in Earlier.
- Open the panel with only always-running services active. Only the "N running" pill shows; tapping it reveals them.
- On Android 15 / HyperOS, pause Apple Music, leave its NO_CLEAR Media player with deleteIntent visible, and swipe it in Chronotif. The player disappears from the stock shade and Android's active notifications too, not just Chronotif. Active or buffering playback must resist swipes; a protected Media player without deleteIntent must also resist. A rejected dismissal restores the card. Vertical scrolling, playback buttons and the seek bar keep working.
- Receive a photo in X or TikTok. It shows as a thumbnail on its card. Press and hold the card: the Preview shows it large with the app's buttons. Tap the image: the viewer opens, Save puts it in the gallery at once, and the card stays New. (WhatsApp sends no image, only "📷 Photo".)
- Get a TikTok LIVE alert. Its card shows the host's profile photo on the right, not the TikTok logo.
- Receive three photos in one chat. The collapsed stack's thumbnail shows "+2". In the viewer you can swipe through all three.
- Press and hold a card on the lock-screen view. The Preview and the viewer show without unlocking; Reply and Share ask to unlock.
- Pause music. Its player stays at the top and does not move to Earlier.
- Play music in Spotify or YouTube Music. The player shows artwork, title, artist, transport controls and a seek bar that moves; dragging it seeks.
- With "Hide sensitive content" on, the lock-screen view's player shows the app name and controls but not the track.
- Get notifications from two apps, dismiss one, then pull down at the top of the panel. Search opens with the keyboard up, and its row shows both apps, the newer first. Type "tok": TikTok is listed. Tap it: TikTok opens and the panel closes.
- Pull down on the lock-screen view and tap a suggestion. The unlock prompt shows, then the app opens. Pull down again and press Back: the notifications are back, still locked.
- Open the panel with the status-bar swipe and keep dragging down. Search doesn't open.
- Pull a little way down from the status bar and let go slowly. The Panel slides back up and New is unchanged. Pull past a third of the screen, or flick down: it opens.
- Close the Panel and swipe the status bar again straight away. The Panel opens, not the system shade.
- With BCA open and the accessibility service off, hold the edge handle. Search opens over BCA; Back returns to it.
- Add the Search tile and the Search widget. Each opens Search with the keyboard up, also from the lock screen, where tapping an app asks to unlock first.
- Long-press Chronotif's icon and tap Search. Search opens.
- Type `12*7.5+3`: the answer card shows "= 93"; tap it and paste somewhere: `93`. Type `5 km in mi`, `30 c`, `time in tokyo`: each shows its answer above the apps.
- Turn on *Find contacts* and allow the permission. Type the start of a contact's surname: the contact shows with its number. Tap the message button: WhatsApp opens the chat if the contact uses it, otherwise the SMS app opens to that number. The call button opens the dialer. Turn on *Call from Search*: the call button starts a WhatsApp call, and a video call button appears. Lock the phone, open Search from the tile and type the same: no contacts, only *Unlock to see all results*. Tap it and unlock: the contact shows.
- Turn on *Find calendar events*, add a weekly event, and type its first word. It shows once, at its next date.
- Type `100 usd` with currency rates off: the card offers to turn them on. Tap it: after a moment the card shows the amount in rupiah (on an Indonesian network) with the rate's date. Type `rp 50.000 to sgd`: fifty thousand rupiah in Singapore dollars, two decimals.
- Type `wifi`: Wi-Fi is under Settings. Type `klikbca.com`: the last row opens it. Type `nasi goreng`: the last row searches it in the default browser.

## Automated verification

`./gradlew testDebugUnitTest` runs the pure attention-model tests and Android-backed Robolectric tests. Regression tests reproduce the silent message being classified into Earlier and the ten-second activity exit before the fixes. They cover the split of a conversation at the last check, collapsed and opened Conversation stacks, and hiding a single message. They also cover outgoing replies, historic messages, repeated content, plain-text message updates, and redaction.

The lock-activity test verifies that the window requests the keyguard timeout, that inactivity leaves Chronotif visible, and that a screen-off broadcast closes it. On an Android 16 emulator with a 60-second screen timeout, an untouched wake slept after about 11 seconds with Chronotif visible; without the timeout request it stayed on for 60 seconds. HyperOS power management is not emulated. Verify that behavior on the reporting phone.
