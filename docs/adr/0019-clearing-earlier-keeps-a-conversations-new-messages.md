# Clearing Earlier keeps a conversation's New messages

Partly supersedes [ADR 0002](0002-conversations-split-into-message-cards.md), where clearing either of a conversation's stacks cleared the notification and with it both stacks.

A conversation can sit in New and Earlier at once, split by message. Clearing its Earlier stack, an App stack holding one of its checked messages, or Earlier as a whole with its Clear button, cancelled the whole notification. New messages the user hadn't read yet went with it, from a gesture made on what they had already seen. Earlier's Clear also missed these checked messages altogether, because it only looked at notifications whose arrival was before the check.

So clearing in Earlier acts on what Earlier shows. The checked messages of a conversation that still has messages in New are hidden, as swiping each of their cards would, and the notification stays, in New and in the system shade. When what Earlier shows is all that is left of the notification, clearing it cancels the notification as before. Clearing New's stack still clears the whole conversation, both stacks, as ADR 0002 has it: New is what the user is looking at, and a conversation they clear there is one they are done with.

## Considered Options

- **Keep ADR 0002: any stack's clear cancels the notification**: one rule, but a clear in Earlier throws away messages the user never saw.
- **Every stack clears only its own messages, New included**: symmetric, but clearing a chat from New or the Pile would leave its checked messages, and the notification, behind. Getting rid of a chat would take two swipes, and the system shade would keep it after the first.

## Consequences

- Earlier's Clear button shows when Earlier holds only the checked messages of conversations also in New.
- A conversation cleared this way stays in the system shade until its New stack is cleared or the app removes it.
- What counts as New is decided per message: a conversation whose New messages are all swiped away isn't New, even with checked messages left, so it neither tints the edge handle nor wakes the lock-screen view.
