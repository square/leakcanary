package leakcanary.internal

import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.HeapAnalysisSuccess
import leakcanary.InstanceDefaultLabeler
import leakcanary.Labeler
import leakcanary.LeakingInstance
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LabelerTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun stringContentAsLabel() {
    hprofFile.writeSinglePathToString("World")

    val labeler: Labeler = { record
      ->
      if (record is GraphInstanceRecord && record.className == "java.lang.String") {
        listOf("Hello ${record.readAsJavaString()}")
      } else emptyList()

    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(labelers = listOf(labeler))

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements.last().labels).isEqualTo(listOf("Hello World"))
  }

  @Test fun threadNameLabel() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(labelers = listOf(InstanceDefaultLabeler))

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements.first().labels).contains("Thread name: 'kroutine'")
  }

}