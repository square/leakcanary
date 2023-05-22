plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api projects.leakcanary.leakcanaryAndroidCore
  // AppWatcher auto installer
  api projects.objectWatcher.objectWatcherAndroid
  // Plumber auto installer
  implementation projects.plumber.plumberAndroid
  implementation libs.kotlin.stdlib

  androidTestImplementation libs.androidX.test.espresso
  androidTestImplementation libs.androidX.test.rules
  androidTestImplementation libs.androidX.test.runner
  androidTestImplementation libs.assertjCore
  androidTestImplementation projects.shark.sharkHprofTest
}

android {
  compileSdk versions.compileSdk
  defaultConfig {
    minSdk versions.minSdk
    testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
  }
  buildFeatures.buildConfig = false
  namespace 'com.squareup.leakcanary'
  lint {
    checkOnly 'Interoperability'
    disable 'GoogleAppIndexingWarning'
    ignore 'InvalidPackage'
  }
}
