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

// A heap dump path passed with --args is written relative to the repo, not to this module, which is
// where a JavaExec resolves it from by default. The Compose plugin registers `run` late, hence
// matching by name rather than tasks.named().
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
  workingDir = rootProject.projectDir
}

compose.desktop {
  application {
    mainClass = "shark.explorer.app.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "Shark Explorer"
      packageVersion = "1.0.0"

      // Each platform takes a different container, all three rendered from the one SVG by
      // icons/render-icons.sh.
      //
      // The macOS one also fixes the dock icon of a plain `run`, which is how this app is usually
      // launched: the Compose plugin turns this file into `-Xdock:icon` on the run task, and without
      // it the process shows the default Java icon.
      macOS {
        iconFile.set(project.file("icons/shark-explorer-icon.icns"))
      }
      windows {
        iconFile.set(project.file("icons/shark-explorer-icon.ico"))
      }
      // A .deb takes a PNG, so it reuses the one the window already loads off the classpath.
      linux {
        iconFile.set(project.file("src/main/resources/shark-explorer-icon.png"))
      }
    }
  }
}
