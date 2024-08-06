plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  // Required to run obfuscated instrumentation tests:
  // ./gradlew leakcanary-android-sample:connectedCheck -Pminify
  id("com.slack.keeper")
}

keeper {
  variantFilter {
    setIgnore(!project.hasProperty("minify"))
  }
}

dependencies {
  debugImplementation(projects.leakcanary.leakcanaryAndroid)
  debugImplementation(projects.leakcanary.leakcanaryAppService)
  // debugImplementation(projects.leakcanary.leakcanaryAndroidStartup)

  // Uncomment to use the :leakcanary process
  // debugImplementation(projects.leakcanary.leakcanaryAndroidProcess)
  releaseImplementation(projects.leakcanary.leakcanaryAndroidRelease)
  // Optional
  releaseImplementation(projects.objectWatcher.objectWatcherAndroid)

  implementation(libs.kotlin.stdlib)
  // Uncomment to use WorkManager
  // implementation(libs.androidX.work.runtime)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)

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
    minSdk = 16
    targetSdk = libs.versions.androidCompileSdk.get().toInt()

    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Run ./gradlew leakcanary-android-sample:connectedCheck -Porchestrator
    if (project.hasProperty("orchestrator")) {
      testInstrumentationRunnerArguments(mapOf("clearPackageData" to "true"))
      testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
      }
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

  dexOptions {
    dexInProcess = false
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }
  namespace = "com.example.leakcanary"
  testNamespace = "com.squareup.leakcanary.instrumentation.test"
  lint {
    disable += "GoogleAppIndexingWarning"
  }
}
