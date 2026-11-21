# The panel is kept between opens, hidden rather than rebuilt

Opening the panel built it from scratch every time: a new window, then every card. On a Poco X3 with 118 cards in Earlier that held the first frame back by about a second, and even once the header came first (PR #62) the cards and a status-bar pull that follows the finger stuttered while they were built and drawn. The stock shade feels instant because SystemUI keeps it built. So Chronotif keeps its last panel once it closes: the window stays added but hidden (no surface, untouchable, unfocusable), with its cards. The next open shows it again, updates only the cards whose notification, style or time label changed, and draws only the cards within half a screen of what's visible. Nothing runs while it's closed: no updates follow notifications until it opens again.

## Considered Options

- **Build on every open, just faster**: the old way. Loading icons off the main thread (PR #62) helped, but making and drawing a hundred cards stayed slow on a mid-range phone.
- **Keep it built and up to date while closed**: opens without even updating, but rebinds a hidden list on every notification change, which costs battery for something nobody sees.
- **Keep the cards but remove the window**: measured at about 140 ms on the X3 to attach and draw them again on each open.
- **A transparent window left on screen**: keeps the drawing too, but a full-screen window that's still visible makes Android flag touches to every app below as obscured, which apps that guard against overlays (banking apps) refuse.

## Consequences

- A few megabytes stay in memory while the panel is closed. The kept panel is let go when Android reports memory running low, when the configuration changes (theme, font size, language, rotation) and when Chronotif's settings are left, so the next open builds a fresh one that looks right.
- Every open must start like a new one: cards collapsed, no reply box, scrolled to the top, no Preview or Search left open. `PanelView.reset` and `NotifList.resetUi` do that, and new per-open state has to be reset there too.
- A card's binding depends on everything listed in its row's signature (`NotifList.Row.sig`). Something new that changes how a card looks has to join it, or a kept card shows it late.
  - Later, the signature became the card's look (`NotifList.Row.drawn`), and the card is drawn from that look by a function that can't reach the list's state. Anything a card shows from outside that state, such as the notification's ranking, still has to join the look.
