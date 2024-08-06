plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(projects.shark.shark)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
  }
  buildFeatures {
    aidl = true
  }
  buildFeatures.buildConfig = false
  namespace = "com.squareup.leakcanary.app.aidl"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    ignore += "InvalidPackage"
  }
}
