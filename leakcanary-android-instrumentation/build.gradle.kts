plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

android {
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig {
    targetSdk = libs.versions.compileSdk.get().toInt()
    minSdk = libs.versions.minSdk.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  buildFeatures.buildConfig = false
  lintOptions {
    disable("GoogleAppIndexingWarning")
    // junit references java.lang.management
    ignore("InvalidPackage")
    checkOnly("Interoperability")
  }
}

dependencies {
  api(project(":leakcanary-android-core"))

  implementation(libs.androidX.test.runner)
  implementation(libs.kotlin.stdlib)

  // AppWatcher auto installer for running tests
  androidTestImplementation(project(":leakcanary-object-watcher-android"))
  // Plumber auto installer for running tests
  androidTestImplementation(project(":plumber-android"))
  androidTestImplementation(libs.androidX.test.core)
  androidTestImplementation(libs.androidX.test.espresso)
  androidTestImplementation(libs.androidX.test.rules)
  androidTestImplementation(libs.androidX.fragment)
  androidTestImplementation(libs.assertjCore)
}
