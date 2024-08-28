plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  implementation(libs.kotlin.stdlib)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
  }
  buildFeatures.buildConfig = false
  namespace = "com.squareup.leakcanary.app.service"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    ignore += "InvalidPackage"
  }
}

dependencies {
  implementation(projects.leakcanary.leakcanaryAppAidl)
  implementation(projects.leakcanary.leakcanaryAndroidCore)
  implementation(projects.shark.shark)
  implementation(projects.shark.sharkAndroid)
}
