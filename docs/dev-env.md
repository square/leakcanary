# Dev Environment for LeakCanary contributors

* Download Android Studio
* We use two spaces code indentation, use https://github.com/square/java-code-styles
* Build with `./gradlew build`
* Running the failing UI tests to confirm leak detection correctly fails UI tests: `./gradlew leakcanary-sample:connectedCheck`
* Normal UI tests: `./gradlew leakcanary-support-fragment:connectedCheck`