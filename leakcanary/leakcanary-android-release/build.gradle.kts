import java.io.InputStreamReader

plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.shark.sharkAndroid)
  api(projects.leakcanary.leakcanaryAndroidUtils)

  implementation(libs.kotlin.stdlib)
  implementation(libs.okio2)
}

fun gitSha(): String {
  val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
  return InputStreamReader(process.inputStream).readText().trim()
}

android {
  resourcePrefix = "leak_canary_"
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = 16
    buildConfigField("String", "LIBRARY_VERSION", "\"${rootProject.property("VERSION_NAME")}\"")
    buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
    consumerProguardFiles("consumer-proguard-rules.pro")
  }
  namespace = "com.squareup.leakcanary.release"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    error += "ObsoleteSdkInt"
  }
}
