plugins {
  id("org.jetbrains.kotlin.jvm")
}

dependencies {
  api(projects.shark.sharkExplorer.sharkExplorerCore)

  implementation(libs.kotlin.stdlib)

  testImplementation(libs.junit)
  testImplementation(libs.assertjCore)
}
