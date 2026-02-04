plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
  api(projects.shark.sharkLog)

  implementation(libs.kotlin.stdlib)
  // compileOnly ensures this dependency is not exposed through this artifact's pom.xml in Maven Central.
  // Okio is a required dependency, but we're making it required on the "shark" artifact which is the main artifact that
  // should generally be used. The shark artifact depends on Okio 2.x (ensure compatibility with modern Okio).
  compileOnly(libs.okio2)
  testImplementation(libs.okio2)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  testImplementation(projects.shark.sharkTest)
}
