# Earlier stacks one app's neighbouring cards that arrived close together

Superseded in part by [ADR 0019](0019-clearing-earlier-keeps-a-conversations-new-messages.md): clearing an app stack hides the checked message of a conversation that is also in New instead of clearing that notification.

ADR 0003 gave every Earlier notification its own card. On the phone, Earlier then showed long runs of near-identical cards, for example three T3 Code "Done" cards from the last two minutes, or four TikTok messages that all arrived at the same minute. The user wanted those runs folded together the way iOS does. They did not want a T3 card from 4 hours ago folded in with them. We chose to stack only cards from one app that sit next to each other in Earlier's time order, where each card arrived within 30 minutes of the stack's newest card. A stack sits where its newest card would sit, and opening it shows its cards in that same place. So a stack never puts an old card above a newer one from another app, which was the reason for ADR 0003.

## Considered Options

- **One card each (ADR 0003)**: strict, but long runs from one app fill Earlier.
- **Stack by app, ordered by the newest member (the design before ADR 0003)**: puts cards from hours ago above newer cards from other apps.
- **Stack neighbours with no time limit**: two bursts hours apart would merge whenever no other app posted between them.
- **Measure each gap to the previous card rather than to the newest**: a steady trickle, such as one card every 20 minutes, would chain into one stack spanning hours.

ADR 0003 rejected neighbour-only stacks because stacks would form and break apart as other apps post. That rarely happens in Earlier. New arrivals go to New, so a stack splits only when one of its cards is removed or updated back into New.

## Consequences

- A Conversation stack with several messages stays its own stack and is never folded into an app stack. A conversation showing a single message is one card and stacks like any other card, as with TikTok's one-message chats.
- New is unchanged: every New card is shown in full.
- The "N earlier" pill still counts cards, not stacks.
- Swiping a collapsed app stack, or tapping × on an opened one, clears every notification in it.
- The website demo mirrors this.
