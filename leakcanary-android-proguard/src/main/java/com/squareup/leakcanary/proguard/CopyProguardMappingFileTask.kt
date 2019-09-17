package com.squareup.leakcanary.proguard

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

open class CopyProguardMappingFileTask : DefaultTask() {

  private val assetsOutputDirectory = "${project.buildDir}/generated/assets/"

  @Input var variantDirName: String? = null

  @Input @Optional var mappingFile: File? = null

  @get:OutputDirectory
  val leakCanaryAssetsOutputDirectory: File
    get() = project.file("$assetsOutputDirectory$variantDirName")

  init {
    group = "leakcanaryplugin"
    description = "LeakCanary copy proguard mapping file."
  }

  @TaskAction
  fun copyProguardMappingFile() {
    mappingFile?.let { mappingFile ->
      if (mappingFile.exists()) {
        val destination = File(leakCanaryAssetsOutputDirectory, "leakCanaryProguardMapping.txt")
        if (destination.exists()) {
          destination.delete()
        }
        mappingFile.copyTo(destination)
      }
    }
  }
}
