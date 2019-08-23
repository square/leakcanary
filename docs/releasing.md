# Releasing LeakCanary

* Create a local release branch from `master`
```
git checkout master
git pull
git checkout -b release_1.5
```

* Update `VERSION_NAME` in `gradle.properties` (remove `-SNAPSHOT`)
```gradle
VERSION_NAME = "1.5"
```

* Find all doc references to the current version and update them:

```
grep -R "2.0-beta-2" docs/
```

* Generate the Dokka docs

```
rm -rf docs/api
./gradlew shark:dokka shark-android:dokka leakcanary-android-core:dokka leakcanary-android-instrumentation:dokka leakcanary-android-process:dokka shark-graph:dokka shark-hprof:dokka leakcanary-object-watcher-android:dokka shark-log:dokka leakcanary-object-watcher:dokka
```

* Update `docs/changelog.md` after checking out all changes:
    * https://github.com/square/leakcanary/compare/v1.4...master
* Take one last look
```
git diff
```

* Commit all local changes
```
git commit -am "Prepare 1.5 release"
```

* Perform a clean build
```
./gradlew clean build
```

* Create a tag and push it
```
git tag v1.5
git push origin v1.5
```

* Make sure you have valid credentials to upload the artifacts

`~/.gradle/gradle.properties`
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
* Merge the release branch to master
```
git checkout master
git pull
git merge --no-ff release_1.5
```
* Update `VERSION_NAME` in `gradle.properties` (increase version and add `-SNAPSHOT`)
```gradle
VERSION_NAME = "2.0-alpha-4-SNAPSHOT"
```
* Update the snapshot version in `docs/faq.md`:
```gradle
 dependencies {
   debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.0-alpha-4-SNAPSHOT'
 }
```
* Commit your changes
```
git commit -am "Prepare for next development iteration"
```

* Push your changes
```
git push
```

* Go to [Milestones](https://github.com/square/leakcanary/milestones), rename the current release to the version just released, and create a new *Next Release* milestone.
* Wait for the release to be available [on Maven Central](https://repo1.maven.org/maven2/com/squareup/leakcanary/leakcanary-android/).
* Redeploy the docs: `mkdocs serve` to check locally, `mkdocs gh-deploy` to deploy.
* Go to the [Draft a new release](https://github.com/square/leakcanary/releases/new) page, enter the release name (v1.5) as tag and title, and have the description point to the changelog. You can find the direct anchor URL from the [Change Log](https://square.github.io/leakcanary/changelog) page on the doc site.
```
See [Change Log](https://square.github.io/leakcanary/changelog#version-20-alpha-2-2019-05-21)
```
* Add the CLIP zip from `shark-cli/build/distributions/` to the release. Update the documentation to point to it.
* Tell your friends, update all of your apps, and tweet the new release. As a nice extra touch, mention external contributions.