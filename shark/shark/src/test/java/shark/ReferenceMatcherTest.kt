package shark

import java.io.File
import java.lang.ref.WeakReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ReferencePattern.Companion.instanceField
import shark.ReferencePattern.Companion.javaLocal
import shark.ReferencePattern.Companion.nativeGlobalVariable
import shark.ReferencePattern.Companion.staticField
import shark.ValueHolder.ReferenceHolder

class ReferenceMatcherTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test fun shortestPathExcluded() {
    hprofFile.writeTwoPathsToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = listOf(
        staticField(
          className = "GcRoot",
          fieldName = "shortestPath"
        ).leak()
      )
    )

    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath).hasSize(2)
    assertThat(leakTrace.referencePath[0].originObject.className).isEqualTo("GcRoot")
    assertThat(leakTrace.referencePath[0].referenceName).isEqualTo("longestPath")
    assertThat(leakTrace.referencePath[1].originObject.className).isEqualTo("HasLeaking")
    assertThat(leakTrace.referencePath[1].referenceName).isEqualTo("leaking")
    assertThat(leakTrace.leakingObject.className).isEqualTo("Leaking")
  }

  @Test fun allPathsExcluded_ShortestWins() {
    hprofFile.writeTwoPathsToInstance()

    val expectedMatcher = staticField(
      className = "GcRoot",
      fieldName = "shortestPath"
    ).leak()
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = listOf(
        expectedMatcher,
        instanceField(
          className = "HasLeaking",
          fieldName = "leaking"
        ).leak()
      )
    )

    val leak = analysis.libraryLeaks[0]
    assertThat(leak.pattern).isEqualTo(expectedMatcher.pattern)
    val leakTrace = leak.leakTraces.first()
    assertThat(leakTrace.referencePath).hasSize(1)
    assertThat(leakTrace.referencePath[0].originObject.className).isEqualTo("GcRoot")
    assertThat(leakTrace.referencePath[0].referenceName).isEqualTo("shortestPath")
    assertThat(leakTrace.leakingObject.className).isEqualTo("Leaking")
  }

  @Test fun noPathToInstanceNeverReachable() {
    hprofFile.writeTwoPathsToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = listOf(
        staticField(
          className = "GcRoot",
          fieldName = "shortestPath"
        ).ignored(),
        instanceField(
          className = "HasLeaking",
          fieldName = "leaking"
        ).ignored()
      )
    )
    assertThat(analysis.libraryLeaks).isEmpty()
  }

  @Test fun excludedThread() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val matcher = javaLocal("kroutine").leak()
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = listOf(matcher)
    )

    val leak = analysis.libraryLeaks[0]
    assertThat(leak.pattern).isEqualTo(matcher.pattern)
  }

  @Test fun overrideSuperclassExclusion() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["ref"] =
          keyedWeakReference(referentInstanceId = "Leaking" instance {})
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = listOf(
        instanceField(
          className = WeakReference::class.java.name,
          fieldName = "referent"
        ).leak(),
        instanceField(
          className = "leakcanary.KeyedWeakReference",
          fieldName = "referent"
        ).ignored()
      )
    )
    assertThat(analysis.libraryLeaks).isEmpty()
  }

  @Test fun nativeGlobalVariableLibraryLeak() {
    hprofFile.dump {
      gcRoot(JniGlobal(id = "Leaking".watchedInstance {}.value, jniGlobalRefId = 42))
    }

    val matcher = nativeGlobalVariable("Leaking").leak()
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = listOf(matcher)
    )
    val leak = analysis.libraryLeaks[0]
    assertThat(leak.pattern).isEqualTo(matcher.pattern)
  }

  @Test fun nativeGlobalVariableShortestPathExcluded() {
    hprofFile.dump {
      val leaking = instance(clazz("Leaking"))
      keyedWeakReference(leaking)
      val hasLeaking = instance(
        clazz("HasLeaking", fields = listOf("leaking" to ReferenceHolder::class)),
        fields = listOf(leaking)
      )
      clazz("GcRoot", staticFields = listOf("longestPath" to hasLeaking))
      gcRoot(JniGlobal(id = leaking.value, jniGlobalRefId = 42))
    }

    val matcher = nativeGlobalVariable("Leaking").leak()
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = listOf(matcher)
    )
    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leakTrace.referencePath).hasSize(2)
    assertThat(leakTrace.referencePath[0].originObject.className).isEqualTo("GcRoot")
    assertThat(leakTrace.referencePath[0].referenceName).isEqualTo("longestPath")
  }
}
