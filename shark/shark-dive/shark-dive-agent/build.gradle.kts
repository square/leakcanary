plugins {
  id("org.jetbrains.kotlin.jvm")
}

dependencies {
  api(projects.shark.sharkDive.sharkDiveCore)

  implementation(libs.kotlin.stdlib)
  // Every operation is a read of the heap dump, and a read is suspending because the app confines it to
  // the heap dump's own thread. See shark.dive.app.HeapDumpSession.
  implementation(libs.coroutines.core)
  // The JsonElement API only, so that no @Serializable class here needs the compiler plugin. The wire
  // format is JSON-RPC, whose shape is decided by the protocol rather than by classes of ours.
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.assertjCore)
  // Builds the heap dumps the tool tests read, rather than checking binary fixtures in.
  testImplementation(projects.shark.sharkHprofTest)
}
