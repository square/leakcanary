package shark

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

    val labeler = ObjectInspector { reporter ->
      reporter.whenInstanceOf("java.lang.String") { instance ->
        labels += "Hello ${instance.readAsJavaString()}"
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(objectInspectors = listOf(labeler))

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()

    assertThat(leakTrace.leakingObject.labels).contains("Hello World")
  }

  @Test fun labelOnUnreachableObject() {
    val heapDump = dump {
      "com.example.SomeClass" watchedInstance {
      }
    }

    val labeler = ObjectInspector { reporter ->
      reporter.whenInstanceOf("java.lang.Object") { instance ->
        labels += instance.instanceClassSimpleName
      }
    }

    val analysis = heapDump.checkForLeaks<HeapAnalysisSuccess>(objectInspectors = listOf(labeler))

    assertThat(analysis.unreachableObjects[0].labels).contains("SomeClass")
  }

  @Test fun threadNameLabel() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis =
      hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        objectInspectors = listOf(ObjectInspectors.THREAD)
      )

    val leak = analysis.applicationLeaks[0]

    assertThat(leak.leakTraces.first().referencePath.first().originObject.labels).contains(
      "Thread name: 'kroutine'"
    )
  }
}