import java.util.Properties

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("application")
  id("com.vanniktech.maven.publish")
}

dependencies {
  api(projects.shark.sharkAndroid)

  implementation(libs.clikt)
  implementation(libs.neo4j)
  implementation(libs.jline)
  implementation(libs.kotlin.stdlib)
}

application {
  mainClass.set("shark.MainKt")
}

val generatedVersionDir = "${buildDir}/generated-version"

sourceSets {
  main {
    output.dir(generatedVersionDir, "builtBy" to "generateVersionProperties")
  }
}

tasks.register("generateVersionProperties") {
  doLast {
    val propertiesFile = file("$generatedVersionDir/version.properties")
    propertiesFile.parentFile.mkdirs()
    val properties = Properties()
    properties.setProperty("version_name", rootProject.property("VERSION_NAME").toString())
    propertiesFile.writer().use {
      properties.store(it, null)
    }
  }
}
tasks.named("processResources") {
  dependsOn("generateVersionProperties")
}

