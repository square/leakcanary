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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NONE
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CopyObfuscationMappingFileTask : DefaultTask() {

  @get:Input
  abstract val variantName: Property<String>

  @get:InputFile
  @get:Optional
  @get:PathSensitive(NONE)
  abstract val mappingFile: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  init {
    description = "Puts obfuscation mapping file in assets directory."
  }

  @TaskAction
  fun copyObfuscationMappingFile() {
    val mappingFile = mappingFile.orNull?.asFile
    if (mappingFile == null || !mappingFile.exists()) {
      throw GradleException(
        "The plugin was configured to be applied to a variant which doesn't define an obfuscation " +
          "mapping file. Make sure that isMinified is true for variant: ${variantName.get()}."
      )
    }
    val outputFile = outputDir.get().asFile
      .also { it.mkdirs() }
      .resolve("leakCanaryObfuscationMapping.txt")
    mappingFile.copyTo(outputFile, overwrite = true)
  }
}
