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

android {
  compileSdk = property("compileSdk").toString().toInt()

  defaultConfig {
    applicationId = "com.example.leakcanary"
    minSdk = 16
    targetSdk = property("compileSdk").toString().toInt()

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

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  buildTypes {
    // Build with ./gradlew leakcanary-android-sample:installDebug -Pminify
    getByName("debug") {
      isMinifyEnabled = project.hasProperty("minify")
      proguardFiles("proguard-android-optimize.txt")
    }
    getByName("release") {
      signingConfig = signingConfigs["debug"]
    }
  }

  dexOptions {
    dexInProcess = false
  }

  lintOptions {
    disable("GoogleAppIndexingWarning")
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }
}

dependencies {
  debugImplementation(project(":leakcanary-android"))
  // Uncomment to use the :leakcanary process
  // debugImplementation(project(":leakcanary-android-process"))
  releaseImplementation(project(":leakcanary-android-release"))
  // Optional
  releaseImplementation(project(":leakcanary-object-watcher-android"))

  implementation(libs.kotlin.stdlib)
  // Uncomment to use WorkManager
  // implementation(libs.androidX.work.runtime)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)

  androidTestImplementation(project(":leakcanary-android-instrumentation"))
  androidTestImplementation(libs.androidX.test.espresso)
  androidTestImplementation(libs.androidX.test.rules)
  androidTestImplementation(libs.androidX.test.runner)
  androidTestImplementation(libs.androidX.test.junit)
  androidTestImplementation(libs.androidX.test.junitKtx)
  androidTestUtil(libs.androidX.test.orchestrator)
}
