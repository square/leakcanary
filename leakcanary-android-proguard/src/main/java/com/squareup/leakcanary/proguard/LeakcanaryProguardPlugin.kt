package com.squareup.leakcanary.proguard

import com.android.build.gradle.AppExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.TaskProvider

class LeakcanaryProguardPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create("leakCanary", LeakCanaryProguardExtension::class.java)

    val androidPluginHandler = { _: Plugin<*> ->
      val android = project.extensions.getByType(AppExtension::class.java)

      android.applicationVariants.all { variant ->

        if (extension.obfuscatedVariantNames?.contains(variant.name) == true) {
          val copyProguardMappingFileTaskProvider = project.tasks.register(
              "leakCanaryCopyProguardMappingFor${variant.name.capitalize()}",
              CopyProguardMappingFileTask::class.java
          ) {
            it.variantDirName = variant.dirName
            it.mappingFile = variant.mappingFile
            it.mergeAssetsDirectory = variant.mergeAssetsProvider.get()
                .outputDir.get()
                .asFile
          }

          val mappingGeneratingTaskProvider =
            findTaskProviderOrNull(
                project,
                "transformClassesAndResourcesWithR8For${variant.name.capitalize()}"
            ) ?: findTaskProviderOrNull(
                project,
                "transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}"
            )

          variant.packageApplicationProvider.configure {
            it.dependsOn(copyProguardMappingFileTaskProvider)
          }
          copyProguardMappingFileTaskProvider.configure { copyProguardMappingFileTask ->
            mappingGeneratingTaskProvider?.let { copyProguardMappingFileTask.dependsOn(it) }
          }
        }
      }
    }

    if (project.plugins.hasPlugin("com.android.application")) {
      project.plugins.withId("com.android.application", androidPluginHandler)
    } else {
      throw GradleException("This plugin can only be applied in android application modules.")
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
}
