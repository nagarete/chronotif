# The list keeps a view for every row

The Panel and the lock-screen view draw notifications with `NotifList`, which keeps one live View per row (card, header, stack, player) in a plain `LinearLayout`, not a recycled list such as `RecyclerView`. That's the usual Android advice for long lists, so not following it needs a reason. Three things keep it this way.

- **The Pile (ADR 0011) needs every row laid out.** The lock-screen view folds the whole list under New's first card and unfolds it as it scrolls, moving each row from where it is laid out. It also measures where the list's top edge reaches the clock and how much room the artwork gets (ADR 0012). With recycling, rows off screen have no position, so the fold would have to work out every row's height itself.
- **Rows keep their state by stable id.** A card being replied to, an expanded card, a stack opened in place, a swipe in progress and an open Preview all hang off the row's View, keyed by notification and message. A recycled View is handed to another row, so all of that would have to move into the list's state and be bound back on every scroll.
- **Lists are usually short.** Most lists stay under about a hundred rows, and the cost below only starts to show at about fifty.

What keeps that cost down instead: `NotifList.place` makes and updates rows a few per frame once a screenful is up, so a long list never holds a frame back; a card is drawn again only when its look changes; and the Panel's `NearColumn` skips drawing rows more than half a screen away. The Panel itself is kept between opens (ADR 0009).

## The cost

- Memory: every row's View tree stays alive, in the kept Panel as well as while it shows. A card is a dozen or so Views. App icons come from a shared cache, but any picture a card shows stays with that card.
- Layout: a change that lays the list out again (a card expands, a notification arrives) measures every row, not just a screenful. Skipped drawing doesn't skip measuring.
- First open: the first screenful is made at once and the rest over the next frames, so a very long list finishes building after the first frame.

Usage data's `panel_speed` (21–24 Nov 2026, a few installs, mostly the developer's phones) shows where it starts to cost. With 50 or more cards, the kept Panel opened at a median of 109 ms against 93 ms when built fresh (13 and 5 opens). With 0–10 cards, the kept Panel took about 51 ms against 78–109 ms. So keeping every row helps short lists a lot and starts to cost on long ones, though few long-list opens were measured.

## Considered Options

- **Recycle a screenful of rows (`RecyclerView` or a pool of our own)**: memory and layout stay flat however long the list. But the Pile, the clock push and the artwork would need every row's height without its View, and expansion, reply and swipe state would move out of the Views. That's a large rewrite of the two most fiddly views, for lists most people never have.
- **Keep every row, but drop offscreen rows' Views in the kept Panel**: saves memory while closed, but the next open has to make them again, which is what ADR 0009 avoids.

## Consequences

- Revisit this when `panel_speed` by card count shows long lists getting slower to open, or when a phone with a long list stutters while scrolling or runs short of memory. Either of those would justify recycling.
- Row work has to stay cheap per row: binding, layout and the Pile's per-frame pass must not do per-row binder calls, decodes or allocations. A row's content is parsed once per notification and reused (`NotifList.contentOf`), and the Pile works out which rows it folds only when rows are placed.
