# What Chronotif sends

Chronotif sends **Usage data** to PostHog, on PostHog's servers in the EU, so the app can be improved from how it's really used. This page lists everything that's sent. The code that sends it is [`UsageData.kt`](../app/src/main/java/app/chronotif/UsageData.kt), and [`core/Usage.kt`](../app/src/main/java/app/chronotif/core/Usage.kt) holds the full list of counts. The decision and its reasons are in [ADR 0016](adr/0016-chronotif-sends-usage-data.md).

Usage data is on unless you turn it off: **Settings → Privacy and about → Usage data**.

## Never sent

- Anything from a notification: titles, text, senders, pictures, and which apps notify.
- Your contacts, calendar events, or anything you type in Search.
- Device identifiers (Android ID, advertising ID, IMEI) or your phone number.
- Your IP address. PostHog is set to discard it, and Chronotif asks PostHog not to look up a location from it.
- Screen recordings or a log of your taps.

## What every event carries

| Field | Example | Why |
|---|---|---|
| `distinct_id` | `3f2c…` | A random install ID, made by Chronotif. Turning Usage data off deletes it. |
| `$app_version` | `0.2.7-nightly.330` | Tells whether a fix worked, or a release made things worse. |
| `channel` | `nightly` | Stable or Nightly build. |

## Events

### `usage_data_on`

Sent once, when an install starts sending (its first start, or Usage data turned back on). It carries the same phone details and settings as `daily`, without the counts.

### `daily`

Sent once a day, the first time Chronotif starts or the Panel closes on a new day.

**The phone**

| Field | Example |
|---|---|
| `$os`, `$os_version`, `sdk` | `Android`, `16`, `36` |
| `$device_manufacturer`, `$device_model` | `Google`, `Pixel 8` |
| `system` | `HyperOS 3.0`, `One UI 6.1`, `MIUI 14` or `Android` |
| `country`, `$geoip_country_code` | `DE`, from the mobile network, else the SIM, else the language's region |

**Settings and permissions**, as they are when the day's batch is made: `set_enabled`, `set_swipe_to_clear`, `set_status_swipe`, `set_handle`, `set_handle_right`, `set_handle_shape`, `set_handle_colour`, `set_handle_new_colour`, `set_handle_look`, `set_handle_hide_landscape`, `set_handle_tap`, `set_handle_double_tap`, `set_handle_hold`, `set_handle_swipe_down`, `set_handle_swipe_up`, `set_handle_swipe_inward` (each `none`, `panel`, `search`, `sleep`, `flashlight`, `camera`, `qr`, `dnd` or `app`, never which app), `set_lock_mode`, `set_lock_hide_content`, `set_lock_double_tap`, `set_lock_shortcut_left`, `set_lock_shortcut_right` (`none`, `flashlight`, `camera`, `qr`, `dnd` or `app`, never which app), `set_qr_tile`, `has_google_lens`, `set_header_clock`, `set_header_clock_size`, `set_header_date_beside`, `set_header_date_align`, `set_header_weekday`, `set_header_year`, `set_header_short_names`, `set_header_settings`, `set_pull_down_search`, `set_search_engine`, `set_search_contacts`, `set_search_calls`, `set_search_calendar`, `set_currency_rates`, `set_search_tile`, `set_auto_update`, `set_nightly_updates`, `set_settings_theme`, `tutorial_parts_done` (how many parts), `perm_listener`, `perm_overlay`, `perm_status_swipe`.

**Counts** since the last `daily`. Each is how often something happened, never what it was about. A count that stayed at 0 is left out.

| Field | Counts |
|---|---|
| `n_panel_opened` | Panel opens |
| `n_lock_shown` | Times the lock-screen view was shown |
| `n_stack_opened` | Stacks opened |
| `n_preview_opened` | Previews opened |
| `n_notification_opened` | Notifications opened in their app |
| `n_action_tapped` | Notification buttons tapped |
| `n_reply_sent` | Replies sent (never what was written) |
| `n_cleared` | Clears (one card or a whole section) |
| `n_snoozed` | Snoozes |
| `n_media_dismissed` | Media players swiped away |
| `n_search_opened` | Search opened |
| `n_search_app`, `n_search_contact`, `n_search_calendar`, `n_search_setting`, `n_search_web` | Which sort of Search result was opened, never which one |
| `n_double_tap_sleep` | Double-taps to sleep |
| `n_handle_sleep` | Sleeps from an edge-handle gesture |
| `n_handle_shortcut` | Flashlight, camera, QR scanner, Do Not Disturb or an app from an edge-handle gesture, never which |
| `n_shortcut_flashlight`, `n_shortcut_camera`, `n_shortcut_qr`, `n_shortcut_dnd`, `n_shortcut_app` | Lock-screen shortcuts used, by what they're set to, never which app |
| `n_qr_tile` | Taps on the Chronotif QR scanner tile |
| `n_tutorial_part_done` | Tutorial parts finished |
| `n_anr`, `n_native_crash` | Times Android stopped Chronotif for not responding, or it crashed outside its own code |
| `days` | How many days the counts cover, more than 1 if the phone was off |

The Tutorial and its sample cards aren't counted.

### `panel_speed`

Up to 30 a day, sent with `daily`, each with the time of the open.

| Field | Example | Meaning |
|---|---|---|
| `ms` | `142` | Milliseconds from asking for the Panel to its cards being built |
| `cards` | `10-50` | Roughly how many notifications it held: `0-10`, `10-50` or `50+` |
| `kept` | `true` | Whether it reused the Panel kept from the last open (ADR 0009) |
| `pulled` | `false` | Whether it was pulled down from the status bar |
| `$device_model` | `Pixel 8` | The phone |

### `crash`

Sent the next time Chronotif starts after a crash, with the phone details from `daily`.

| Field | Example |
|---|---|
| `exception` | `java.lang.IllegalStateException` |
| `causes` | `java.lang.RuntimeException`, the exceptions that led to it |
| `frames` | Up to 25 lines like `app.chronotif.ui.NotifList.render(NotifList.kt:212)` |

The exception's **message is never sent**. A message can quote whatever value the app was handling, and in Chronotif that is often a notification.

### `usage_data_off`

Sent once when Usage data is turned off, with `$app_version` and `channel` only. Then the install ID and everything waiting to be sent are deleted, and nothing more is sent.

## When sending fails

Chronotif keeps a batch on the phone until PostHog has it. If a send fails, it tries again at the next app start or Panel close at least an hour later, with that same batch, so nothing is counted twice. Counts made in the meantime go in the next day's batch.

## Builds that send nothing

The debug build (`app.chronotif.debug`) has no PostHog key and never sends.

## Feedback is separate

**Settings → Send feedback** isn't Usage data, and turning Usage data off doesn't affect it. Nothing goes until you write a message and tap Send. It goes to chronotif.com, whose Worker ([`worker/feedback.js`](../website/worker/feedback.js)) emails it privately to the developer; it isn't stored there or posted anywhere. It carries:

- what it's about (Problem, Idea or Other), if you pick one;
- your message;
- how to reach you, only if you fill it in;
- the phone's details, unless you switch them off before sending: app version and channel, Android version, the phone's maker and model, and its system (e.g. HyperOS 3.0).

Your IP address is used only to limit how many messages one network can send a minute (and, on the website, for Cloudflare's Turnstile bot check). It isn't kept or put in the email. The debug build sends Feedback too, since it's only sent when you ask.
