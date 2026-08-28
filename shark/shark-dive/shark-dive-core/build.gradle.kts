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

/**
 * Puts the reference on the classpath, which is where [shark.dive.ReferencePage] reads it.
 *
 * Copied rather than kept here, because the same files are the website's: `docs/shark-dive-reference.md`
 * includes every one of them, so there is one copy of every sentence and no way for the page a `?` leads to
 * and the page the site publishes to drift apart. See `exclude_docs` in `mkdocs.yml`.
 *
 * Shipped inside the build rather than opened in a browser so that a release shows the reference it was
 * built with: a link to the site would have an old release explaining itself with a page written about a
 * newer one. Same reasoning as `writeVersionResource` in shark-dive-app.
 */
val copyReference by tasks.registering(Sync::class) {
  from(rootProject.layout.projectDirectory.dir("docs/shark-dive-reference"))
  into(layout.buildDirectory.dir("generated/reference/shark-dive-reference"))
}

sourceSets.main {
  resources.srcDir(copyReference.map { it.destinationDir.parentFile })
}

tasks.test {
  // Where the page that publishes the reference is, so that `ReferenceTest` can hold it to including every
  // topic. The only thing here that reads the repository rather than the classpath.
  systemProperty("shark.dive.referencePage", rootProject.file("docs/shark-dive-reference.md").path)
}
