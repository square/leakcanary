package com.squareup.leakcanary.proguard

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class LeakcanaryProguardPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create("leakCanary", LeakCanaryProguardExtension::class.java)

    val androidPluginHandler = { _: Plugin<*> ->
      val android = project.extensions.getByType(AppExtension::class.java)

      project.afterEvaluate {
        android.applicationVariants.all { variant ->

          if (extension.obfuscatedVariantNames?.contains(variant.name) == true) {
            val copyProguardMappingFileTask = project.tasks.create(
                "leakCanaryCopyProguardMappingFor${variant.name.capitalize()}",
                CopyProguardMappingFileTask::class.java
            ) {
              it.variantDirName = variant.dirName
              it.mappingFile = variant.mappingFile
              it.mergeAssetsDirectory = variant.mergeAssetsProvider.get().outputDir.get().asFile
            }

            val mappingGeneratingTask = project.tasks.find { task ->
              task.name.contains("transformClassesAndResourcesWithR8", true)
            } ?: project.tasks.find { task ->
              task.name.contains("transformClassesAndResourcesWithProguard", true)
            }

            mappingGeneratingTask?.let {
              mappingGeneratingTask.finalizedBy(copyProguardMappingFileTask)
            }
          }
        }
      }
    }
    project.plugins.withId("com.android.application", androidPluginHandler)
  }
}
