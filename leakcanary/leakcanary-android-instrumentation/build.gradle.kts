plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.leakcanary.leakcanaryAndroidCore)
  api(projects.leakcanary.leakcanaryAndroidTest)
  api(projects.leakcanary.leakcanaryTestCore)
  api(projects.shark.sharkAndroid)

  implementation(libs.androidX.test.runner)
  implementation(libs.kotlin.stdlib)

  // AppWatcher auto installer for running tests
  androidTestImplementation(projects.objectWatcher.objectWatcherAndroid)
  // Plumber auto installer for running tests
  androidTestImplementation(projects.plumber.plumberAndroid)
  androidTestImplementation(libs.androidX.multidex)
  androidTestImplementation(libs.androidX.test.core)
  androidTestImplementation(libs.androidX.test.espresso)
  androidTestImplementation(libs.androidX.test.rules)
  androidTestImplementation(libs.androidX.fragment)
  androidTestImplementation(libs.assertjCore)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    targetSdk = libs.versions.androidCompileSdk.get().toInt()
    minSdk = libs.versions.androidMinSdk.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    multiDexEnabled = true
  }
  buildFeatures.buildConfig = false
  namespace = "com.squareup.leakcanary.instrumentation"
  testNamespace = "com.squareup.leakcanary.instrumentation.test"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    ignore += "InvalidPackage"
  }
}
