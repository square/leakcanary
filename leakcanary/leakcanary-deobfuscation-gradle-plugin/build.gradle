plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-gradle-plugin")
  id("com.vanniktech.maven.publish")
}

gradlePlugin {
  plugins {
    leakCanary {
      id = 'com.squareup.leakcanary.deobfuscation'
      implementationClass =
        'com.squareup.leakcanary.deobfuscation.LeakCanaryLeakDeobfuscationPlugin'
    }
  }

  sourceSets {
    test.java.srcDirs += 'src/test/test-project/src/main/java'
  }
}

dependencies {
  implementation libs.kotlin.stdlib
  implementation libs.gradlePlugin.kotlin
  implementation libs.gradlePlugin.android
  compileOnly gradleApi()

  testImplementation libs.assertjCore
  testImplementation libs.junit
}

sourceCompatibility = JavaVersion.VERSION_11
targetCompatibility = JavaVersion.VERSION_11

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
      kotlinOptions {
        jvmTarget = '11'
      }
}

