package leakcanary.internal

import leakcanary.AndroidLeakTraceInspectors
import leakcanary.HeapAnalysisSuccess
import leakcanary.HprofGraph
import leakcanary.LeakTraceElementReporter
import leakcanary.LeakTraceInspector
import leakcanary.LeakingInstance
import leakcanary.forEachInstanceOf
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

    val labeler = object : LeakTraceInspector{
      override fun inspect(
        graph: HprofGraph,
        leakTrace: List<LeakTraceElementReporter>
      ) {
        leakTrace.forEachInstanceOf("java.lang.String")  { instance ->
          addLabel("Hello ${instance.readAsJavaString()}")
        }
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(leakTraceInspectors = listOf(labeler))

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements.last().labels).isEqualTo(listOf("Hello World"))
  }

  @Test fun threadNameLabel() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(leakTraceInspectors = listOf(AndroidLeakTraceInspectors.THREAD))

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.leakTrace.elements.first().labels).contains("Thread name: 'kroutine'")
  }

}