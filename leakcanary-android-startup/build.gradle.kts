plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

android {
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
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
  // AppWatcher AndroidX Startup installer
  implementation(project(":leakcanary-object-watcher-android-startup"))
  // Plumber AndroidX Startup installer
  implementation(project(":plumber-android-startup"))
}
