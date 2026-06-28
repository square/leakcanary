plugins {
  id("com.android.library")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.leakcanary.leakcanaryCore)
  api(projects.leakcanary.leakcanaryTestCore)
  api(projects.shark.sharkAndroid)
  api(libs.androidX.test.uiautomator)

  implementation(libs.androidX.test.monitor)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
  }
  namespace = "com.squareup.leakcanary.android.uiautomator"
  testOptions {
    targetSdk = libs.versions.androidCompileSdk.get().toInt()
  }
}
