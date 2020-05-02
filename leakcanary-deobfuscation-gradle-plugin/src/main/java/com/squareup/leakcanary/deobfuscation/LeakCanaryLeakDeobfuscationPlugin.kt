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

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.DomainObjectSet
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.plugins.AppliedPlugin
import org.gradle.api.tasks.TaskProvider

class LeakCanaryLeakDeobfuscationPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    val leakCanaryPluginAction = Action<AppliedPlugin> {
      val leakCanaryExtension = createLeakCanaryExtension(project)
      val variants = findAndroidVariants(project)
      variants.all { variant ->
        if (leakCanaryExtension.filterObfuscatedVariants(variant)) {
          setupTasks(project, variant)
        }
      }
    }

    project.pluginManager.withPlugin("com.android.application", leakCanaryPluginAction)
    project.pluginManager.withPlugin("com.android.library", leakCanaryPluginAction)
  }

  private fun findAndroidVariants(project: Project): DomainObjectSet<BaseVariant> {
    return try {
      when (val extension = project.extensions.getByType(BaseExtension::class.java)) {
        is AppExtension -> extension.applicationVariants as DomainObjectSet<BaseVariant>
        is LibraryExtension -> extension.libraryVariants as DomainObjectSet<BaseVariant>
        else -> throwNoAndroidPluginException()
      }
    } catch (e: Exception) {
      throwNoAndroidPluginException()
    }
  }

  private fun createLeakCanaryExtension(project: Project): LeakCanaryDeobfuscationExtension {
    return project.extensions.create("leakCanary", LeakCanaryDeobfuscationExtension::class.java)
  }

  private fun setupTasks(
    project: Project,
    variant: BaseVariant
  ) {
    val copyObfuscationMappingFileTaskProvider = project.tasks.register(
      "leakCanaryCopyObfuscationMappingFor${variant.name.capitalize()}",
      CopyObfuscationMappingFileTask::class.java
    ) {
      it.variantName = variant.name
      it.mappingFile = variant.mappingFile
      it.mergeAssetsDirectory = variant.mergeAssetsProvider.get().outputDir.get().asFile

      val mappingGeneratingTaskProvider =
        findTaskProviderOrNull(
            project,
            "transformClassesAndResourcesWithR8For${variant.name.capitalize()}"
        ) ?: findTaskProviderOrNull(
            project,
            "transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}"
        ) ?: findTaskProviderOrNull(
            project,
            "minify${variant.name.capitalize()}WithR8"
        ) ?: findTaskProviderOrNull(
            project,
            "minify${variant.name.capitalize()}WithProguard"
        ) ?: throwMissingMinifiedVariantException()

      it.dependsOn(mappingGeneratingTaskProvider)
      it.dependsOn(variant.mergeAssetsProvider)
    }

    getPackageTaskProvider(variant).configure {
      it.dependsOn(copyObfuscationMappingFileTaskProvider)
    }
  }

  private fun findTaskProviderOrNull(
    project: Project,
    taskName: String
  ): TaskProvider<Task>? {
    return try {
      project.tasks.named(taskName)
    } catch (proguardTaskNotFoundException: UnknownTaskException) {
      null
    }
  }

  private fun getPackageTaskProvider(variant: BaseVariant): TaskProvider<out DefaultTask> {
    return when (variant) {
      is LibraryVariant -> variant.packageLibraryProvider
      is ApplicationVariant -> variant.packageApplicationProvider
      else -> throwNoAndroidPluginException()
    }
  }

  private fun throwNoAndroidPluginException(): Nothing {
    throw GradleException(
      "LeakCanary deobfuscation plugin can be used only in Android application or library module."
    )
  }

  private fun throwMissingMinifiedVariantException(): Nothing {
    throw GradleException(
      """
        LeakCanary deobfuscation plugin couldn't find any variant with minification enabled.
        Please make sure that there is at least 1 minified variant in your project. 
      """
    )
  }
}
