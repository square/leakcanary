package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.GcRoot.ThreadObject
import shark.LeakTraceReference.ReferenceType.STATIC_FIELD
import shark.LeakTraceReference.ReferenceType.LOCAL
import shark.ValueHolder.ReferenceHolder
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

    assertThat(analysis.applicationLeaks[0]).isInstanceOf(Leak::class.java)
  }

  @Test fun pathToString() {
    hprofFile.writeSinglePathToString()
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leak = analysis.applicationLeaks[0]

    assertThat(leak.leakTraces.first().leakingObject.className).isEqualTo("java.lang.String")
  }

  @Test fun pathToCharArray() {
    hprofFile.writeSinglePathsToCharArrays(listOf("Hello"))
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()
    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.leakingObject.className).isEqualTo("char[]")
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

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath).hasSize(1)
    assertThat(leakTrace.referencePath[0].originObject.className).isEqualTo("GcRoot")
    assertThat(leakTrace.referencePath[0].referenceName).isEqualTo("shortestPath")
    assertThat(leakTrace.leakingObject.className).isEqualTo("Leaking")
  }

  @Test fun noPathToInstance() {
    hprofFile.writeNoPathToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    assertThat(analysis.applicationLeaks).isEmpty()
  }

  @Test fun weakRefCleared() {
    hprofFile.writeWeakReferenceCleared()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()
    assertThat(analysis.applicationLeaks).isEmpty()
  }

  @Test fun failsNoRetainedKeys() {
    hprofFile.writeMultipleActivityLeaks(0)

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    assertThat(analysis.applicationLeaks).isEmpty()
  }

  @Test fun findMultipleIdenticalLeaks() {
    hprofFile.writeMultipleActivityLeaks(5)

    val leaks = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    assertThat(leaks.applicationLeaks).hasSize(1)
    assertThat(leaks.applicationLeaks.first().leakTraces).hasSize(5)
  }

  @Test fun localVariableLeak() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath).hasSize(1)
    assertThat(leakTrace.referencePath[0].originObject.className).isEqualTo("MyThread")
    assertThat(leakTrace.referencePath[0].referenceType).isEqualTo(LOCAL)
    assertThat(leakTrace.leakingObject.className).isEqualTo("Leaking")
  }

  @Test fun localVariableLeakShortestPathGoesLast() {
    hprofFile.writeTwoPathJavaLocalShorterLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()
    println(analysis)

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath).hasSize(2)
    assertThat(leakTrace.referencePath[0].originObject.className).isEqualTo("GcRoot")
    assertThat(leakTrace.referencePath[0].referenceType).isEqualTo(STATIC_FIELD)
  }

  @Test fun threadFieldLeak() {
    hprofFile.dump {
      val threadClassId =
        clazz(className = "java.lang.Thread", fields = listOf("name" to ReferenceHolder::class))
      val myThreadClassId = clazz(
          className = "MyThread", superclassId = threadClassId,
          fields = listOf("leaking" to ReferenceHolder::class)
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

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath).hasSize(1)
    assertThat(leakTrace.referencePath[0].originObject.className).isEqualTo("MyThread")
    assertThat(leakTrace.referencePath[0].referenceName).isEqualTo("leaking")
    assertThat(leakTrace.leakingObject.className).isEqualTo("Leaking")
  }

  @Test fun nativeGlobalVariableApplicationLeak() {
    hprofFile.dump {
      gcRoot(JniGlobal(id = "Leaking".watchedInstance {}.value, jniGlobalRefId = 42))
    }

    val leaks = hprofFile.checkForLeaks<HeapAnalysisSuccess>()
    assertThat(leaks.applicationLeaks).hasSize(1)
  }
}