package com.squareup.leakcanary.leak.deobfuscation.plugin

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CopyObfuscationMappingFileTaskTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val task =
    ProjectBuilder
        .builder()
        .build()
        .tasks
        .create(
            "testCopyObfuscationMappingFileTask",
            CopyObfuscationMappingFileTask::class.java
        )

  @Test
  fun `non existing mapping not copied if merge assets dir is not specified`() {
    task.copyObfuscationMappingFile()

    assertThat(task.leakCanaryAssetsOutputFile.exists()).isFalse()
  }

  @Test
  fun `non existing mapping not copied if merge assets dir is specified`() {
    task.mergeAssetsDirectory = tempFolder.newFolder("mergeAssetsDir")

    task.copyObfuscationMappingFile()

    assertThat(task.leakCanaryAssetsOutputFile.exists()).isFalse()
  }

  @Test
  fun `existing mapping not copied if merge assets dir is not specified`() {
    task.mappingFile = tempFolder.newFile("mapping.txt")

    task.copyObfuscationMappingFile()

    assertThat(task.leakCanaryAssetsOutputFile.exists()).isFalse()
  }

  @Test
  fun `existing mapping copied and merge assets dir generated if not exists`() {
    task.mappingFile = tempFolder.newFile("mapping.txt")
    task.mergeAssetsDirectory = File(tempFolder.root, "mergeAssetsDir")

    assertThat(task.mergeAssetsDirectory!!.exists()).isFalse()

    task.copyObfuscationMappingFile()

    assertThat(task.mergeAssetsDirectory!!.exists()).isTrue()
    assertThat(task.leakCanaryAssetsOutputFile.exists()).isTrue()
  }

  @Test
  fun `existing mapping copied if merge assets dir is specified`() {
    task.mappingFile = tempFolder.newFile("mapping.txt")
    task.mergeAssetsDirectory = tempFolder.newFolder("mergeAssetsDir")

    task.copyObfuscationMappingFile()

    assertThat(task.leakCanaryAssetsOutputFile.exists()).isTrue()
  }

  @Test
  fun `previous mapping overwritten`() {
    task.mergeAssetsDirectory = tempFolder.newFolder("mergeAssetsDir")

    // create first mapping file
    task.mappingFile = tempFolder.newFile("firstMappingFile.txt")
        .apply {
          writeText("firstMappingFile")
        }
    task.copyObfuscationMappingFile()

    // create second mapping file
    task.mappingFile = tempFolder.newFile("secondMappingFile.txt")
        .apply {
          writeText("secondMappingFile")
        }
    task.copyObfuscationMappingFile()

    assertThat(task.leakCanaryAssetsOutputFile.exists()).isTrue()
    assertThat(task.leakCanaryAssetsOutputFile.readText()).isEqualTo("secondMappingFile")
  }
}