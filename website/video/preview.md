# What's new in 0.2.7, part 4: the Preview and the panel

`out/chronotif-0.2.7-4-silent.mp4`: about 60 seconds, vertical 1080×1920, silent (the version with
no music). It covers only what 0.2.7 changed in the Preview and the panel: Snooze, Clear
and Swipe to clear, closing a stack by its name, renamed conversations, and What's new after an update.
Built from `trailer/preview.html` by `trailer/render.mjs --page preview.html`.

It's motion graphics, like parts 1 and 2. The panel is drawn in dp to match `Panel.kt` and
`NotifList.kt` (an open stack's header: the title, Show less and ×), the Preview to match
`CardPreview.kt` (52 dp buttons, Snooze's choices, the spinner time picker starting an hour from now
rounded up to five minutes, and its "Snooze until" button), the sheet to match `WhatsNewSheet.kt`, and
Settings → Panel in the Material 3 colours of a Robolectric render of `SetupActivity`. The apps, people
and notifications are the same made-up ones as the other videos.

Music: none. No CC0 instrumental phonk is left that another video doesn't use, and the silent cut is the one to post.

Render: `node trailer/render.mjs raw/part4-silent.mp4 --page preview.html && cp raw/part4-silent.mp4 out/chronotif-0.2.7-4-silent.mp4`.

## Chapters

| Time | Chapter | Caption | What it shows |
|---|---|---|---|
| 0:00 | (title) | **Chronotif**. What's new in 0.2.7, part 4: the Preview and the panel. | Title card |
| 0:02 | Snooze it | Hold a card, then Snooze: 15 minutes, an hour, three hours, or tomorrow morning. | Daybook's "Call the dentist" held; its Preview; Snooze opens into the choices |
| 0:08 | Pick a time | Or pick a time. It shows when the card comes back. | Pick a time…: the wheels go from 10:45 AM to 6:30 PM; "Snooze until 6:30 PM"; the card leaves |
| 0:15 | Back as new | At 6:30 it comes back, at the top of New. | The clock runs on to 6:30; the reminder arrives at the top of New, the rest in Earlier |
| 0:20 | Clear | The Preview has a Clear button too. | Fernbank's card held, Clear tapped, the card goes |
| 0:25 | Swipe to clear | Clearing cards by accident? Turn off Swipe to clear under Settings → Panel. | Settings → Panel scrolled to Swipe to clear, switched off |
| 0:31 | Springs back | Then a swiped card gives a little and springs back. Clear it from its Preview. | Two swipes on a card: it moves a little and springs back |
| 0:36 | Close a stack | Tap an open stack's name to close it, as well as Show less. | Hiking crew opened into three messages, then closed by tapping its name |
| 0:42 | New names | When an app renames a chat, the panel shows the new name the next time it opens. | A note that Emma renamed the group; the panel reopens with "Lake trip ⛺" |
| 0:48 | What's new | After an update, the panel first shows what's new, only that. Your cards stay New until you close it. | The sheet ("Updated to 0.2.7", the headline, the new lines, All release notes, Got it); Got it; New still lit |
| 0:54 | Help shape it | Ideas or bugs? Tell us. | End card |

## Not in the video

- Tomorrow morning's exact rule (8:00 the next day, or the same day before 4:00), and that a time
  already past today means tomorrow.
- Snoozing a message snoozes its whole conversation.
- A nightly's What's new shows only the lines it added.
