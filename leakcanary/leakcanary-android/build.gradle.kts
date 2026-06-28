plugins {
  id("com.android.library")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.leakcanary.leakcanaryAndroidCore)
  // AppWatcher auto installer
  api(projects.objectWatcher.objectWatcherAndroid)
  // Plumber auto installer
  implementation(projects.plumber.plumberAndroid)
  implementation(libs.kotlin.stdlib)

  androidTestImplementation(libs.androidX.test.espresso)
  androidTestImplementation(libs.androidX.test.rules)
  androidTestImplementation(libs.androidX.test.runner)
  androidTestImplementation(libs.assertjCore)
  androidTestImplementation(projects.shark.sharkHprofTest)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments["clearPackageData"] = "true"
  }
  namespace = "com.squareup.leakcanary"
  testOptions {
    execution = "ANDROIDX_TEST_ORCHESTRATOR"
    // Avoid DeprecatedTargetSdkVersionDialog / INSTALL_FAILED_DEPRECATED_SDK_VERSION on API 28+
    targetSdk = libs.versions.androidCompileSdk.get().toInt()
  }
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    disable += "InvalidPackage"
  }
}
