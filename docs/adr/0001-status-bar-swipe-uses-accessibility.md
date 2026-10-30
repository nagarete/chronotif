# The status-bar swipe uses an accessibility service

People open notifications by swiping down from the very top of the screen, but ordinary app overlays can't receive touches on the status bar, so an overlay just below it (the approach in #21) was invisible and hard to hit, and making it visible would get in the way. We chose an opt-in accessibility service whose only job is to place a touch area over the notification side of the status bar, accepting the trust cost of the accessibility permission (and Xiaomi's periodic "keep allowing?" prompt) to match existing muscle memory. The service subscribes to no events and can't retrieve window content; the edge handle and Quick Settings tile remain for anyone who won't grant it.

## Considered Options

- **App overlay strip below the status bar (#21)**: no extra permission, but users start the swipe on the status bar and get the system shade anyway.
- **Visible handle at the top edge**: discoverable, but just another target to aim for, and permanently covers app content.
- **Closing the system shade after it opens and showing Chronotif**: covers every swipe, but flashes the stock shade, can't reliably tell notifications from Control Center, and needs window events.
