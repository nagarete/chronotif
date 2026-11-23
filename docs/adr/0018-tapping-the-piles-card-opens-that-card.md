# Tapping the Pile's card opens that card

Partly supersedes [ADR 0011](0011-the-lock-screen-view-rests-as-a-pile.md), where a tap on the Pile unfolded it.

A user woke the phone to one New conversation and tapped it to read its two messages. The whole list unfolded, Earlier included, and pushed the clock away, when only that card was wanted. So a tap on the Pile's card now does what a tap on that card would do in the open list, in place, with the view still at rest: a Conversation stack opens into its messages with the newest one expanded and its buttons showing, and a card expands. The rest of New and Earlier stay folded under it, behind their slivers and count. Tapping the count ("N more") unfolds the list as before, so seeing everything is still one tap. A card with nothing more to show still unfolds the list when tapped.

## Considered Options

- **Keep the tap unfolding the list**: one gesture for everything, but answering the one message the phone woke for takes a tap, a scroll back and another tap, and the clock is gone while doing it.
- **Open the stack without expanding its newest message**: the same as the Panel's tap, but Reply would need a second tap, which is the step the user was trying to skip.

## Consequences

- With the card open, every card in it takes its own swipe and hold, as in the open list. The folded cards under it still take nothing.
- An opened card too tall for the room under the clock pushes the clock up and fades it, as the unfold does, and scrolling down shows its top.
- Back folds an unfolded list into the Pile first, then closes the opened card, then shows the stock lock screen. Each wake still starts with the card closed.
- The Tutorial's step teaches the count rather than the card.
