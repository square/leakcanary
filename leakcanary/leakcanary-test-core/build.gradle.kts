plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.leakcanary.leakcanaryCore)
  api(projects.shark.shark)
  api(libs.junit)

  testImplementation(libs.assertjCore)
}
