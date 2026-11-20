# Double-tap to sleep blacks out the screen, then lets Android sleep

On Xiaomi phones, as on Samsung and OnePlus ones, double-tapping the lock screen puts the display to sleep. The lock-screen view covers the stock lock screen, so the gesture stopped working there, and the first tap asked to unlock instead. Android gives an ordinary app no call that turns the display off. So a double tap on empty space turns the screen black at the lowest brightness, hides the system bars and asks for the shortest screen timeout Android allows. Android then puts the display to sleep by itself, after its minimum screen-off timeout: 6 seconds on HyperOS, on both the Poco X3 NFC and the Xiaomi 17T Pro. It needs no permission, and fingerprint and face unlock keep working.

## Considered Options

- **The accessibility service's lock action**: a real sleep at once, with fingerprint kept. But banking apps such as BCA refuse to run while any accessibility service is on, so the user who asked for this couldn't use it. ADR 0001's service keeps its one job, the status-bar swipe.
- **Device admin's `lockNow()`**: a real sleep at once. But Android then asks for the PIN, pattern or password on the next unlock, with fingerprint and face off, every time. Device admin also has to be switched off before Chronotif can be uninstalled.
- **Handing over to the stock lock screen**: a double tap closes Chronotif, and the stock lock screen's own double tap does the rest. No permission, but it takes two double taps and flashes the stock lock screen.
- **Shizuku**: shell rights put the display to sleep as the power button does. But wireless debugging has to be started again after every reboot, and some banking apps refuse to run with developer options on.
- **A shorter window timeout alone**: Android raises it to the same minimum, and the screen stays lit until then. Chronotif already asks for the keyguard's short timeout.

## Consequences

- For the seconds before Android sleeps, the display is on behind the black. Always-on display shows only after that, and a power press in that time only finishes the sleep, so a second press wakes the phone.
- Until then, touches still reach Chronotif, so the black screen acts as a sleeping one would: a double tap brings the lock-screen view back, as double tap to wake would, and a single tap does nothing.
- On an LCD, the lowest brightness still glows faintly until the display sleeps.
- While *Double-tap to sleep* is on (the default), a single tap on empty space waits Android's double-tap timeout, about 0.3 seconds, before asking to unlock.
- Going to sleep this way is not a check, just as the display timing out isn't.
