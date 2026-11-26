# The Play build has no Support link

Chronotif ships its APK on GitHub Releases and is going to Google Play as well. The GitHub build has a Support section in Settings with a button to Ko-fi, and a Support sheet that now and then points to it. The Play build leaves out both.

Google Play's Payments policy says apps "may not lead users to a payment method other than Google Play's billing system", and the only exception it names for donations is tax-exempt ones, which a developer's Ko-fi isn't. Whether a tip that unlocks nothing falls under that rule is unclear, and reviewers have decided both ways: open-source apps were removed from Play over plain donation links, and tip links have been rejected since. A rejection would hold up a whole release over one link.

The Play build is a build of its own anyway, since Play doesn't let an app update itself from outside the store and the self-updater has to come out of it. Leaving Support out of that build costs little more.

## Considered Options

- **The same Ko-fi link in both builds**: one build less to think about, but it risks rejection or removal for every Play release.
- **Tips through Google Play Billing in the Play build**: within the rules, but Google keeps 15%, it needs a merchant account and a billing library, and nobody knows yet whether Play users would tip. Worth revisiting once the listing is settled.
- **A link to chronotif.com, which links to Ko-fi**: less direct, but still leads to a payment page, so it carries a smaller version of the same risk.

## Consequences

- Until the Play build exists, the one build there is has Support, and the Ko-fi link carries a comment pointing here.
- The Play build is made with the rest of the Play launch work, which also takes out the self-updater.
- The website keeps its Ko-fi link; the policy covers the app, not the site.
