# A Media player's artwork rests large on the lock screen

Waking the phone while music plays is mostly to see what's playing or to skip a track. The lock-screen view showed a compact player under the clock and left the middle of the screen empty, the space the Pile leaves free (ADR 0011). So the album art now rests large in that space, the player under it and anything New under the player. Scrolling shrinks the art into the player, and a blur of the art stands in for the wallpaper.

The artwork is part of the Pile's resting state, not a separate mode. The list gets a room for it above the newest player, sized so the Pile still sits at the bottom, and the large picture is drawn over that room. Scrolling up moves the list as usual and the picture shrinks from the room into the player's thumbnail at the same pace, so it follows the finger and nothing is rebuilt mid-gesture. Back folds both back. New still shows: the art takes only the room the Pile leaves, and gives way when the screen is too short for it.

For the view to be useful while music plays, a playing Media player now brings it up by itself, in either *Show on lock screen* mode. This reverses part of the earlier rule that live state never does. A paused player doesn't, because apps keep paused players for hours and the view would come up on every wake.

## Considered Options

- **Keep the art to its thumbnail**: no change, but it leaves the middle of the lock screen empty during playback.
- **Hide New while the art shows**, behind an "N new" line: a cleaner picture, but New is what Chronotif is for.
- **A blur only while the art rests**: less change to the screen as it opens, but the blur fading out mid-scroll draws attention to itself.

## Consequences

- Only the newest player gets the large artwork, and only with no call going. Other players sit under it as usual.
- With content hidden on the lock screen there is no large artwork and no blur, as the artwork is the track's.
- The blur is the artwork drawn a few pixels wide and stretched, so it works on any Android version; Android 12 and newer also blur it.
