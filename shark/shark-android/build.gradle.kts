import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

// mockito-kotlin ships Java 11 bytecode, and its `mock()`, `whenever()` and `verify()` are inline
// functions, which the Kotlin compiler refuses to inline into Java 8 bytecode. Only what we publish
// has to stay on Java 8: unit tests only ever run on the JDK that builds the project.
tasks.named<KotlinCompile>("compileTestKotlin") {
  compilerOptions.jvmTarget = JvmTarget.JVM_11
}

// The Kotlin plugin checks that the Java and Kotlin test compilations agree on their target.
tasks.named<JavaCompile>("compileTestJava") {
  sourceCompatibility = JavaVersion.VERSION_11.toString()
  targetCompatibility = JavaVersion.VERSION_11.toString()
}

dependencies {
  api(projects.shark.shark)

  implementation(libs.kotlin.stdlib)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinStatistics)
  testImplementation(libs.mockito)
  testImplementation(libs.mockitoKotlin)
  testImplementation(libs.okio2)
  testImplementation(projects.shark.sharkTest)
  testImplementation(projects.shark.sharkHprofTest)
}
