import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    jvmTarget = "11"
  }
}

