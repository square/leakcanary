/**
 * The multi process setup, as small as it goes: add `leakcanary-android-process` next to
 * `leakcanary-android` and the heap analysis runs in a `:leakcanary` process. Nothing else is
 * needed, and if this file ever grows something else that is needed, that is a bug to fix in the
 * library rather than a line to add here.
 *
 * It is also the app CI builds to catch collisions between those two artifacts. Nothing else in the
 * repo puts them in the same app, which is why the duplicate `com.squareup.leakcanary` namespace
 * they both used to declare shipped in 2.14 and broke every multi process app on AGP 9 while
 * `./gradlew build` stayed green. Assembling this module runs the manifest merge and the namespace
 * uniqueness check over the pair, in the checks job, with no emulator.
 */
plugins {
  id("com.android.application")
}

dependencies {
  implementation(projects.leakcanary.leakcanaryAndroid)
  implementation(projects.leakcanary.leakcanaryAndroidProcess)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()

  defaultConfig {
    applicationId = "com.example.leakcanary.process"
    minSdk = libs.versions.androidMinSdk.get().toInt()
    targetSdk = libs.versions.androidCompileSdk.get().toInt()

    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    // An app would put LeakCanary in debugImplementation and would not minify that build. Here it
    // is in both build types and release is minified, so that one ./gradlew build covers the merge
    // of the two artifacts both ways, and so that checkShrunkManifestComponents has a shrunk app to
    // read.
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      // Debuggable and debug signed so the minified APK can be installed and tried by hand.
      // LeakCanary refuses to install itself in a non debuggable build, so without this the app
      // would die on launch instead of running the analysis.
      isDebuggable = true
      signingConfig = signingConfigs["debug"]
    }
  }
  namespace = "com.example.leakcanary.process"
  lint {
    disable += "GoogleAppIndexingWarning"
  }
}
