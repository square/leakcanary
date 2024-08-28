import java.io.InputStreamReader

plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.shark.sharkAndroid)
  api(projects.objectWatcher.objectWatcherAndroidCore)
  api(projects.objectWatcher.objectWatcherAndroidAndroidx)
  api(projects.leakcanary.leakcanaryAndroidUtils)
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
  androidTestImplementation(projects.shark.sharkHprofTest)
  androidTestUtil(libs.androidX.test.orchestrator)
}

fun gitSha(): String {
  val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
  return InputStreamReader(process.inputStream).readText().trim()
}

android {
  resourcePrefix = "leak_canary_"
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
    // Avoid DeprecatedTargetSdkVersionDialog during UI tests
    targetSdk = libs.versions.androidCompileSdk.get().toInt()
    buildConfigField("String", "LIBRARY_VERSION", "\"${rootProject.property("VERSION_NAME")}\"")
    buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
    consumerProguardFiles("consumer-proguard-rules.pro")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    testInstrumentationRunnerArguments(
      mapOf(
        "clearPackageData" to "true",
      )
    )
    testOptions {
      execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
  }
  namespace = "com.squareup.leakcanary.core"
  testNamespace = "com.squareup.leakcanary.core.test"
  lint {
    checkOnly += "Interoperability"
    disable += "GoogleAppIndexingWarning"
    error += "ObsoleteSdkInt"
  }
}
