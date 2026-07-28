plugins {
  id("com.android.library")
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
  namespace = "com.squareup.leakcanary.app.aidl"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    disable += "InvalidPackage"
  }
}
