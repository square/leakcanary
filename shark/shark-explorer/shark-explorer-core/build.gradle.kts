plugins {
  id("org.jetbrains.kotlin.jvm")
}

dependencies {
  // shark-android rather than shark so that Android heap dumps get the reference readers and
  // ignored reference matchers they need, the same way shark-cli depends on it.
  api(projects.shark.sharkAndroid)

  implementation(libs.kotlin.stdlib)

  testImplementation(libs.junit)
  testImplementation(libs.assertjCore)
  testImplementation(projects.shark.sharkHprofTest)
}
