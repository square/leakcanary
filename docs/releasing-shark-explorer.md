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

## What the first signing runs found

Measured against the real service on 2026-08-04, from tags whose releases were forced to draft and then
deleted. Three separate faults, and they hid each other: Apple refused the app, the service reported the
refusal as success, and a space in the app's name broke the reply that would have said so.

### Apple refuses the app over a dylib no signer can see

Fixed here, by `shark-explorer-app/build.gradle.kts`, which deletes them from the app image. Worth
knowing anyway, because nothing about the failure points at the cause.

`skiko-awt-runtime-macos-arm64` ships both architectures' dylibs. Compose extracts the one it is
packaging for into the app directory, where `-Dskiko.library.path=$APPDIR` makes it the copy that loads,
and leaves the other architecture's dylib inside the jar. Nothing loads that one, and nothing signing the
bundle reaches it either: a signer walks files, and this is an entry in a zip. **Apple's notary service
opens jars.** So it refused the whole app over
`skiko-awt-runtime-macos-arm64-*.jar/libskiko-macos-x64.dylib` — *"The binary is not signed with a valid
Developer ID certificate"* — while every one of the 32 files a signer can see was signed correctly, each
with a secure timestamp, and the nested `Contents/runtime` bundle was sealed.

Which is why no local check finds this. `codesign --verify --deep --strict` passes on the returned DMG,
`spctl --assess` accepts it, and all four entitlements are there. What it costs is the whole app: one
Apple has no notarization record of does not launch, and it does not fail either. It hangs in `dyld` with
no output and no session log, where the same bundle re-signed ad hoc starts in two seconds. So a signed
DMG that opens into a hang means notarization, not entitlements.

### A refusal came back as success

`notarize()` in `apple-codesign/lib/notarize.sh` (`squareup/mdx-ios-codesign-helper`) read
`xcrun notarytool submit --wait`'s exit status rather than the `status` field of the JSON it asked for, so
a refusal logged "Notarization complete" and the pipeline handed back a signed, un-notarized DMG. It also
discarded that JSON, which is where the submission id was — and the id is the only handle on Apple's
reason, since the artifact carries no trace of it. Both halves are fixed in
squareup/mdx-ios-codesign-helper#20. A run after that merge failed where the same bundle had previously
"passed", which is that fix working.

**A signing failure's reason is legible only in Buildkite.** The lambda collapses any failed build into
`Poll request failed with status 400`, so the `notarytool log` output that PR added never reaches the
GitHub Actions log. Reading it means opening the build at `buildkite.com/runway/mdx-ios-codesign-helper`,
which is Block-internal, so expect to need someone with that access.

### A space in the app's name breaks the reply, not the signing

Open, and the reason `packageName` is one word. The service signed `Shark Explorer.app` correctly — the
Buildkite job passed and uploaded the signed zip — and then the lambda failed working out where it had put
it: `bad URI(is not URI?): "s3://…/Shark Explorer.app.zip"`, after a mac worker had done all the work.
`destination_url` in `global/lambdas/codesign_helper.rb` parses that S3 URL with Ruby's `URI()` to insert
`-signed` before the extension, and a space is not a legal URI character.
squareup/tf-mobuild-workers#1365 fixes it and has merged.

**Merging it is not the same as shipping it**, unlike the `notarize.sh` fix above, and this is the trap to
know about: Buildkite reads its scripts out of a git checkout at build time, so that one went live on
merge, whereas this lambda is Ruby that terraform packages and keeps serving the deployed zip until the
`mobuild-workers-rollout` CodePipeline applies. Every check on the pull request is a *plan*. A tagged
build after the merge still failed with the same `bad URI`, so **the rollout is what to wait for, not the
merge** — and that build is also the test, since the app's name reaches the service as an S3 key. Put the
space back, tag, and read the codesign step.

### Where that leaves it

Both macOS builds come back signed as Block, notarized and stapled: `spctl` says
`source=Notarized Developer ID` and `stapler validate` says *"The validate action worked!"*, first
measured on builds 1622 and 1623. Those two lines are what a release has to show, and the workflow fails
rather than publishing anything that cannot.

That was carried through to what someone downloading it gets: the DMG off a release, marked with the
`com.apple.quarantine` attribute a browser download applies — which is what makes Gatekeeper insist on a
ticket at all — is accepted, and the app opens a heap dump. So macOS is releasable, under the one-word
name.
