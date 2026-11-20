# The lock-screen view does what the Panel does

The user wanted every feature to work on the lock screen, not only what was shown. Until now the lock-screen view held a lot back: replying asked to unlock and continued in the Panel, every button of a notification asked to unlock, Running was left out, and Search hid contacts and calendar events while locked. So the lock-screen view now does what the Panel does. Only opening an app still waits for the unlock, because Android can't show another app's screen over the keyguard.

- A reply types and sends right there, in a card or its Preview. The keyboard already comes up over the lock screen for Search.
- A button that works in the background, such as Mark as read, runs without unlocking. A button that opens the app asks to unlock first.
- When the app marks a reply or button as needing an unlock (Android 12's `setAuthenticationRequired`), the lock-screen view asks for one, as the system shade does.
- Running sits below Earlier behind its pill, as in the Panel.
- Search shows contacts and calendar events while locked.

The privacy switch is the one that already exists. When the lock screen hides sensitive content, by *Settings → Lock screen → Hide sensitive content* or by the system's setting with a secure lock, Search holds contacts and events back behind *Unlock to see all results*, and redacted cards show no buttons to reply with, as before.

## Considered Options

- **A separate "Allow when locked" switch per feature**: finer control, but a second privacy setting beside *Hide sensitive content* that people would have to find and keep consistent with it.
- **Keep replying behind the unlock**: the safer default, but a reply is what people most want to do from a woken phone, and the system shade on most phones already allows it.
- **Keep contacts hidden while locked** (ADR 0008): anyone holding the phone can no longer search them, but a phone that shows message content on the lock screen already shows who wrote.

## Consequences

- Anyone holding the locked phone can reply to a shown conversation, run a notification's background buttons, and search contacts and events, unless content is hidden on the lock screen. Calling or messaging a contact still asks to unlock, since it opens another app.
- Replying from the lock screen doesn't count as a check. Unlocking afterwards does, as before.
- Before Android 12, Android can't tell whether a button opens an app, so every button except a reply asks to unlock there.
