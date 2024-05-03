plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api projects.shark.sharkAndroid
  api projects.objectWatcher.objectWatcherAndroidCore
  api projects.objectWatcher.objectWatcherAndroidAndroidx
  api projects.leakcanary.leakcanaryAndroidUtils
  implementation libs.kotlin.stdlib

  // Optional dependency
  compileOnly libs.androidX.work.runtime
  compileOnly libs.androidX.work.multiprocess

  testImplementation libs.assertjCore
  testImplementation libs.junit
  testImplementation libs.kotlin.reflect
  testImplementation libs.mockito
  testImplementation libs.mockitoKotlin
  androidTestImplementation libs.androidX.test.espresso
  androidTestImplementation libs.androidX.test.rules
  androidTestImplementation libs.androidX.test.runner
  androidTestImplementation libs.assertjCore
  androidTestImplementation projects.shark.sharkHprofTest
  androidTestUtil libs.androidX.test.orchestrator
}

def gitSha() {
  return 'git rev-parse --short HEAD'.execute().text.trim()
}

android {
  resourcePrefix 'leak_canary_'
  compileSdk versions.compileSdk
  defaultConfig {
    minSdk versions.minSdk
    // Avoid DeprecatedTargetSdkVersionDialog during UI tests
    targetSdk versions.compileSdk
    buildConfigField "String", "LIBRARY_VERSION", "\"${rootProject.ext.VERSION_NAME}\""
    buildConfigField "String", "GIT_SHA", "\"${gitSha()}\""
    consumerProguardFiles 'consumer-proguard-rules.pro'
    testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"

    testInstrumentationRunnerArguments clearPackageData: 'true'
    testOptions {
      execution 'ANDROIDX_TEST_ORCHESTRATOR'
    }
  }
  namespace 'com.squareup.leakcanary.core'
  testNamespace 'com.squareup.leakcanary.core.test'
  lint {
    checkOnly 'Interoperability'
    disable 'GoogleAppIndexingWarning'
    error 'ObsoleteSdkInt'
  }
}
