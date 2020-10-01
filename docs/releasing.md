# Releasing LeakCanary

* Make sure you have the docsite Google Analytics key set up in your `~/.bashrc`:

```
export LEAKCANARY_GOOGLE_ANALYTICS_KEY="UA-142834539-1"
```

* Create a local release branch from `main`
```
git checkout main
git pull
git checkout -b release_{{ leak_canary.next_release }}
```

* Update `VERSION_NAME` in `gradle.properties` (remove `-SNAPSHOT`)
```gradle
VERSION_NAME={{ leak_canary.next_release }}
```

* Update the current version and next version in `mkdocs.yml`:
```
extra:
  leak_canary:
    release: '{{ leak_canary.next_release }}'
    next_release: 'REPLACE_WITH_NEXT_VERSION_NUMBER'
```

* Generate the Dokka docs
```
rm -rf docs/api
./gradlew leakcanary-android-core:dokka leakcanary-android-instrumentation:dokka leakcanary-android-process:dokka leakcanary-object-watcher-android:dokka leakcanary-object-watcher:dokka shark-android:dokka shark-graph:dokka shark-hprof:dokka shark-log:dokka shark:dokka plumber-android:dokka
```

* Confirm all API changes are intentional
```
git diff docs/api
```

* Update `docs/changelog.md` after checking out all changes:
    * https://github.com/square/leakcanary/compare/v{{ leak_canary.release }}...main
* Take one last look
```
git diff
```

* Commit all local changes
```
git commit -am "Prepare {{ leak_canary.next_release }} release"
```

* Perform a clean build
```
./gradlew clean
./gradlew build
```

* Create a tag and push it
```
git tag v{{ leak_canary.next_release }}
git push origin v{{ leak_canary.next_release }}
```

* Make sure you have valid credentials in `~/.gradle/gradle.properties` to upload the artifacts
```
SONATYPE_NEXUS_USERNAME=
SONATYPE_NEXUS_PASSWORD=
```

* Upload the artifacts to Sonatype OSS Nexus
```
./gradlew uploadArchives --no-daemon --no-parallel
```

* Generate the CLI zip
```
./gradlew shark-cli:distZip
```

* Release to Maven Central
    * Login to Sonatype OSS Nexus: https://oss.sonatype.org/
    * Click on **Staging Repositories**
    * Scroll to the bottom, you should see an entry named `comsquareup-XXXX`
    * Check the box next to the `comsquareup-XXXX` entry, click **Close** then **Confirm**
    * Wait a bit, hit **Refresh**, until the *Status* for that column changes to *Closed*.
    * Check the box next to the `comsquareup-XXXX` entry, click **Release** then **Confirm**
* Merge the release branch to main
```
git checkout main
git pull
git merge --no-ff release_{{ leak_canary.next_release }}
```
* Update `VERSION_NAME` in `gradle.properties` (increase version and add `-SNAPSHOT`)
```gradle
VERSION_NAME=REPLACE_WITH_NEXT_VERSION_NUMBER-SNAPSHOT
```

* Commit your changes
```
git commit -am "Prepare for next development iteration"
```

* Push your changes
```
git push
```

* Go to [Milestones](https://github.com/square/leakcanary/milestones), close the corresponding milestones and create a new milestone.
* Wait for the release to be available [on Maven Central](https://repo1.maven.org/maven2/com/squareup/leakcanary/leakcanary-android/).
* Redeploy the docs: `mkdocs serve` to check locally, `mkdocs gh-deploy` to deploy.
* Go to the [Draft a new release](https://github.com/square/leakcanary/releases/new) page, enter the release name (v{{ leak_canary.next_release }}) as tag and title, and have the description point to the changelog. You can find the direct anchor URL from the [Change Log](https://square.github.io/leakcanary/changelog) page on the doc site.
```
See [Change Log](https://square.github.io/leakcanary/changelog#version-20-alpha-2-2019-05-21)
```
* Add the CLIP zip from `shark-cli/build/distributions/` to the release.
* Make a pull request to [brew](https://brew.sh/). Just execute 
```bash
brew bump-formula-pr --url https://github.com/square/leakcanary/releases/download/v{{ leak_canary.next_release }}/shark-cli-{{ leak_canary.next_release }}.zip leakcanary-shark
```
(The url parameter should point at zip from this new release).   
In case of problems, read [brew docs](https://docs.brew.sh/How-To-Open-a-Homebrew-Pull-Request).  
* Tell your friends, update all of your apps, and tweet the new release. As a nice extra touch, mention external contributions.
