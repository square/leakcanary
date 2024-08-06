plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.objectWatcher.objectWatcher)
  api(projects.leakcanary.leakcanaryAndroidUtils)

  implementation(libs.curtains)
  implementation(libs.kotlin.stdlib)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.reflect)
}

android {
  resourcePrefix = "leak_canary_watcher_"
  compileSdk = libs.versions.androidCompileSdk.get().toInt()

  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
    consumerProguardFiles("consumer-proguard-rules.pro")
  }

  buildFeatures {
    buildConfig = false
  }
  namespace = "com.squareup.leakcanary.objectwatcher.core"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
  }
}
