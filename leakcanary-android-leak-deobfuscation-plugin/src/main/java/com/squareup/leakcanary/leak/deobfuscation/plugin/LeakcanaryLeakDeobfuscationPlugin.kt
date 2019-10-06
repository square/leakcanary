package com.squareup.leakcanary.leak.deobfuscation.plugin

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.TaskProvider

class LeakcanaryLeakDeobfuscationPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val leakCanaryExtension =
      project.extensions.create("leakCanary", LeakCanaryDeobfuscationExtension::class.java)

    val androidAppPluginHandler = { _: Plugin<*> ->
      val appExtension = project.extensions.getByType(AppExtension::class.java)
      appExtension.applicationVariants.all { variant ->
        setupPlugin(project, variant, leakCanaryExtension)
      }
    }

    val androidLibraryPluginHandler = { _: Plugin<*> ->
      val libraryExtension = project.extensions.getByType(LibraryExtension::class.java)
      libraryExtension.libraryVariants.all { variant ->
        setupPlugin(project, variant, leakCanaryExtension)
      }
    }

    when {
      project.plugins.hasPlugin("com.android.application") -> {
        project.plugins.withId("com.android.application", androidAppPluginHandler)
      }
      project.plugins.hasPlugin("com.android.library") -> {
        project.plugins.withId("com.android.library", androidLibraryPluginHandler)
      }
      else -> {
        throw GradleException(
            "This plugin can only be applied in Android application or library modules."
        )
      }
    }
  }

  private fun setupPlugin(
    project: Project,
    variant: BaseVariant,
    leakCanaryExtension: LeakCanaryDeobfuscationExtension
  ) {
    if (leakCanaryExtension.obfuscatedVariantNames?.contains(variant.name) == true) {
      val copyObfuscationMappingFileTaskProvider = project.tasks.register(
          "leakCanaryCopyObfuscationMappingFor${variant.name.capitalize()}",
          CopyObfuscationMappingFileTask::class.java
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

      getPackageTaskProvider(variant)?.configure {
        it.dependsOn(copyObfuscationMappingFileTaskProvider)
      }
      copyObfuscationMappingFileTaskProvider.configure { copyProguardMappingFileTask ->
        mappingGeneratingTaskProvider?.let { copyProguardMappingFileTask.dependsOn(it) }
      }
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

  private fun getPackageTaskProvider(variant: BaseVariant): TaskProvider<out DefaultTask>? {
    return when (variant) {
      is LibraryVariant -> {
        variant.packageLibraryProvider
      }
      is ApplicationVariant -> {
        variant.packageApplicationProvider
      }
      else -> {
        null
      }
    }
  }
}
