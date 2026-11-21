# Chronotif sends Usage data

Chronotif was built with no analytics and no crash reports, and promised that nothing about the user left the device. That promise is narrowed here. Notifications, contacts, calendar events and Search queries still never leave the phone. What does leave is **Usage data**: anonymous counts, Panel speeds, settings and crash reports, sent to PostHog's EU servers. It adds an exception to ADR 0008's offline-first rule: Search's results never go online, but how often Search is used is counted.

The user wants to know how Chronotif is really used. Which phones and system versions it runs on, whether a fix stopped a crash, whether the Panel opens as fast on other phones as on the two it's tested on, and which features nobody finds. Download counts on GitHub can't answer those questions, and few people file bug reports. There aren't many users yet, so the cost to trust is lowest now. The code is open, so anyone can see exactly what is sent. T3 Code and Ardia, two open-source apps the user follows, send usage data the same way.

## The decision

- **On unless turned off.** Setup says it's on and puts *Turn off* beside the notice. The switch lives in *Settings → Privacy and about*. Existing installs learn about it from What's new.
- **What's sent**, all tied to a random install ID:
  - Once a day: the app version, the channel, Android's version, the phone's maker and model, the maker's system and its version (such as HyperOS 3.0), the country (from the mobile network or SIM, else the language's region), Chronotif's settings and permissions, and counts of what was used.
  - How long each Panel open took, with a rough card count (0–10, 10–50, 50+).
  - Crash reports: the exception types and stack frames, never the messages.

  `docs/usage-data.md` lists every field.
- **Never sent:** anything from a notification (including which apps notify), contacts, calendar events, what's typed in Search, device identifiers, the IP address (PostHog is set to discard it), screen recordings, tap logs, remote feature flags.
- **Turning it off** sends one last `usage_data_off` event, so opt-outs can be counted, then forgets the install ID and everything waiting to be sent. Turning it back on starts a new, unlinked install.
- **Only Stable and Nightly send.** The debug build has no key and sends nothing.
- **No SDK, no timer.** About 300 lines of Chronotif's own code post to PostHog's batch endpoint with `HttpURLConnection`, so the app keeps zero dependencies. The day's counts go out on the first app start or Panel close of a new day, and a crash goes out on the next one. Nothing polls or runs in the background.
- **The dashboard is private.** Neither T3 Code nor Ardia publishes a live dashboard. The field list is public instead.

## Considered Options

- **Keep no analytics**: the strongest privacy promise, but it leaves Chronotif tuned to two phones.
- **Opt-in, asked during setup**: safest under EU rules, but most people never turn such a thing on, so the numbers would only describe the few who did.
- **PostHog's or Sentry's Android SDK**: less code to write, but each pulls in several libraries, and their automatic capture (screens, taps, error messages) would need careful turning off in an app whose screens are notifications.
- **Firebase Analytics or Crashlytics**: closed-source binaries in a GPL app, and they need Google Play services, which some Xiaomi systems lack.
- **Our own endpoint on Cloudflare**: the most control, but the charts and storage would all need building.

## Consequences

- The privacy promise in `CONTRIBUTING.md`, the README, About, the website and the issue and PR templates now names Usage data. "Your notifications never leave your phone" stays true.
- Being on by default, without asking first, may not satisfy EU consent rules for people in the EU. The notice at setup, the one-tap switch and the public field list are there to be upfront about it. If that changes, switching to opt-in is a change to `Prefs.usageData`'s default, the same kind as ADR 0015.
- Every new field must be added to `docs/usage-data.md` in the same commit, and must not reveal a notification's content or which app sent it.
- Release builds keep class and method names (`proguard-rules.pro`), so crash frames are readable without a mapping file. The APK grows slightly.
- PostHog's free plan allows a million events a month. Chronotif sends a few dozen per phone per day.
