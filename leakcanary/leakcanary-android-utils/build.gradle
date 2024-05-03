plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api projects.leakcanary.leakcanaryCore
  api projects.shark.sharkLog

  implementation libs.kotlin.stdlib
}

android {
  compileSdk versions.compileSdk
  defaultConfig {
    minSdk versions.minSdk
  }
  buildFeatures.buildConfig = false
  namespace 'com.squareup.leakcanary.utils'
  lint {
    checkOnly 'Interoperability'
    disable 'GoogleAppIndexingWarning'
    error 'ObsoleteSdkInt'
  }
}
