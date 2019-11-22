package com.squareup.leakcanary.deobfuscation

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.DefaultTask
import org.gradle.api.DomainObjectSet
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.TaskProvider
import java.io.File

class LeakcanaryLeakDeobfuscationPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val androidPluginHandler = { _: Plugin<*> ->
      val extension = project.extensions.getByType(BaseExtension::class.java)
      val variants: DomainObjectSet<BaseVariant> = when (extension) {
        is AppExtension -> {
          extension.applicationVariants as DomainObjectSet<BaseVariant>
        }
        is LibraryExtension -> {
          extension.libraryVariants as DomainObjectSet<BaseVariant>
        }
        else -> throw GradleException(
            "This plugin can only be applied in Android application or library modules."
        )
      }
      val leakCanaryExtension = project.extensions.create(
          "leakCanary",
          LeakCanaryDeobfuscationExtension::class.java
      )

      variants.all { variant ->
        val obfuscatedVariant =
          leakCanaryExtension.filterObfuscatedVariants?.invoke(variant) ?: false
        if (obfuscatedVariant || variant.buildType.isMinifyEnabled) {
          setupPlugin(project, variant)
        }
      }
    }

    when {
      project.plugins.hasPlugin("com.android.application") ->
        project.plugins.withId("com.android.application", androidPluginHandler)
      project.plugins.hasPlugin("com.android.library") ->
        project.plugins.withId("com.android.library", androidPluginHandler)
      else -> throw GradleException(
          "This plugin can only be applied in Android application or library modules."
      )
    }
  }

  private fun setupPlugin(
    project: Project,
    variant: BaseVariant
  ) {
    val copyObfuscationMappingFileTaskProvider = project.tasks.register(
        "leakCanaryCopyObfuscationMappingFor${variant.name.capitalize()}",
        CopyObfuscationMappingFileTask::class.java
    ) {
      val empty = File("empty")
      it.variantDirName = variant.dirName
      it.mappingFile = variant.mappingFile ?: empty
      it.mergeAssetsDirectory = variant.mergeAssetsProvider.get().outputDir.get().asFile ?: empty
    }

    getPackageTaskProvider(variant).configure {
      it.dependsOn(copyObfuscationMappingFileTaskProvider)
    }

    copyObfuscationMappingFileTaskProvider.configure { copyProguardMappingFileTask ->
      val mappingGeneratingTaskProvider =
        findTaskProviderOrNull(
            project,
            "transformClassesAndResourcesWithR8For${variant.name.capitalize()}"
        ) ?: findTaskProviderOrNull(
            project,
            "transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}"
        )

      mappingGeneratingTaskProvider?.let { copyProguardMappingFileTask.dependsOn(it) }
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
      else -> throw GradleException(
          "This plugin can only be applied in Android application or library modules."
      )
    }
  }
}
