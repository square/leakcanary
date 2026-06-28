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

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class LeakCanaryLeakDeobfuscationPlugin : Plugin<Project> {

  override fun apply(target: Project) = with(target) {
    val androidComponents = extensions.findByType(AndroidComponentsExtension::class.java)
      ?: throwNoAndroidPluginException()

    val leakCanaryExtension = createLeakCanaryExtension(project)
    androidComponents.onVariants { variant ->
      if (!leakCanaryExtension.filterObfuscatedVariants(variant)) return@onVariants
      val mappingFile = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)

      val copyTask = tasks.register(
        /* name = */ variant.computeTaskName("copy", "leakCanaryObfuscationMapping"),
        /* type = */CopyObfuscationMappingFileTask::class.java,
      ) {
        it.variantName.set(variant.name)
        it.mappingFile.set(mappingFile)
        it.outputDir.set(layout.buildDirectory.dir("generated/assets/${variant.name}"))
      }
      variant.sources.assets?.addGeneratedSourceDirectory(
        copyTask,
        CopyObfuscationMappingFileTask::outputDir
      )
    }
  }

  private fun createLeakCanaryExtension(project: Project): LeakCanaryDeobfuscationExtension {
    return project.extensions.create("leakCanary", LeakCanaryDeobfuscationExtension::class.java)
  }

  private fun throwNoAndroidPluginException(): Nothing {
    throw GradleException(
      "LeakCanary deobfuscation plugin can be used only in Android application or library module."
    )
  }
}
