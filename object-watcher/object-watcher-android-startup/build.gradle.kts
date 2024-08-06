plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.objectWatcher.objectWatcherAndroidCore)

  implementation(libs.androidX.startup)
}

android {
  resourcePrefix = "leak_canary_watcher_"
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
  }
  buildFeatures.buildConfig = false
  namespace = "com.squareup.leakcanary.objectwatcher.startup"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    error += "ObsoleteSdkInt"
  }
}
