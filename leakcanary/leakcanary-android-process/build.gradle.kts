plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.shark.sharkLog)
  api(projects.objectWatcher.objectWatcherAndroidCore)

  implementation(libs.kotlin.stdlib)
  implementation(libs.androidX.work.multiprocess)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
  }
  buildFeatures.buildConfig = false
  namespace = "com.squareup.leakcanary"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    ignore += "InvalidPackage"
  }
}
