plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

android {
  resourcePrefix = "leak_canary_watcher_"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
    consumerProguardFiles("consumer-proguard-rules.pro")
  }
  buildFeatures.buildConfig = false

  lintOptions {
    disable("GoogleAppIndexingWarning")
    checkOnly("Interoperability")
  }
}

dependencies {
  api(project(":leakcanary-object-watcher"))
  api(project(":leakcanary-android-utils"))

  implementation(libs.curtains)
  implementation(libs.kotlin.stdlib)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.reflect)
}
