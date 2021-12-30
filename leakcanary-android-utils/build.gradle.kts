plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

android {
  compileSdk = property("compileSdk").toString().toInt()
  defaultConfig {
    minSdk = property("minSdk").toString().toInt()
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

  implementation(libs.kotlin.stdlib)
}
