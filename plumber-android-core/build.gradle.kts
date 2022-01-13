plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

android {
  resourcePrefix = "leak_canary_plumber"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
    consumerProguardFiles("consumer-proguard-rules.pro")
  }
  buildFeatures.buildConfig = false
  lintOptions {
    disable("GoogleAppIndexingWarning")
    error("ObsoleteSdkInt")
    checkOnly("Interoperability")
  }
}

dependencies {
  api(project(":shark-log"))
  api(project(":leakcanary-android-utils"))

  implementation(libs.kotlin.stdlib)
  implementation(libs.curtains)
  // Optional dependency
  compileOnly(libs.androidX.fragment)
}
