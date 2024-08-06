plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.leakcanary.leakcanaryAndroidCore)
  // AppWatcher AndroidX Startup installer
  implementation(projects.objectWatcher.objectWatcherAndroidStartup)
  // Plumber AndroidX Startup installer
  implementation(projects.plumber.plumberAndroidStartup)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
  }
  buildFeatures.buildConfig = false
  namespace = "com.squareup.leakcanary.startup"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    ignore += "InvalidPackage"
  }
}
