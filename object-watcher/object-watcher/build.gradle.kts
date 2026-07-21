plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

dependencies {
  implementation(libs.kotlin.stdlib)
  api(projects.shark.sharkLog)
  api(projects.leakcanary.leakcanaryGc)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
}
