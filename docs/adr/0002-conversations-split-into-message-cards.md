# Conversations are split into one card per message

Android holds a whole chat as one notification, but people wanted to see every message the way iOS shows them: one card per message, stacked per conversation, with new messages in New and already-seen ones in Earlier. We chose to split a conversation by message, using the messages the app supplies in the notification. As a result, one notification can appear as two Conversation stacks, one in New and one in Earlier. Swiping away a single message card hides it only in Chronotif, because Android can't remove one message from a notification. Swiping a whole stack, or tapping ×, clears the notification, and with it both stacks.

## Considered Options

- **One card per conversation, listing its new messages (the previous design, capped at five)**: matches Android and the system shade, and dismissing is never ambiguous. But a burst of messages becomes one long card, and already-seen messages hide behind the expand chevron.
- **One card per notification with no cap**: simple, but it doesn't give the per-message stacks users asked for.

## Consequences

- Chronotif keeps its own record of hidden message cards. The system shade can still show those messages.
- Apps that send only the latest text, with no list of messages, can't be split. They stay as one card per notification.
- Earlier now stacks by conversation, not by app, for conversations. Other notifications still stack by app.
