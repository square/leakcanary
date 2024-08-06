plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.leakcanary.leakcanaryCore)
  api(projects.leakcanary.leakcanaryTestCore)
  api(projects.shark.sharkAndroid)
  api(libs.androidX.test.uiautomator)

  implementation(libs.androidX.test.monitor)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    targetSdk = libs.versions.androidCompileSdk.get().toInt()
    minSdk = 18
  }
  buildFeatures.buildConfig = false
  namespace = "com.squareup.leakcanary.android.uiautomator"
}
