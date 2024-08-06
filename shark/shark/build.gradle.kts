plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
  api(projects.shark.sharkGraph)

  implementation(libs.coroutines.core)
  implementation(libs.kotlin.stdlib)
  implementation(libs.okio2)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  testImplementation(projects.shark.sharkTest)
  testImplementation(projects.shark.sharkHprofTest)
}
