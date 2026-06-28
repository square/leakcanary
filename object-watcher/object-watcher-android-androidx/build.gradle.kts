plugins {
  id("com.android.library")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.objectWatcher.objectWatcherAndroidCore)

  implementation(libs.kotlin.stdlib)
  // Optional dependency
  compileOnly(libs.androidX.fragment)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
    consumerProguardFiles("consumer-proguard-rules.pro")
  }
  namespace = "com.squareup.leakcanary.fragments.androidx"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    error += "ObsoleteSdkInt"
  }
}
