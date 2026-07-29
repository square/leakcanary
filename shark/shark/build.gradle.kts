plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.shark.sharkGraph)

  implementation(libs.kotlin.stdlib)
  implementation(libs.okio2)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  testImplementation(projects.shark.sharkTest)
  testImplementation(projects.shark.sharkHprofTest)
}

// Throwaway harness for issue #2777 analysis. Not part of CI.
tasks.register<JavaExec>("analyzeBigHeapDump") {
  group = "benchmark"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("shark.benchmark.AnalyzeBigHeapDumpKt")
  maxHeapSize = (project.findProperty("benchHeap") as String?) ?: "24g"
  args((project.findProperty("dumpFile") as String?) ?: "${layout.buildDirectory.get()}/big.hprof")
}
