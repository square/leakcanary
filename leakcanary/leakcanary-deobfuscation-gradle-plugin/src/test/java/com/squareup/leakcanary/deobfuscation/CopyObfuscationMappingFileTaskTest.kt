package com.squareup.leakcanary.deobfuscation

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
  fun `existing mapping copied and merge assets dir generated if not exists`() {
    task.mappingFile.set(tempFolder.newFile("mapping.txt"))
    task.outputDir.set(File(tempFolder.root, "mergeAssetsDir"))

    assertThat(task.outputDir.get().asFile.exists()).isFalse()

    task.copyObfuscationMappingFile()

    assertThat(task.outputDir.get().asFile.exists()).isTrue()
    assertThat(task.mappingFile.get().asFile.exists()).isTrue()
  }

  @Test
  fun `previous mapping overwritten`() {
    task.outputDir.set(tempFolder.newFolder("mergeAssetsDir"))

    // create first mapping file
    task.mappingFile.set(tempFolder.newFile("firstMappingFile.txt")
      .apply {
        writeText("firstMappingFile")
      })
    task.copyObfuscationMappingFile()

    // create second mapping file
    task.mappingFile.set(tempFolder.newFile("secondMappingFile.txt")
      .apply {
        writeText("secondMappingFile")
      })
    task.copyObfuscationMappingFile()

    assertThat(task.mappingFile.get().asFile.exists()).isTrue()
    assertThat(task.mappingFile.get().asFile.readText()).isEqualTo("secondMappingFile")
  }
}
