plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

android {
  resourcePrefix = "leak_canary_"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig {
    minSdk = 16
    buildConfigField("String", "LIBRARY_VERSION", "\"${property("VERSION_NAME")}\"")
    consumerProguardFiles("consumer-proguard-rules.pro")
  }
  lintOptions {
    disable("GoogleAppIndexingWarning")
    error("ObsoleteSdkInt")
    checkOnly("Interoperability")
  }
}

dependencies {
  api(project(":shark-android"))
  api(project(":leakcanary-android-utils"))

  implementation(libs.kotlin.stdlib)
  implementation(libs.okio2)
}
