# Dev Environment for LeakCanary contributors

## Setup
* Download [Android Studio](https://developer.android.com/studio).
* We use two spaces code indentation, use `SquareAndroid` code style settings from https://github.com/square/java-code-styles.
* Build with `./gradlew build`.
* Running the failing UI tests to confirm leak detection correctly fails UI tests: `./gradlew leakcanary-android-sample:connectedCheck`.
* Normal UI tests: `./gradlew leakcanary-android-core:connectedCheck`.

## Static Code Analysis 
* LeakCanary [uses](https://github.com/square/leakcanary/pull/1535) [Detekt](https://arturbosch.github.io/detekt/) for static Code analysis.
* Analyze the entire project with `./gradlew check` or particular modules with `./gradlew :module-name:check`. Detekt will fail the build if any ruleset violations are found. **You should fix all issues before pushing the branch to remote**.
  * There's also a **git pre-push** hook that will run analysis automatically before pushing a branch to the remote. If there are any violations - it will prevent the push. Fix the issues!
  * You can bypass the git hook though; Travis CI will still run checks and will fail if any violations are found. 
* Detekt report will be printed in the console and saved to `/moduleDir/build/reports/`.

## Deploying locally

To deploy LeakCanary to your local maven repository, run the following command, changing the path to the path of your local repository:

```
./gradlew uploadArchives -PSNAPSHOT_REPOSITORY_URL=file:///Users/py/.m2/repository
```

Then add the SNAPSHOT dependency and `mavenLocal()` repository to your project:

```gradle
dependencies {
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:{{ leak_canary.next_release }}-SNAPSHOT'
}

repositories {
  mavenLocal()
}
```

## Deploying the docs locally

Installing the markdownextradata plugin:

```
pip install mkdocs-markdownextradata-plugin
```

Deploying locally

```
mkdocs serve
```
