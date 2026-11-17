# The updater reads github.com release pages, not the GitHub API

The updater used to read the release list from `api.github.com`. Without a token, that API allows 60 requests an hour per IP address, and everyone behind the same address shares them. Home networks with other GitHub tools, and mobile carriers that put many customers behind one address, ran out, and the check failed with `HTTP 403`. The updater now reads github.com pages instead: stable follows the `/releases/latest` redirect to its tag, and nightly reads the tags in the `releases.atom` feed. CI names each release's files after its tag (`chronotif-<tag>.apk` and its `.sha256`), so the updater builds the download URLs from the tag.

## Considered Options

- **Keep the API and show a clearer error**: the check would still fail for anyone on a busy shared address.
- **Ship a token in the app**: anyone could extract it, and GitHub would revoke it.
- **Publish a version file on chronotif.com**: another thing for every release to update, and the website would then decide which version phones install.

## Consequences

- Renaming the release files in `build.yml` breaks updates for every installed version. The names are now part of the updater's contract.
- A release whose files haven't finished uploading fails the check with `HTTP 404` instead of being skipped. The next check picks it up.
- The feed lists only recent releases. A nightly that falls off it has always been replaced by a newer one.
- A 403 or 429 from github.com shows "GitHub is limiting requests from this network" instead of the bare code.
