plugins {
  id("com.android.library")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.plumber.plumberAndroidCore)

  implementation(libs.kotlin.stdlib)
  implementation(libs.androidX.startup)
}

android {
  resourcePrefix = "leak_canary_plumber"
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
  }
  namespace = "com.squareup.leakcanary.plumber.startup"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    error += "ObsoleteSdkInt"
  }
}
