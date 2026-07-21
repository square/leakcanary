plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

dependencies {
  implementation(libs.kotlin.stdlib)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
}
