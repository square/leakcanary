# Releasing LeakCanary

## Preparing the release environment

### Set up your Sonatype OSSRH account

* Create a [Sonatype OSSRH JIRA account](https://issues.sonatype.org/secure/Signup!default.jspa).
* Create a ticket to request access to the `com.squareup.leakcanary` project. Here's an example: [OSSRH-54959](https://issues.sonatype.org/browse/OSSRH-54959).
* Then ask someone with deployer role from the LeakCanary team to confirm access.

### Set up your signing key

```bash
# Create a new key
gpg --gen-key
# List local keys. Key id is last 8 characters
gpg -K
cd ~/.gnupg
# Export key locally
gpg --export-secret-keys -o secring.gpg
# Upload key to Ubuntu servers
gpg --send-keys --keyserver keyserver.ubuntu.com <KEY ID>
# Confirm the key can now be found
gpg --recv-keys --keyserver keyserver.ubuntu.com <KEY ID>
```

### Set up your home gradle.properties

Add this to your `~/.gradle/gradle.properties`:

```
signing.keyId=<KEY ID>
signing.password=<KEY PASSWORD>
signing.secretKeyRingFile=/Users/YOUR_USERNAME_/.gnupg/secring.gpg
SONATYPE_NEXUS_USERNAME=<SONATYPE_USERNAME>
SONATYPE_NEXUS_PASSWORD=<SONATYPE_PASSWORD>
```

### Set up the Google Analytics docs key 

Add this to your `~/.bashrc`:

```bash
export LEAKCANARY_GOOGLE_ANALYTICS_KEY="UA-142834539-1"
```

### Set up GitHub CLI

Install GitHub CLI

```bash
brew install gh
```

Install jq, a CLI Json processor

```bash
brew install jq
```

Set up aliases for milestone management:

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

### Install or update the doc generation dependencies

```bash
pip3 install --requirement docs/requirements.txt
```

## Releasing

* Create a local release branch from `main`
```bash
git checkout main && \
git pull && \
git checkout -b release_{{ leak_canary.next_release }}
```

* Update `VERSION_NAME` in `gradle.properties` (remove `-SNAPSHOT`)
```gradle
sed -i '' 's/VERSION_NAME={{ leak_canary.next_release }}-SNAPSHOT/VERSION_NAME={{ leak_canary.next_release }}/' gradle.properties
```

* Update the current version and next version in `mkdocs.yml`
```bash
sed -i '' 's/{{ leak_canary.next_release }}/NEXT/' mkdocs.yml
sed -i '' 's/{{ leak_canary.release }}/{{ leak_canary.next_release }}/' mkdocs.yml
```

* Create the release
```bash
git commit -am "Prepare {{ leak_canary.next_release }} release" && \
./gradlew clean && \
./gradlew build && \
git tag v{{ leak_canary.next_release }} && \
git push origin v{{ leak_canary.next_release }} && \
./gradlew publish --no-daemon --no-parallel && \
./gradlew closeAndReleaseRepository && \
./gradlew shark-cli:distZip
```

Note: if anything goes wrong, you can manually drop the release at https://s01.oss.sonatype.org/

* Merge back to main
```bash
git checkout main && \
git pull && \
git merge --no-ff release_{{ leak_canary.next_release }}
```
* Update `VERSION_NAME` in `gradle.properties` (increase version and add `-SNAPSHOT`)
```gradle
sed -i '' 's/VERSION_NAME={{ leak_canary.next_release }}/VERSION_NAME=NEXT-SNAPSHOT/' gradle.properties
```

* Generate the Dokka docs
```bash
rm -rf docs/api && ./gradlew siteDokka
```

* Update the changelog ([commit list](https://github.com/square/leakcanary/compare/v{{ leak_canary.release }}...main))
```
mate docs/changelog.md
```	

* Deploy the docs locally then [open the changelog](http://127.0.0.1:8000/leakcanary/changelog/) and check everything looks good
```bash
mkdocs serve
```

* Finish up the release

```bash
git commit -am "Prepare for next development iteration" && \
git push && \
gh listOpenMilestones | jq '.data.repository.milestones.nodes[0].number' | xargs gh closeMilestone && \
echo '{
  "title": "REPLACE_WITH_NEXT_VERSION_NUMBER",
  "state": "open",
  "description": ""
}' | gh createMilestone && \
mkdocs gh-deploy
gh release create v{{ leak_canary.next_release }} ./shark-cli/build/distributions/shark-cli-{{ leak_canary.next_release }}.zip --title v{{ leak_canary.next_release }} --notes 'See [Change Log](https://square.github.io/leakcanary/changelog)'
```

* Open the [v{{ leak_canary.next_release }} release](https://github.com/square/leakcanary/releases/tag/v{{ leak_canary.next_release }}) to confirm everything looks good.

* Upload shark-cli to [brew](https://brew.sh/):
```bash
brew bump-formula-pr --url https://github.com/square/leakcanary/releases/download/v{{ leak_canary.next_release }}/shark-cli-{{ leak_canary.next_release }}.zip leakcanary-shark
```

* Wait for the release to be available [on Maven Central](https://repo1.maven.org/maven2/com/squareup/leakcanary/leakcanary-android/).
* Tell your friends, update all of your apps, and tweet the new release. As a nice extra touch, mention external contributions.
