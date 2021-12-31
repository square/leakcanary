plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

android {
  resourcePrefix = "leak_canary_"
  compileSdk = property("compileSdk").toString().toInt()
  defaultConfig {
    minSdk = property("minSdk").toString().toInt()
    buildConfigField("String", "LIBRARY_VERSION", "\"${property("VERSION_NAME")}\"")
    consumerProguardFiles("consumer-proguard-rules.pro")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  lintOptions {
    disable("GoogleAppIndexingWarning")
    error("ObsoleteSdkInt")
    checkOnly("Interoperability")
  }
}

dependencies {
  api(project(":shark-android"))
  api(project(":leakcanary-object-watcher-android-core"))
  api(project(":leakcanary-object-watcher-android-androidx"))
  api(project(":leakcanary-object-watcher-android-support-fragments"))
  implementation(libs.kotlin.stdlib)

  // Optional dependency
  compileOnly(libs.androidX.work.runtime)
  compileOnly(libs.androidX.work.multiprocess)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.reflect)
  testImplementation(libs.mockito)
  testImplementation(libs.mockitoKotlin)
  // AppWatcher auto installer for running tests
  androidTestImplementation(project(":leakcanary-object-watcher-android"))
  androidTestImplementation(libs.androidX.test.espresso)
  androidTestImplementation(libs.androidX.test.rules)
  androidTestImplementation(libs.androidX.test.runner)
  androidTestImplementation(project(":shark-hprof-test"))
}
