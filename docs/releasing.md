# Releasing LeakCanary

## Prerequisites

Publishing to Maven Central is fully automated: the
[`publish-release.yml`](https://github.com/square/leakcanary/blob/main/.github/workflows/publish-release.yml)
GitHub Actions workflow builds, signs, and publishes to the
[Maven Central Portal](https://central.sonatype.com/). The credentials live as
GitHub **organization secrets**, so no local Sonatype account or signing key is
required to cut a release:

* `SONATYPE_CENTRAL_USERNAME` / `SONATYPE_CENTRAL_PASSWORD` — a Central Portal user token
* `GPG_SECRET_KEY` / `GPG_SECRET_PASSPHRASE` — the artifact signing key

To run a release you only need:

* [GitHub CLI](https://cli.github.com/) and `jq`:
```bash
brew install gh jq
```
* The documentation toolchain (used to generate and deploy the docs site):
```bash
python3 -m venv venv
source venv/bin/activate
pip3 install --requirement docs/requirements.txt
```
* The Google Analytics key, required by `mkdocs` when deploying the docs. Add to your `~/.bashrc`:
```bash
export LEAKCANARY_GOOGLE_ANALYTICS_KEY="UA-142834539-1"
```

### Milestone management aliases (optional helpers)

```bash
gh alias set listOpenMilestones "api graphql -F owner=':owner' -F name=':repo' -f query='
    query ListOpenMilestones(\$name: String\!, \$owner: String\!) {
        repository(owner: \$owner, name: \$name) {
            milestones(first: 100, states: OPEN) {
                nodes {
                    title
                    number
                    description
                    dueOn
                    url
                    state
                    closed
                    closedAt
                    updatedAt
                }
            }
        }
    }
'"

gh alias set --shell createMilestone "gh api --method POST repos/:owner/:repo/milestones --input - | jq '{ html_url: .html_url, state: .state, created_at: .created_at }'"

gh alias set --shell closeMilestone "echo '{\"state\": \"closed\"}' | gh api --method PATCH repos/:owner/:repo/milestones/\$1 --input - | jq '{ html_url: .html_url, state: .state, closed_at: .closed_at }'"
```

> Publishing locally is normally unnecessary (and OSSRH-based local publishing no
> longer works). If you ever need it, set `mavenCentralUsername`/`mavenCentralPassword`
> (a Central Portal token) and `signingInMemoryKey`/`signingInMemoryKeyPassword` as
> Gradle properties — the same `ORG_GRADLE_PROJECT_*` names the workflow uses — then
> run `./gradlew publish`.

## Releasing

Set the version variables once; every step below uses them. This avoids the
hand-edited placeholders that previously caused mistakes (e.g. a dropped
`-SNAPSHOT`).

```bash
echo "Current: $(grep VERSION_NAME gradle.properties)"
printf "Version being released (e.g. 3.0-alpha-9): " && read NEW_VERSION
printf "Next version, WITHOUT -SNAPSHOT (e.g. 3.0-alpha-10): " && read NEXT_VERSION
# Previous release tag, used for the changelog diff link:
PREV_VERSION=$(git tag --sort=-v:refname | head -1)
echo "Releasing $NEW_VERSION (previous: $PREV_VERSION), next dev version: $NEXT_VERSION-SNAPSHOT"
```

* Create the release branch and set the release version:
```bash
git checkout main && git pull && \
git checkout -b release_$NEW_VERSION && \
sed -i '' "s/VERSION_NAME=.*/VERSION_NAME=$NEW_VERSION/" gradle.properties
```

* Update `mkdocs.yml`. `next_release` is the in-development version referenced by
  the snapshot docs — bump it to the next version. `release` is the latest
  **stable** version used throughout the getting-started docs; only change it when
  releasing a stable version, so leave it alone for alpha/beta releases.
```bash
sed -i '' "s/next_release: .*/next_release: '$NEXT_VERSION'/" mkdocs.yml
# Stable releases ONLY — also promote the stable version:
# sed -i '' "s/release: .*/release: '$NEW_VERSION'/" mkdocs.yml
```

* Update the changelog: rename the `## Unreleased` section to
  `## Version $NEW_VERSION (<date>)`, make sure it lists everything merged since
  the last release, and add a full diff link at the end of the section:
```
See the [full diff](https://github.com/square/leakcanary/compare/v<PREV_VERSION>...v<NEW_VERSION>).
```
  (Commit list for reference: `https://github.com/square/leakcanary/compare/v$PREV_VERSION...main`)
```bash
"${EDITOR:-vi}" docs/changelog.md
```

* Commit, tag, push, and trigger the publish workflow. It builds, signs, and
  publishes to the Maven Central Portal (`automaticRelease` releases it once
  validation passes — no manual portal step).
```bash
git commit -am "Prepare $NEW_VERSION release" && \
git tag v$NEW_VERSION && \
git push origin v$NEW_VERSION && \
gh workflow run publish-release.yml --ref v$NEW_VERSION && \
sleep 5 && \
gh run list --workflow=publish-release.yml --branch v$NEW_VERSION --json databaseId --jq ".[].databaseId" | xargs -I{} gh run watch {} --exit-status
```

Notes:

* The `publish-release.yml` workflow must already exist on `main` to be
  dispatchable (`workflow_dispatch` requirement).
* Monitor or drop deployments at https://central.sonatype.com/publishing/deployments

* Build the `shark-cli` distribution and create the GitHub release. Do this while
  `VERSION_NAME` is still the release version (before the `-SNAPSHOT` bump below),
  otherwise the zip is named after the snapshot version.
```bash
./gradlew shark:shark-cli:distZip && \
gh release create v$NEW_VERSION ./shark/shark-cli/build/distributions/shark-cli-$NEW_VERSION.zip --title v$NEW_VERSION --notes 'See [Change Log](https://square.github.io/leakcanary/changelog)'
```

* Merge back to `main` and start the next development iteration. The `-SNAPSHOT`
  suffix is required: without it, `main` carries a release-style version for the
  whole dev cycle, which breaks snapshot deployment.
```bash
git checkout main && git pull && \
git merge --no-ff --no-edit release_$NEW_VERSION && \
sed -i '' "s/VERSION_NAME=.*/VERSION_NAME=$NEXT_VERSION-SNAPSHOT/" gradle.properties && \
git commit -am "Prepare for next development iteration" && \
git push
```

* Generate the API docs and deploy the documentation site. Preview locally first
  if you want (`mkdocs serve`, then open http://127.0.0.1:8000/leakcanary/changelog/).
```bash
rm -rf docs/api && ./gradlew siteDokka && \
source venv/bin/activate && \
mkdocs gh-deploy
```

* Update milestones. There is one milestone per release:
    * Make sure the milestone for the version you just released contains only the
      issues fixed in this release; move any unfinished issues to the next
      milestone.
    * Close the released milestone and create the next one as the active milestone.

  List open milestones with `gh listOpenMilestones`, then use
  `gh closeMilestone <number>` and `gh createMilestone` (see the aliases above).

* Open the [v$NEW_VERSION release](https://github.com/square/leakcanary/releases) to confirm everything looks good.

* Wait for the release to be available [on Maven Central](https://repo1.maven.org/maven2/com/squareup/leakcanary/leakcanary-android/).

* **Stable releases only:** publish `shark-cli` to [Homebrew](https://brew.sh/).
  homebrew-core only accepts stable versions, so skip this for alpha/beta releases.
```bash
brew bump-formula-pr --url https://github.com/square/leakcanary/releases/download/v$NEW_VERSION/shark-cli-$NEW_VERSION.zip leakcanary-shark
```

* Tell your friends, update all of your apps, and announce the new release. As a nice extra touch, mention external contributions.
