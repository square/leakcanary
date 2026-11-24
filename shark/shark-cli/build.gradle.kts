import java.util.Properties
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("application")
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

// The default Windows start script lists every jar in lib/ on a single `set CLASSPATH=...` line,
// which exceeds cmd.exe's 8,191-character limit once %APP_HOME% expands. Use a wildcard classpath
// so Java expands lib/ itself.
tasks.named<CreateStartScripts>("startScripts") {
  doLast {
    val original = windowsScript.readText()
    val updated = original.replace(Regex("(?m)^set CLASSPATH=[^\\r\\n]*")) {
      "set CLASSPATH=%APP_HOME%\\lib\\*"
    }
    check(updated != original) {
      "Failed to replace CLASSPATH in ${windowsScript.name}"
    }
    windowsScript.writeText(updated)
  }
}
