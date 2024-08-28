import java.io.InputStreamReader

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("app.cash.sqldelight")
  id("com.google.dagger.hilt.android")
  id("kotlin-kapt")
  id("kotlin-parcelize")
}

fun gitSha(): String {
  val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
  return InputStreamReader(process.inputStream).readText().trim()
}

android {
  namespace = "org.leakcanary"
  compileSdk = libs.versions.androidCompileSdk.get().toInt()

  defaultConfig {
    applicationId = "org.leakcanary"
    // 21 required by Compose
    minSdk = 21
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

  kotlinOptions {
    jvmTarget = "1.8"
  }

  buildFeatures {
    compose = true
  }

  composeOptions {
    kotlinCompilerExtensionVersion = "1.4.7"
  }

  packagingOptions {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

dependencies {
  implementation(projects.leakcanary.leakcanaryAppAidl)
  // TODO Move these to ./gradle/libs/versions/toml
  implementation("app.cash.sqldelight:android-driver:2.0.0-alpha05")
  implementation("app.cash.sqldelight:coroutines-extensions:2.0.0-alpha05")
  implementation("androidx.core:core-ktx:1.9.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.5.1")
  implementation("androidx.activity:activity-compose:1.5.1")
  implementation("androidx.compose.ui:ui:${libs.versions.compose.get()}")
  implementation("androidx.compose.ui:ui-tooling-preview:${libs.versions.compose.get()}")
  implementation("androidx.compose.material3:material3:1.0.0-beta02")
  implementation("androidx.sqlite:sqlite-framework:2.2.0")
  implementation("me.saket.extendedspans:extendedspans:1.3.0")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test.ext:junit:1.1.3")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
  androidTestImplementation("androidx.compose.ui:ui-test-junit4:${libs.versions.compose.get()}")
  debugImplementation("androidx.compose.ui:ui-tooling:${libs.versions.compose.get()}")
  debugImplementation("androidx.compose.ui:ui-test-manifest:${libs.versions.compose.get()}")
  // TODO Split out what's included in debug vs the subset for release
  implementation(projects.leakcanary.leakcanaryAndroid)
  implementation("com.google.dagger:hilt-android:2.43.2")
  implementation(libs.okio2)
  kapt("com.google.dagger:hilt-compiler:2.43.2")
}

kapt {
  correctErrorTypes = true
}
