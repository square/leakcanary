package com.squareup.leakcanary.deobfuscation

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
open class CopyObfuscationMappingFileTask : DefaultTask() {

  @Input
  @SkipWhenEmpty
  @PathSensitive(PathSensitivity.RELATIVE)
  lateinit var mappingFile: File

  @Input
  @SkipWhenEmpty
  @PathSensitive(PathSensitivity.RELATIVE)
  lateinit var mergeAssetsDirectory: File

  @get:OutputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val leakCanaryAssetsOutputFile: File
    get() = File(mergeAssetsDirectory, "leakCanaryObfuscationMapping.txt")

  init {
    description = "Puts obfuscation mapping file in assets directory."
  }

  @TaskAction
  fun copyObfuscationMappingFile() {
    if (!mappingFile.exists()) {
      throw GradleException("Missing obfuscation mapping file.")
    }

    if (!mergeAssetsDirectory.exists()) {
      mergeAssetsDirectory.mkdirs()
    }

    if (leakCanaryAssetsOutputFile.exists()) {
      leakCanaryAssetsOutputFile.delete()
    }

    mappingFile.copyTo(leakCanaryAssetsOutputFile)
  }
}
