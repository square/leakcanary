/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
    throw GradleException("TEST RUNNING THE TASK")
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

  private fun validateMergeAssetsDir() {
    val mergeAssetsDir = mergeAssetsDirectory
    if (mergeAssetsDir == null || (!mergeAssetsDir.exists() && !mergeAssetsDir.mkdirs())) {
      throw GradleException("Can't create obfuscation mapping file destination directory.")
    }
  }

  private fun validateOutputFile() {
    if (leakCanaryAssetsOutputFile.exists() && !leakCanaryAssetsOutputFile.delete()) {
      throw GradleException("Can't copy obfuscation mapping file. Previous one still exists.")
    }
  }
}
