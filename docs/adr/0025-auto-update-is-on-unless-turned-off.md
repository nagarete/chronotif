# Auto-update is on unless turned off

Supersedes ADR 0015 in part: its last line, that the updater stays opt-in.

Chronotif installs from GitHub Releases, outside any store, so its own updater is the only way a fix reaches a phone. With Auto-update opt-in, Usage data from late November 2026 showed most installs days or weeks behind: of 13 sending, 9 were on old builds, 4 of them with Auto-update never turned on. So Auto-update is now on unless turned off, for new installs and for older installs that never chose. Someone who turned it off keeps it off. A notice in Settings says it's on, with the switch a tap away, and asks for "Install unknown apps" so updates install without a prompt. The release notes tell updating installs the same.

The daily check now runs for everyone, on any network, because it costs a few kilobytes. With Auto-update off, it only posts **Update available**, once per build on either channel, and installing takes a tap. With it on, the APK's download still waits for Wi-Fi and a battery that isn't low, in a one-off job, so it never spends paid mobile data.

## Considered Options

- **Keep it opt-in and ask in setup**: no install without a yes, but most people skip a question they don't need yet, and the data showed where that leads.
- **On for new installs only**: keeps the promise made to older installs, but those are the installs already behind.
- **Download on any network**: gets stuck phones moving fastest, but Nightly's daily builds would cost around 300 MB a month of mobile data without anyone noticing.

## Consequences

- An install that never chose starts downloading and installing updates after this version, told only by the notice and What's new.
- Usage data reports how each install's last check ended (`docs/usage-data.md`), so a phone where updates still stall can be found.
- Installs on 0.2.8 or older that had Auto-update off can't learn any of this until they update once by hand; the release notes ask them to.
- The README, About, the website and `CONTRIBUTING.md` describe the updater as on unless turned off.
