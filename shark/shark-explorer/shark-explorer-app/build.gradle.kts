import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
}

// Compose Multiplatform's artifacts are not built for Java 8, so this module opts out of the
// repo wide Java 8 target set in the root build script. Nothing here is meant to run on Android.
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions.jvmTarget = JVM_17
}

dependencies {
  implementation(projects.shark.sharkExplorer.sharkExplorerCore)

  implementation(compose.desktop.currentOs)
  implementation(compose.material3)

  testImplementation(libs.junit)
  testImplementation(libs.assertjCore)
  // Drives composables headlessly on the JVM, so UI tests need no emulator or display.
  testImplementation(compose.desktop.uiTestJUnit4)
  // Builds the heap dumps the UI tests open, rather than checking binary fixtures in.
  testImplementation(projects.shark.sharkHprofTest)
}

compose.desktop {
  application {
    mainClass = "shark.explorer.app.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "Shark Explorer"
      packageVersion = "1.0.0"
    }
  }
}
