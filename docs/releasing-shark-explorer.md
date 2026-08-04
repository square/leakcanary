# Releasing Shark Explorer

Shark Explorer is a desktop app, not a library, and it is released **separately from LeakCanary**. This
page is that process. [Releasing LeakCanary](releasing.md) is the other one, and the two share nothing but
the repository.

| | LeakCanary | Shark Explorer |
| --- | --- | --- |
| Tags | `v3.0-alpha-10` | `shark-explorer-1.0.0` |
| Version in | `VERSION_NAME` | `SHARK_EXPLORER_VERSION` |
| Goes to | Maven Central | a GitHub release |
| Workflow | `publish-release.yml` | `release-shark-explorer.yml` |
| Change log | [changelog.md](changelog.md) | [shark-explorer-changelog.md](shark-explorer-changelog.md) |

Two release schedules means two change logs. **Shark Explorer changes never go in the LeakCanary change
log**, and the reverse: a reader of either one is asking about one release line.

## The version can't say "alpha", so the release does

`SHARK_EXPLORER_VERSION` is three integers and nothing else. Every installer format validates it, and
between them they leave only `MAJOR.MINOR.PATCH` with **MAJOR between 1 and 255**, MINOR up to 255 and
PATCH up to 65535 — macOS rejects a MAJOR of 0, and MSI rejects anything over 255. So there is no number
that means "before 1.0": not `0.1.0`, not a calendar version, and no qualifier like `-alpha-1`.

What follows is that **the release says it instead**. `release-shark-explorer.yml` marks every release as a
prerelease and titles it `Shark Explorer <version> (alpha)`. Drop that when the app is no longer alpha.

## Cutting a release

Set the version, tag it, and let CI do the rest. The workflow refuses to run if the tag and
`SHARK_EXPLORER_VERSION` disagree, so this order matters.

```bash
printf "Version being released (e.g. 1.0.1): " && read NEW_VERSION
git checkout main && git pull && \
git checkout -b shark_explorer_$NEW_VERSION && \
sed -i '' "s/SHARK_EXPLORER_VERSION=.*/SHARK_EXPLORER_VERSION=$NEW_VERSION/" gradle.properties
```

Rename the `## Unreleased` heading in
[`docs/shark-explorer-changelog.md`](shark-explorer-changelog.md) to `## Version $NEW_VERSION (<date>)`,
check it lists everything that landed since the last one, and commit:

```bash
"${EDITOR:-vi}" docs/shark-explorer-changelog.md && \
git commit -am "Release Shark Explorer $NEW_VERSION"
```

Merge that to `main`, then tag it:

```bash
git tag shark-explorer-$NEW_VERSION && \
git push origin shark-explorer-$NEW_VERSION && \
gh run watch $(gh run list --workflow=release-shark-explorer.yml --limit 1 --json databaseId --jq '.[].databaseId') --exit-status
```

There is no `-SNAPSHOT` dance here, unlike LeakCanary: nothing consumes this version as a dependency, so
`main` carrying the last released number between releases costs nothing.

What the workflow builds:

* **macOS arm64 and x64**, signed and notarized (see below). Two builds because jpackage produces a thin
  binary for the architecture it runs on and has no universal option.
* **Windows `.msi` and Linux `.deb`**, unsigned.

## Telling people about it is a separate step

Publishing a release does **not** offer it to anyone. The app checks one file — the `latest.properties`
asset of the rolling `shark-explorer-latest` release — and only `promote-shark-explorer.yml` writes it:

```bash
gh workflow run promote-shark-explorer.yml -f version=$NEW_VERSION
```

So install the release and open a heap dump with it before running that. A release that turns out to be
broken is then one nobody was told about, rather than one that has to be withdrawn.

The release notes link to the change log page, which is only live once the site is deployed:

```bash
rm -rf docs/api && ./gradlew siteDokka && mkdocs gh-deploy
```

Two things this shares with [releasing LeakCanary](releasing.md), for the same reasons. `siteDokka` is
not optional even though nothing about an explorer release touches the API reference: `docs/api` is
generated and git ignored, so `gh-deploy` without it publishes a site whose API pages 404. And this
deploys **the whole site from your checkout**, so run it from `main` rather than from a branch carrying
unrelated documentation work.

Two things about that mechanism that look like accidents and aren't:

* **It is not the GitHub API.** `releases/latest` returns the newest release of *either* line, and this
  repository publishes LeakCanary on `v*` tags, so that endpoint usually answers with the wrong release
  entirely. The unauthenticated API is also 60 requests an hour **per IP**, which a shared corporate
  egress can exhaust; a release asset is an ordinary unmetered CDN download.
* **The app only ever reports.** It shows a bar naming the new version with a link, and nothing downloads
  or installs itself. See `UpdateCheck.kt`.

## macOS signing

Handled by [`block/apple-codesign-action`](https://github.com/block/apple-codesign-action), which signs and
notarizes with `Developer ID Application: Block, Inc.` through Block's internal signing service. Nothing
in this repository holds a certificate or an Apple credential: the workflow authenticates over OIDC and the
signing happens elsewhere, which is what makes this safe to do from a public repository.

It needs two repository secrets, `OSX_CODESIGN_ROLE` and `CODESIGN_S3_BUCKET`. **Ask `#mdx-ios` to
provision them** — that team owns Apple codesigning at Block for macOS desktop apps as well as iOS ones,
which is how `block/qrgo` and `block/buzz` are signed.

Two things to know about the result:

* The service signs the `.app` and rebuilds the DMG around it, so **the DMG container itself is unsigned**.
  Gatekeeper assesses the app, which is the part that has to pass, and the workflow runs `codesign
  --verify`, `stapler validate` and `spctl --assess` on the app inside the DMG and prints what they say.
  Read those, especially on a first release.
* `entitlements.plist` next to the app module is not optional. Every key in it is something the JVM does
  and the hardened runtime forbids by default, so a notarized build without them launches and immediately
  dies.

Windows is unsigned, and signing it would mean Azure Trusted Signing — a different service and a separate
ask. Linux `.deb` needs no signature.

## Managed Software Center, for Block employees

Optional, and worth doing only for discoverability: the in-app check already covers updates. File a ticket
at `go/cpeticket` with the repository, the bundle ID (`com.squareup.leakcanary.shark-explorer`), a release
asset URL and install type "optional". CPE build the AutoPkg pipeline and Munki recipes themselves and pick
up each new GitHub release daily.

Note that Munki compares versions using `CFBundleShortVersionString`, which is `SHARK_EXPLORER_VERSION` —
another reason that field can't be pinned to something the releases don't move.

## What a first signing run found, and what is still open

Both were measured against the real service on 2026-08-04, from a tag whose release was forced to draft
and then deleted. Neither is fixable here — both are `#mdx-ios` asks, and each is one function.

* **The signing service signs without notarizing, and reports success.** The DMG came back signed by
  `Developer ID Application: Block, Inc. (EYF346PHUG)` with the hardened runtime on, all four
  entitlements present, and each of its 32 Mach-O files carrying a secure timestamp — and Apple had no
  notarization record of it, still none eight hours later: `xcrun stapler staple` answers *"CloudKit
  query failed due to Record not found"*. That app does not launch. It hangs in `dyld` with no output
  and no session log, where the same bundle re-signed ad hoc with the same entitlements starts in two
  seconds. So the app-not-launching failure this page used to warn about is real, and it is not the
  entitlements.

    `notarize()` in `apple-codesign/lib/notarize.sh` (`squareup/mdx-ios-codesign-helper`) treats `xcrun
    notarytool submit --wait` exiting 0 as the verdict and never reads the `status` field out of the JSON
    it asked for, so a submission Apple refused still logs "Notarization complete". Stapling then fails,
    which is the only remaining signal, and that is a warning that deliberately doesn't fail the build.

* **A space in the app's name breaks the reply, not the signing.** The service signed `Shark
  Explorer.app` correctly — the Buildkite job passed and uploaded the signed zip — and then the lambda
  failed working out where it had put it: `bad URI(is not URI?): "s3://…/Shark Explorer.app.zip"`, five
  minutes in, after a mac worker had done all the work. `destination_url` in
  `global/lambdas/codesign_helper.rb` (`squareup/tf-mobuild-workers`) parses that S3 URL with Ruby's
  `URI()` to insert `-signed` before the extension, and a space is not a legal URI character. Renaming
  the package to `SharkExplorer` is what got a signature back.

Until both are fixed there is no releasable macOS build, and the notarization check in the workflow
fails the release rather than shipping one.
