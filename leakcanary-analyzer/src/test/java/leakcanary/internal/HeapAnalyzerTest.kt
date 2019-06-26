package leakcanary.internal

import leakcanary.GcRoot.ThreadObject
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalysisSuccess
import leakcanary.HeapValue.ObjectReference
import leakcanary.LeakTraceElement.Type.LOCAL
import leakcanary.LeakingInstance
import leakcanary.NoPathToInstance
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HeapAnalyzerTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun singlePathToInstance() {
    hprofFile.writeSinglePathToInstance()
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    assertThat(analysis.retainedInstances[0]).isInstanceOf(LeakingInstance::class.java)
  }

  @Test fun pathToString() {
    hprofFile.writeSinglePathToString()
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance

    assertThat(leak.instanceClassName).isEqualTo("java.lang.String")
  }

  @Test fun pathToCharArray() {
    hprofFile.writeSinglePathsToCharArrays(listOf("Hello"))
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()
    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.instanceClassName).isEqualTo("char[]")
  }

  // Two char arrays to ensure we keep going after finding the first one
  @Test fun pathToTwoCharArrays() {
    hprofFile.writeSinglePathsToCharArrays(listOf("Hello", "World"))
    val analysis = hprofFile.checkForLeaks<HeapAnalysis>()
    assertThat(analysis).isInstanceOf(HeapAnalysisSuccess::class.java)
  }

  @Test fun shortestPath() {
    hprofFile.writeTwoPathsToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements).hasSize(2)
    assertThat(leak.leakTrace.elements[0].className).isEqualTo("GcRoot")
    assertThat(leak.leakTrace.elements[0].reference!!.name).isEqualTo("shortestPath")
    assertThat(leak.leakTrace.elements[1].className).isEqualTo("Leaking")
  }

  @Test fun noPathToInstance() {
    hprofFile.writeNoPathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    assertThat(analysis.retainedInstances[0]).isInstanceOf(NoPathToInstance::class.java)
  }

  @Test fun weakRefCleared() {
    hprofFile.writeWeakReferenceCleared()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisFailure>()
    assertThat(analysis.exception.cause).isInstanceOf(IllegalStateException::class.java)
        .hasMessage("No retained instances found in heap dump")
  }

  @Test fun failsNoRetainedKeys() {
    hprofFile.writeMultipleActivityLeaks(0)

    val analysis = hprofFile.checkForLeaks<HeapAnalysis>()

    assertThat(analysis).isInstanceOf(HeapAnalysisFailure::class.java)
  }

  @Test fun findMultipleLeaks() {
    hprofFile.writeMultipleActivityLeaks(5)

    val leaks = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    assertThat(leaks.retainedInstances).hasSize(5)
        .hasOnlyElementsOfType(LeakingInstance::class.java)
  }

  @Test fun localVariableLeak() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements).hasSize(2)
    assertThat(leak.leakTrace.elements[0].className).isEqualTo("MyThread")
    assertThat(leak.leakTrace.elements[0].reference!!.type).isEqualTo(LOCAL)
    assertThat(leak.leakTrace.elements[1].className).isEqualTo("Leaking")
  }

  @Test fun threadFieldLeak() {
    hprofFile.dump {
      val threadClassId =
        clazz(className = "java.lang.Thread", fields = listOf("name" to ObjectReference::class))
      val myThreadClassId = clazz(
          className = "MyThread", superClassId = threadClassId,
          fields = listOf("leaking" to ObjectReference::class)
      )
      val threadInstance =
        instance(myThreadClassId, listOf("Leaking" watchedInstance {}, string("Thread Name")))
      gcRoot(
          ThreadObject(
              id = threadInstance.value, threadSerialNumber = 42, stackTraceSerialNumber = 0
          )
      )
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements).hasSize(2)
    assertThat(leak.leakTrace.elements[0].className).isEqualTo("MyThread")
    assertThat(leak.leakTrace.elements[0].reference!!.name).isEqualTo("leaking")
    assertThat(leak.leakTrace.elements[1].className).isEqualTo("Leaking")
  }

}