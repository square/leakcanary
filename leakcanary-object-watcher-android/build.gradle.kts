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

  lintOptions {
    disable("GoogleAppIndexingWarning")
    checkOnly("Interoperability")
  }

  buildFeatures.buildConfig = false
}

dependencies {
  api(project(":leakcanary-object-watcher-android-core"))
}
