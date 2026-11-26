# Each edge-handle gesture does what the user picks

Superseded in part by [ADR 0024](0024-the-edge-handle-taps-for-search-and-holds-to-sleep.md): new installs tap for Search, swipe down for the Panel and hold to sleep.

The edge handle had a fixed map: tap, swipe down or swipe inward opened the Panel, and holding opened Search. People wanted more from it, such as turning the screen off from any app, as AssistiveTouch can on iOS. No single map suits everyone, so each Handle gesture (Tap, Double-tap, Hold, Swipe down, Swipe up, Swipe inward) now does the Handle action the user picks: the Panel, Search, Sleep, any Lock-screen shortcut action, or None. The defaults are the old map, so nobody's handle changes until they pick.

Moving the handle stays fixed as hold, then drag. If dragging could be assigned like the others, a swipe given to it would be lost to every other action, and the handle has no room for a second way to move. Letting go of a hold without dragging runs Hold's action.

Tap waits Android's double-tap timeout only while Double-tap has an action. Otherwise every Panel open would pay about 0.3 seconds for a gesture nobody uses.

Sleep works as Double-tap to sleep does (ADR 0014): a black page at the lowest brightness, without the system bars, that asks for the shortest screen timeout until Android puts the display to sleep (about 6 seconds on HyperOS). It opens with `FLAG_ACTIVITY_NO_USER_ACTION`. Without that flag, YouTube took the launch as the user leaving and went picture-in-picture, and its small window kept the screen on.

## Considered Options

- **A new fixed map** (tap for Search, a quick flick down for the Panel, drag to move as on AssistiveTouch, hold to sleep): a flick and a drag differ only in speed, so a quick move would sometimes open the Panel, and the choice would still be the same for everyone.
- **An instant lock**: only the accessibility service's lock action or device admin's `lockNow()` can do it. Banking apps such as BCA refuse to run while any accessibility service is on, and `lockNow()` turns fingerprint and face unlock off for the next unlock (ADR 0014).
- **Screenshot as an action**: without accessibility, Android asks the user to approve each capture, or once per session on newer versions. Left for later.
- **A blurred handle**: blurring what's behind an always-shown window redoes the blur every frame anything behind it moves, and costs battery all day. Dropped.

## Consequences

- The tutorial's handle steps name whichever gesture is set to the Panel or Search, and offer *Gestures* when none is.
- A gesture set to an app opens it without asking to unlock, since the handle only shows while the phone is unlocked.
- Usage data sends each gesture's action key and counts Sleep and the shortcut actions from the handle, never which app.
