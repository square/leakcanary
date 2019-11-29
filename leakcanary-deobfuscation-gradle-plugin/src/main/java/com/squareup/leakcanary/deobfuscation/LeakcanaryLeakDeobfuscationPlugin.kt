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
import org.gradle.api.logging.LogLevel.DEBUG
import org.gradle.api.tasks.TaskProvider

class LeakcanaryLeakDeobfuscationPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    val variants = findAndroidVariants(project)
    val leakCanaryExtension = createLeakCanaryExtension(project)
    variants.all { variant ->
      if (leakCanaryExtension.filterObfuscatedVariants(variant)) {
        setupTasks(project, variant)
      }
    }
  }

  private fun findAndroidVariants(project: Project): DomainObjectSet<BaseVariant> {
    return when (val extension = project.extensions.getByType(BaseExtension::class.java)) {
      is AppExtension -> extension.applicationVariants as DomainObjectSet<BaseVariant>
      is LibraryExtension -> extension.libraryVariants as DomainObjectSet<BaseVariant>
      else -> throwNoAndroidPluginException()
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
      it.mappingFile = variant.mappingFile
      it.mergeAssetsDirectory = variant.mergeAssetsProvider.get().outputDir.get().asFile

      val mappingGeneratingTaskProvider =
        findTaskProviderOrNull(
            project,
            "transformClassesAndResourcesWithR8For${variant.name.capitalize()}"
        ) ?: findTaskProviderOrNull(
            project,
            "transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}"
        ) ?: throwMissingMinifiedVariantException(project)

      it.dependsOn(mappingGeneratingTaskProvider)
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

  private fun throwMissingMinifiedVariantException(project: Project): Nothing {
    project.logger.log(
        DEBUG,
        """
          None of the project's variants seem to have minification enabled. 
          Please make sure that there is at least 1 minified variant in your project.    
        """.trimIndent()
    )
    throwNoAndroidPluginException()
  }
}
