package com.squareup.leakcanary.deobfuscation

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
open class CopyObfuscationMappingFileTask : DefaultTask() {

  @Input
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
    val mapping = validateMappingFile()

    if (!mergeAssetsDirectory!!.exists() && !mergeAssetsDirectory!!.mkdirs()) {
      throw GradleException("Can't create obfuscation mapping file destination directory.")
    }

    if (leakCanaryAssetsOutputFile.exists() && !leakCanaryAssetsOutputFile.delete()) {
      throw GradleException("Can't copy obfuscation mapping file. Previous one still exists.")
    }
    mapping.copyTo(leakCanaryAssetsOutputFile)
  }

  private fun validateMappingFile(): File {
    val mapping = mappingFile
    if (mapping == null || !mapping.exists()) {
      throw GradleException(
          """
          The plugin was configured to be applied to the variant which doesn't define 
          an obfuscation mapping file: make sure that isMinified is true for this variant.
          """
      )
    }
    return mapping
  }
}
