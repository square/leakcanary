plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api projects.leakcanary.leakcanaryCore
  api projects.leakcanary.leakcanaryAndroidUtils
  api projects.leakcanary.leakcanaryTestCore
  api projects.shark.sharkAndroid

  implementation libs.androidX.test.runner

  androidTestImplementation libs.androidX.multidex
  androidTestImplementation libs.androidX.test.core
  androidTestImplementation libs.androidX.test.runner
  androidTestImplementation libs.assertjCore
}

android {
  compileSdk versions.compileSdk
  defaultConfig {
    targetSdk versions.compileSdk
    minSdk versions.minSdk
    testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    multiDexEnabled true
  }
  buildFeatures.buildConfig = false
  namespace 'com.squareup.leakcanary.android.test'
  testNamespace 'com.squareup.leakcanary.android.test.test'
}
