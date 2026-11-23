# The QR scanner is Google Lens only

The QR scanner, from a Lock-screen shortcut or the Chronotif QR scanner tile, opens Google Lens and nothing else. Lens reads small, angled and damaged codes quickly and acts on what it reads (links, Wi-Fi logins, contacts, payment codes, text around the code), and the user knows no other Android scanner that comes close. Chronotif builds no scanner of its own. Without Lens it says *Not available without Google Lens* and offers *Get Google Lens*, which opens the Play Store.

## Considered Options

- **Fall back to the phone's own scanner** (HyperOS ships one): it works without Lens, but the scanner then differs from phone to phone, and people who get the weaker one would decide the feature is bad.
- **Chronotif's own scanner on ML Kit's code scanner**: needs nothing installed, but it only decodes. Chronotif would then own a camera screen and everything a code can ask to do.

## Consequences

- On a phone without Google apps, the QR scanner can't be chosen. Flashlight, Camera, Do Not Disturb and An app don't depend on Lens.
- Usage data records whether Lens is installed, to see how often the QR scanner can't be used.
- Whether Lens opens over the lock screen is up to Lens. When it doesn't, the QR scanner asks to unlock first.
