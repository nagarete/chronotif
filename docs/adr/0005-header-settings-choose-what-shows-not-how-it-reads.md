# Header settings choose what shows, not how the time and date read

A contributor (issue #42) built a customizable panel header: the title on or off, a large clock with its own size, the date beside or under it, full or short names, the year, and a manual 24-hour switch. The user wanted the customization, but not settings that compete with Android's. We drew the line here: Android decides what the time and date say, and Chronotif's settings decide what shows, where and how big. So 12/24-hour follows Android's setting, and the date's order, punctuation and spelling come from the locale. The settings only pick the parts (weekday, year, full or short names), and the locale arranges them. The "Chronotif" title was dropped from the header with no setting, and the clock takes its place by default.

## Considered Options

- **No settings, a fixed clock and date**: simplest, but the user wanted to choose the layout and size.
- **Take the contributor's full set, including a manual 24-hour switch and fixed date patterns**: a fixed pattern reads wrong in most languages, and a 24-hour switch disagrees with the rest of the phone.
- **Keep the title as an option, or move it under the clock**: the name is already on the launcher icon, the tile and the settings screen. In the open panel it only takes space.

## Consequences

- A request for a manual time or date format, such as a 24-hour override or a custom pattern, is declined by this rule. A request about what shows or where fits it.
- The day and month always show, because a date without its month is ambiguous.
- Tapping the clock opens the alarms and tapping the date opens the calendar, as in the stock shade. If no app handles it, nothing happens.
- Only the panel changes. The lock-screen view keeps its own clock.
