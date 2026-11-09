# Excluding an app or channel only changes what Chronotif shows

The user wanted to choose which notifications Chronotif shows without touching Android's notification settings. We chose to filter inside Chronotif only. An Excluded app or channel is never shown, never New and never wakes the lock-screen view. Chronotif doesn't cancel, snooze or change the notification itself, so the stock shade, heads-up banners, sound, vibration and the screen wake all carry on as Android decides.

## Considered Options

- **Snooze excluded notifications again and again**, so they leave the stock shade too. This is fragile, the sending app sees the notification as gone, and it changes system state the user asked us to leave alone.
- **Cancel excluded notifications.** That's a dismissal, not exclusion.
- **Turn the screen back off with device-admin `lockNow()`** when a wake brings only excluded notifications. It still flashes and needs device-admin rights. Chronotif also can't tell which notification caused a wake, so it could turn the screen off in the user's hand.
- **Change Android's wake or channel settings through `WRITE_SECURE_SETTINGS`.** That is touching stock settings.

## Consequences

- An excluded app's notification can still wake the screen and appear on the stock lock screen. If a particular app's wakes bother the user, its channel has to be turned down in Android's settings.
- Nothing is exempt: calls, Media players and Running can be excluded with their app. An excluded incoming call or full-screen alarm still keeps Chronotif's lock-screen view from covering it.
- Clearing in Chronotif never dismisses an excluded notification.
- Excluding or including an app is never a check. A notification that comes back after being included is still New if it was New before.
- Chronotif can only list apps and channels it has seen post a notification. Reading an app's full channel list would need companion-device privileges.
