plugins {
  id("org.jetbrains.kotlin.jvm")
  id("java-gradle-plugin")
  id("com.vanniktech.maven.publish")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

gradlePlugin {
  plugins {
    register("leakCanary") {
      id = "com.squareup.leakcanary.deobfuscation"
      implementationClass =
        "com.squareup.leakcanary.deobfuscation.LeakCanaryLeakDeobfuscationPlugin"
    }
  }

  sourceSets["test"].java.srcDirs("src/test/test-project/src/main/java")
}

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(libs.gradlePlugin.kotlin)
  implementation(libs.gradlePlugin.android)
  compileOnly(gradleApi())

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
}
