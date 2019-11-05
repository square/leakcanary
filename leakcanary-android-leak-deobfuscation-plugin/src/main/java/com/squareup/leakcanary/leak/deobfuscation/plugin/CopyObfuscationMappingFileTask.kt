package com.squareup.leakcanary.leak.deobfuscation.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
open class CopyObfuscationMappingFileTask : DefaultTask() {

  @Input
  @Optional
  @PathSensitive(PathSensitivity.RELATIVE)
  var mappingFile: File? = null

  @Input
  @PathSensitive(PathSensitivity.RELATIVE)
  var mergeAssetsDirectory: File? = null

  @get:OutputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val leakCanaryAssetsOutputFile: File
    get() = File(mergeAssetsDirectory, "leakCanaryObfuscationMapping.txt")

  init {
    description = "Puts obfuscation mapping file in assets directory."
  }

  @TaskAction
  fun copyObfuscationMappingFile() {
    mappingFile?.let { mappingFile ->
      if (mappingFile.exists()) {
        if (leakCanaryAssetsOutputFile.exists()) {
          leakCanaryAssetsOutputFile.delete()
        }
        mergeAssetsDirectory?.let { dir ->
          if (!dir.exists()) {
            dir.mkdirs()
          }
          mappingFile.copyTo(leakCanaryAssetsOutputFile)
        }
      }
    }
  }
}
