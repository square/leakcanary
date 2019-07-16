package leakcanary.internal

import leakcanary.HeapAnalysisSuccess
import leakcanary.KeyedWeakReference
import leakcanary.ReferenceMatcher.IgnoredReferenceMatcher
import leakcanary.ReferenceMatcher.LibraryLeakReferenceMatcher
import leakcanary.ReferencePattern.InstanceFieldPattern
import leakcanary.ReferencePattern.JavaLocalPattern
import leakcanary.ReferencePattern.StaticFieldPattern
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.ref.WeakReference

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
            LibraryLeakReferenceMatcher(StaticFieldPattern("GcRoot", "shortestPath"))
        )
    )

    val leak = analysis.applicationLeaks[0]
    assertThat(leak.leakTrace.elements).hasSize(3)
    assertThat(leak.leakTrace.elements[0].className).isEqualTo("GcRoot")
    assertThat(leak.leakTrace.elements[0].reference!!.name).isEqualTo("longestPath")
    assertThat(leak.leakTrace.elements[1].className).isEqualTo("HasLeaking")
    assertThat(leak.leakTrace.elements[1].reference!!.name).isEqualTo("leaking")
    assertThat(leak.leakTrace.elements[2].className).isEqualTo("Leaking")
  }

  @Test fun allPathsExcluded_ShortestWins() {
    hprofFile.writeTwoPathsToInstance()

    val expectedMatcher = LibraryLeakReferenceMatcher(StaticFieldPattern("GcRoot", "shortestPath"))
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        referenceMatchers = listOf(
            expectedMatcher,
            LibraryLeakReferenceMatcher(InstanceFieldPattern("HasLeaking", "leaking"))
        )
    )

    val leak = analysis.libraryLeaks[0]
    assertThat(leak.pattern).isEqualTo(expectedMatcher.pattern)
    assertThat(leak.leakTrace.elements).hasSize(2)
    assertThat(leak.leakTrace.elements[0].className).isEqualTo("GcRoot")
    assertThat(leak.leakTrace.elements[0].reference!!.name).isEqualTo("shortestPath")
    assertThat(leak.leakTrace.elements[1].className).isEqualTo("Leaking")
  }

  @Test fun noPathToInstanceNeverReachable() {
    hprofFile.writeTwoPathsToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        referenceMatchers = listOf(
            IgnoredReferenceMatcher(StaticFieldPattern("GcRoot", "shortestPath")),
            IgnoredReferenceMatcher(InstanceFieldPattern("HasLeaking", "leaking"))
        )
    )
    assertThat(analysis.libraryLeaks).isEmpty()
  }

  @Test fun excludedThread() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val matcher = LibraryLeakReferenceMatcher(JavaLocalPattern("kroutine"))
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
            LibraryLeakReferenceMatcher(
                pattern = InstanceFieldPattern(WeakReference::class.java.name, "referent")
            ),
            IgnoredReferenceMatcher(
                pattern = InstanceFieldPattern(KeyedWeakReference::class.java.name, "referent")
            )
        )
    )
    assertThat(analysis.libraryLeaks).isEmpty()
  }

}