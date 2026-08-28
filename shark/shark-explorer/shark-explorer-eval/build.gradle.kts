plugins {
  id("org.jetbrains.kotlin.jvm")
  id("application")
}

dependencies {
  // The session record an agent leaves behind, which is the whole of what a run is scored from. Nothing
  // here talks to a model or to the tools: the agent's client does that, over this module's heap dumps.
  implementation(projects.shark.sharkExplorer.sharkExplorerAgent)

  implementation(libs.kotlin.stdlib)
  // In the main source set, unlike every other module here, because writing the scenarios *is* what this
  // module does. Which is the reason this is a module of its own: a dump built by the DSL cannot be a
  // dependency of anything the app ships.
  implementation(projects.shark.sharkHprofTest)

  testImplementation(libs.junit)
  testImplementation(libs.assertjCore)
}

application {
  mainClass.set("shark.explorer.eval.EvalMainKt")
}
