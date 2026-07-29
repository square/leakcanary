plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.shark.sharkHprof)
  api(libs.androidX.collections)

  implementation(libs.kotlin.stdlib)
  implementation(libs.okio2)

  testImplementation(libs.assertjCore)
  testImplementation(libs.junit)
  testImplementation(projects.shark.sharkTest)
  testImplementation(projects.shark.sharkHprofTest)
}

// Throwaway benchmark harness for very large heap dumps (see shark.benchmark.*). Not part of CI.
// Generate: ./gradlew :shark:shark-graph:generateBigHeapDump -PdumpFile=/tmp/big.hprof -PobjectCount=120000000 -PgenHeap=12g
// Probe:    ./gradlew :shark:shark-graph:probeHashSetCeiling -PexpectedElements=459305079 -PprobeHeap=24g
tasks.register<JavaExec>("generateBigHeapDump") {
  group = "benchmark"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("shark.benchmark.GenerateBigHeapDumpKt")
  maxHeapSize = (project.findProperty("genHeap") as String?) ?: "12g"
  args(
    (project.findProperty("dumpFile") as String?) ?: "${layout.buildDirectory.get()}/big.hprof",
    (project.findProperty("objectCount") as String?) ?: "120000000"
  )
}

tasks.register<JavaExec>("probeHashSetCeiling") {
  group = "benchmark"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("shark.benchmark.HashSetCeilingProbeKt")
  maxHeapSize = (project.findProperty("probeHeap") as String?) ?: "24g"
  args((project.findProperty("expectedElements") as String?) ?: "459305079")
}

tasks.register<JavaExec>("generateChainedHeapDump") {
  group = "benchmark"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("shark.benchmark.GenerateChainedHeapDumpKt")
  maxHeapSize = (project.findProperty("genHeap") as String?) ?: "40g"
  args(
    (project.findProperty("dumpFile") as String?) ?: "${layout.buildDirectory.get()}/chained.hprof",
    (project.findProperty("objectCount") as String?) ?: "900000000",
    (project.findProperty("chunkSize") as String?) ?: "900000"
  )
}
