package com.squareup.leakcanary.deobfuscation

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class LeakCanaryLeakDeobfuscationPluginTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var buildFile: File

  @Before
  fun setup() {
    buildFile = tempFolder.newFile("build.gradle")

    val localPropertiesFile = File("../local.properties")
    if (localPropertiesFile.exists()) {
      localPropertiesFile.copyTo(File(tempFolder.root, "local.properties"), overwrite = true)
    }

    File("src/test/test-project").copyRecursively(tempFolder.root)
  }

  @Test
  fun `leakcanary deobfuscation plugin runs and copies mapping file into the apk assets dir`() {
    buildFile.writeText(
      """
      plugins {
        id 'com.android.application'
        id 'com.squareup.leakcanary.deobfuscation'
      }
      
      allprojects {
        repositories {
          google()
          jcenter()
        }
      }
      
      android {
        compileSdkVersion 29

        defaultConfig {
          minSdkVersion 29
        }

        buildTypes {
          debug {
            minifyEnabled true
          }
        }
      }
      
      leakCanary {
        filterObfuscatedVariants { variant ->
          variant.name == "debug"
        }
      }
      """.trimIndent()
    )

    val result = GradleRunner.create()
      .withProjectDir(tempFolder.root)
      .withArguments("assembleDebug")
      .withPluginClasspath()
      .build()

    // task has been run
    assertThat(
      result.task(":leakCanaryCopyObfuscationMappingForDebug")?.outcome == SUCCESS
    ).isTrue()

    // apk has been built
    val apkFile = File(tempFolder.root, "build/outputs/apk/debug")
      .listFiles()
      ?.firstOrNull { it.extension == "apk" }
    assertThat(apkFile != null).isTrue()

    // apk contains obfuscation mapping file in assets dir
    val obfuscationMappingEntry = ZipFile(apkFile).use { zipFile ->
      zipFile.entries().toList().firstOrNull { entry ->
        entry.name.contains("assets/leakCanaryObfuscationMapping.txt")
      }
    }
    assertThat(obfuscationMappingEntry != null).isTrue()
  }

  @Test
  fun `should throw if android plugin is not applied before deobfuscation plugin`() {
    buildFile.writeText(
      """
        plugins {
          id 'com.squareup.leakcanary.deobfuscation'
        }
      """.trimIndent()
    )

    val result = GradleRunner.create()
      .withProjectDir(tempFolder.root)
      .withPluginClasspath()
      .buildAndFail()

    assertThat(
      result.output.contains(
    "LeakCanary deobfuscation plugin can be used only in Android application or library module."
      )
    ).isTrue()
  }

  @Test
  fun `should throw if there is no variant with enabled minification`() {
    buildFile.writeText(
      """
        plugins {
          id 'com.android.application'
          id 'com.squareup.leakcanary.deobfuscation'
        }
        
        allprojects {
          repositories {
            google()
            jcenter()
          }
        }
        
        android {
          compileSdkVersion 29
  
          defaultConfig {
            minSdkVersion 29
          }
  
          buildTypes {
            debug {}
          }
        }
        
        leakCanary {
          filterObfuscatedVariants { variant ->
            variant.name == "debug"
          }
        }
      """.trimIndent()
    )

    val result = GradleRunner.create()
      .withProjectDir(tempFolder.root)
      .withArguments("assembleDebug")
      .withPluginClasspath()
      .buildAndFail()

    assertThat(
      result.output.contains(
    "LeakCanary deobfuscation plugin couldn't find any variant with minification enabled."
      )
    ).isTrue()
  }
}
