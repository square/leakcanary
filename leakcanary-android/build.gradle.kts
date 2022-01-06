plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

android {
  compileSdk = property("compileSdk").toString().toInt()
  defaultConfig {
    minSdk = property("minSdk").toString().toInt()
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
  // AppWatcher auto installer
  api(project(":leakcanary-object-watcher-android"))
  // Plumber auto installer
  implementation(project(":plumber-android"))

  androidTestImplementation(libs.androidX.test.espresso)
  androidTestImplementation(libs.androidX.test.rules)
  androidTestImplementation(libs.androidX.test.runner)
  androidTestImplementation(libs.assertjCore)
  androidTestImplementation(project(":shark-hprof-test"))
}
