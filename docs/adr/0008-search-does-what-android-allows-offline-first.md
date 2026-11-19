# Search does what Android allows, opens without accessibility, and fetches only exchange rates

Supersedes ADR 0007. The user wanted iOS Spotlight's reach, not just apps: quick math, currency and a straight handoff to the browser, opened from anywhere. Chronotif's status-bar swipe needs an accessibility service, which banking apps such as BCA refuse to run alongside, and only the home-screen app can catch a swipe on the home screen. So Search opens from Chronotif's own surfaces, none of which need accessibility: the pull-down in the Panel and lock-screen view, a long-press on the edge handle, a Quick Settings tile, a home-screen search-bar widget, and a long-press shortcut on the app icon that launchers such as Nova can bind to their own swipe-down. It covers everything Android lets an ordinary app do: apps, math, units, time zones, Settings pages, contacts and calendar events (each with its own permission, off by default, hidden while locked), currency, and a last row that hands the query to the browser. Currency downloads the European Central Bank's public daily rates, opt-in and only when a currency query meets rates over a day old. That is the second network use after the updater, and nothing typed is sent.

## Considered Options

- **Keep ADR 0007's apps-only search**: no new permissions or network, but most of what the user wanted Spotlight for stays out.
- **A separate search app**: keeps Chronotif's scope and network rule untouched, but repeats its search screen, app list and lock-screen handling in a second install.
- **Become the default digital assistant** to open from holding power: one gesture from anywhere, but Android allows a single assistant, and taking the slot would remove Gemini, its voice wake-up and its "ask about this screen". The user wanted both side by side.
- **Become the home-screen app** to get the home swipe-down: the only way to catch that gesture, far beyond a notification app.
- **Online answers (weather, stocks, Wikipedia, dictionary) and suggestions while typing**: each needs a third-party service that sees what's looked up. The web row covers them through the user's own browser.
- **Messages, notes, mail, files and photos**: Android apps don't share their content, and file names alone need All files access for little use.

## Consequences

- CONTRIBUTING's bar for features widens to include Search, and its network rule names the exchange-rate download beside the updater.
- Search is never one gesture from inside any app, as on iOS. The edge handle is the closest, and the widget covers the home screen.
- Search shows no notifications, so it never counts as a check.
