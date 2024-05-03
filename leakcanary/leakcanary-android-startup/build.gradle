plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api projects.leakcanary.leakcanaryAndroidCore
  // AppWatcher AndroidX Startup installer
  implementation projects.objectWatcher.objectWatcherAndroidStartup
  // Plumber AndroidX Startup installer
  implementation projects.plumber.plumberAndroidStartup
}

android {
  compileSdk versions.compileSdk
  defaultConfig {
    minSdk versions.minSdk
  }
  buildFeatures.buildConfig = false
  namespace 'com.squareup.leakcanary.startup'
  lint {
    checkOnly 'Interoperability'
    disable 'GoogleAppIndexingWarning'
    ignore 'InvalidPackage'
  }
}
