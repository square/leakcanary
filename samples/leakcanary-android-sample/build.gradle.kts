plugins {
  id("com.android.application")
}

dependencies {
  debugImplementation(projects.leakcanary.leakcanaryAndroid)
  debugImplementation(projects.leakcanary.leakcanaryAppService)
  // debugImplementation(projects.leakcanary.leakcanaryAndroidStartup)

  // Uncomment to use the :leakcanary process. The setup is covered by
  // :samples:leakcanary-android-process-sample, which builds it on every CI run.
  // debugImplementation(projects.leakcanary.leakcanaryAndroidProcess)
  releaseImplementation(projects.leakcanary.leakcanaryAndroidRelease)
  // Optional
  releaseImplementation(projects.objectWatcher.objectWatcherAndroid)

  implementation(libs.kotlin.stdlib)
  // Uncomment to use WorkManager
  // implementation(libs.androidX.work.runtime)

  androidTestImplementation(projects.leakcanary.leakcanaryAndroidInstrumentation)
  androidTestImplementation(libs.androidX.test.espresso)
  androidTestImplementation(libs.androidX.test.rules)
  androidTestImplementation(libs.androidX.test.runner)
  androidTestImplementation(libs.androidX.test.junit)
  androidTestImplementation(libs.androidX.test.junitKtx)
  androidTestUtil(libs.androidX.test.orchestrator)
}

android {
  compileSdk = libs.versions.androidCompileSdk.get().toInt()

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  defaultConfig {
    applicationId = "com.example.leakcanary"
    minSdk = libs.versions.androidMinSdk.get().toInt()
    targetSdk = libs.versions.androidCompileSdk.get().toInt()

    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Run ./gradlew leakcanary-android-sample:connectedCheck -Porchestrator
    if (project.hasProperty("orchestrator")) {
      testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }
  }

  buildTypes {
    // Build with ./gradlew leakcanary-android-sample:installDebug -Pminify
    if (project.hasProperty("minify")) {
      debug {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      }
    } else {
      debug {
      }
    }
    release {
      signingConfig = signingConfigs["debug"]
    }
  }
  testOptions {
    if (project.hasProperty("orchestrator")) {
      execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
  }
  namespace = "com.example.leakcanary"
  testNamespace = "com.example.leakcanary.test"
  lint {
    disable += "GoogleAppIndexingWarning"
  }
}
