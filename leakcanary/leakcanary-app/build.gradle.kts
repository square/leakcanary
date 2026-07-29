import java.io.InputStreamReader
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("com.google.dagger.hilt.android")
  id("com.google.devtools.ksp")
  id("kotlin-parcelize")
}

fun gitSha(): String {
  val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
  return InputStreamReader(process.inputStream).readText().trim()
}

android {
  namespace = "org.leakcanary"
  compileSdk = libs.versions.androidCompileSdk.get().toInt()
  buildFeatures.buildConfig = true

  defaultConfig {
    applicationId = "org.leakcanary"
    // 26 minimum SDK for modern Android features
    minSdk = 26
    targetSdk = libs.versions.androidCompileSdk.get().toInt()

    buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")

    // TODO Figure out versioning scheme. Should this follow LeakCanary releases?
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables {
      useSupportLibrary = true
    }
  }

  buildTypes {
    debug {
    }
    release {
      // TODO Enable R8 minification
      // minifyEnabled true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // TODO Proper signing config
      signingConfig = signingConfigs["debug"]
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  buildFeatures {
    compose = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  compilerOptions.jvmTarget = JVM_1_8
}

dependencies {
  implementation(projects.leakcanary.leakcanaryAppAidl)
  implementation(projects.leakcanary.leakcanaryAppDb)
  implementation(libs.sqldelight.android)
  implementation(libs.sqldelight.coroutines)
  implementation(libs.androidX.core)
  implementation(libs.androidX.lifecycle.runtime)
  implementation(libs.androidX.lifecycle.viewModelCompose)
  implementation(libs.androidX.activity.compose)
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.toolingPreview)
  implementation(libs.compose.material3)
  implementation(libs.compose.materialIcons)
  implementation(libs.androidX.sqlite.framework)
  implementation(libs.extendedSpans)
  debugImplementation(libs.compose.ui.tooling)
  // TODO Split out what's included in debug vs the subset for release
  implementation(projects.leakcanary.leakcanaryAndroid)
  implementation(libs.hilt.android)
  implementation(libs.okio2)
  ksp(libs.hilt.compiler)
}
