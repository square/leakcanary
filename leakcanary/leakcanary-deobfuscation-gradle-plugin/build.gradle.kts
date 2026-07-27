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

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(libs.gradlePlugin.kotlin)
  implementation(libs.gradlePlugin.android)
  compileOnly(gradleApi())

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
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
