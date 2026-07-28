plugins {
  id("com.android.library")
  id("app.cash.sqldelight")
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
  }
  namespace = "com.squareup.leakcanary.app.db"
}
