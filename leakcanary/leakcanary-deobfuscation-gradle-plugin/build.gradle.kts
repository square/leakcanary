import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-gradle-plugin")
  id("com.vanniktech.maven.publish")
}

gradlePlugin {
  plugins {
    register("leakCanary") {
      id = "com.squareup.leakcanary.deobfuscation"
      implementationClass =
        "com.squareup.leakcanary.deobfuscation.LeakCanaryLeakDeobfuscationPlugin"
    }
  }

  sourceSets {
    test {
      java.srcDirs.add(file("src/test/test-project/src/main/java"))
    }
  }
}

val agpTestClasspath: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  implementation(libs.kotlin.stdlib)
  // The Android Gradle Plugin is always on the buildscript classpath of a project that applies this
  // plugin, so depend on it at compile time only. Declaring it as `implementation` would publish it
  // as a runtime dependency and force our version onto everyone applying the plugin.
  compileOnly(libs.gradlePlugin.android)
  compileOnly(gradleApi())

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  agpTestClasspath(libs.gradlePlugin.android)
}

// GradleRunner.withPluginClasspath() derives the classpath from the main source set, where the
// Android plugin is now compileOnly. The generated test project applies it, so add it back.
tasks.withType<PluginUnderTestMetadata>().configureEach {
  pluginClasspath.from(agpTestClasspath)
}

tasks.withType<Test>().configureEach {
  // The functional tests generate an Android project, build it with the same SDK versions as the
  // rest of the repo so that no extra platform needs to be installed.
  systemProperty("androidCompileSdk", libs.versions.androidCompileSdk.get())
  systemProperty("androidMinSdk", libs.versions.androidMinSdk.get())
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
  compilerOptions.jvmTarget = JvmTarget.JVM_11
}
