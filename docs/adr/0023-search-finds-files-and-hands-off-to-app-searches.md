# Search finds files and hands off to app searches

Supersedes ADR 0008 in part: files, which it left out.

The user wanted Search to reach further than apps, contacts and the web. Two additions change what ADR 0008 decided.

**Files.** ADR 0008 left files out because file names alone needed All files access for little use. What people look for by name is mostly documents: a ticket, an invoice, a PDF in Downloads. So Search now finds Files by name, newest first, and music by title, artist and album too. Android already indexes every file, so Search asks that index when it opens and keeps no index of its own. It reads in two ways. Android 13's photo, video and music permissions work in every build. All files access, which documents need, is used only in the GitHub build, because Google Play grants it only to file managers, backup and antivirus apps. One *Find files* switch and checklist step sets up whichever the build has. Files follow ADR 0013: names show while locked unless the lock screen hides sensitive content, and opening one asks to unlock.

**Hand-off rows.** Below the web row, Search offers the same query to the Play Store, Maps, Spotify and YouTube Music, each opening that app's own search. A row shows only when its app is installed, and there's no switch to hide one: the rows sit below every result, so an unused one costs a line at the bottom. As with the web row, Chronotif sends nothing itself.

## Considered Options

- **Media only, in every build**: one Search for both builds and no Play risk, but photo names are mostly `IMG_…`, so it finds songs and screenshots and misses the documents people actually look for.
- **All files access in both builds**: one Search everywhere, but Play would reject the Play build over it.
- **A row of chips for any app that takes a search query**: covers more apps, but the user wanted rows like the web row, for the apps they use.
- **A switch per hand-off row**: lets someone hide Maps, at the cost of a Settings list for rows that never push a result down.

## Consequences

- The Play build's Search finds media but not documents, a second difference from the GitHub build after ADR 0021's Support link. Settings says so in the Play build.
- Anyone holding the locked phone can read file names by typing, as they can contact names, unless the lock screen hides sensitive content.
- Google Play's Photo and Video Permissions policy allows the photo and video permissions only to apps whose core use needs broad access to them. If Play refuses them for Search, the Play build drops them the same way it drops All files access, and finds music only; the code asks for whichever media permissions the build declares.
- ADR 0008's "messages, notes, mail and photos" stay out: those apps still don't share their content.
