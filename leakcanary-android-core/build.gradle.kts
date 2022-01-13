plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

android {
  resourcePrefix = "leak_canary_"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
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
  androidTestImplementation(libs.androidX.test.espresso)
  androidTestImplementation(libs.androidX.test.rules)
  androidTestImplementation(libs.androidX.test.runner)
  androidTestImplementation(libs.assertjCore)
  androidTestImplementation(project(":shark-hprof-test"))
}
