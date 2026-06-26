plugins {
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
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
// Generate:  ./gradlew :shark:shark-graph:generateBigHeapDump -PdumpFile=/tmp/big.hprof -PobjectCount=120000000 -PgenHeap=12g
// Benchmark: ./gradlew :shark:shark-graph:benchmarkOpenHeapDump -PdumpFile=/tmp/big.hprof -Pmode=default -PbenchHeap=24g
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

tasks.register<JavaExec>("benchmarkOpenHeapDump") {
  group = "benchmark"
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("shark.benchmark.BenchmarkOpenHeapDumpKt")
  maxHeapSize = (project.findProperty("benchHeap") as String?) ?: "24g"
  args(
    (project.findProperty("dumpFile") as String?) ?: "${layout.buildDirectory.get()}/big.hprof",
    (project.findProperty("mode") as String?) ?: "default"
  )
}
