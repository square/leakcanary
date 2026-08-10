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
  // So that the owner rule about dependency injection singletons is tested against the providers Dagger
  // and Metro really build, rather than against this repo's idea of them. Runtimes only — no annotation
  // processor and no compiler plugin run here, see DependencyInjectionOwnerTest.
  testImplementation(libs.dagger.runtime)
  testImplementation(libs.metro.runtime)
}
