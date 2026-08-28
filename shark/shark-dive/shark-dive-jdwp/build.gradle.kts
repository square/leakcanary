plugins {
  id("org.jetbrains.kotlin.jvm")
}

dependencies {
  api(projects.shark.sharkDive.sharkDiveCore)

  implementation(libs.kotlin.stdlib)

  testImplementation(libs.junit)
  testImplementation(libs.assertjCore)
}
