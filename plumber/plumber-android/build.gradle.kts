plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.plumber.plumberAndroidCore)

  implementation(libs.kotlin.stdlib)
}

android {
  resourcePrefix = "leak_canary_plumber"
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
    consumerProguardFiles("consumer-proguard-rules.pro")
  }
  buildFeatures.buildConfig = false
  namespace = "com.squareup.leakcanary.plumber"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    error += "ObsoleteSdkInt"
  }
}
