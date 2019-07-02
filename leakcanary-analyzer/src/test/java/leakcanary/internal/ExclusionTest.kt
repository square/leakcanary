package leakcanary.internal

import leakcanary.Exclusion
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.Exclusion.ExclusionType.JavaLocalExclusion
import leakcanary.Exclusion.ExclusionType.StaticFieldExclusion
import leakcanary.Exclusion.Status.NEVER_REACHABLE
import leakcanary.Exclusion.Status.WEAKLY_REACHABLE
import leakcanary.Exclusion.Status.WONT_FIX_LEAK
import leakcanary.HeapAnalysisSuccess
import leakcanary.KeyedWeakReference
import leakcanary.LeakingInstance
import leakcanary.NoPathToInstance
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.ref.WeakReference

class ExclusionTest {

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
        exclusions = listOf(Exclusion(StaticFieldExclusion("GcRoot", "shortestPath")))
    )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.leakTrace.elements).hasSize(3)
    assertThat(leak.leakTrace.elements[0].className).isEqualTo("GcRoot")
    assertThat(leak.leakTrace.elements[0].reference!!.name).isEqualTo("longestPath")
    assertThat(leak.leakTrace.elements[1].className).isEqualTo("HasLeaking")
    assertThat(leak.leakTrace.elements[1].reference!!.name).isEqualTo("leaking")
    assertThat(leak.leakTrace.elements[2].className).isEqualTo("Leaking")
  }

  @Test fun allPathsExcluded_ShortestWins() {
    hprofFile.writeTwoPathsToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        exclusions = listOf(
            Exclusion(StaticFieldExclusion("GcRoot", "shortestPath")),
            Exclusion(InstanceFieldExclusion("HasLeaking", "leaking"))
        )
    )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.exclusionStatus).isEqualTo(WONT_FIX_LEAK)
    assertThat(leak.leakTrace.elements).hasSize(2)
    assertThat(leak.leakTrace.elements[0].className).isEqualTo("GcRoot")
    assertThat(leak.leakTrace.elements[0].reference!!.name).isEqualTo("shortestPath")
    assertThat(leak.leakTrace.elements[1].className).isEqualTo("Leaking")
  }

  @Test fun noPathToInstanceNeverReachable() {
    hprofFile.writeTwoPathsToInstance()

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        exclusions = listOf(
            Exclusion(StaticFieldExclusion("GcRoot", "shortestPath"), status = NEVER_REACHABLE),
            Exclusion(InstanceFieldExclusion("HasLeaking", "leaking"), status = NEVER_REACHABLE)
        )
    )
    assertThat(analysis.retainedInstances[0]).isInstanceOf(NoPathToInstance::class.java)
  }

  @Test fun excludedThread() {
    hprofFile.writeJavaLocalLeak(threadClass = "MyThread", threadName = "kroutine")

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        exclusions = listOf(Exclusion(JavaLocalExclusion("kroutine"), status = WONT_FIX_LEAK))
    )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.exclusionStatus).isEqualTo(WONT_FIX_LEAK)
  }

  @Test fun weaklyReachableExclusion() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["ref"] =
          keyedWeakReference(className = "Leaking", referentInstanceId = "Leaking" instance {})
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        exclusions = listOf(
            Exclusion(
                type = InstanceFieldExclusion(WeakReference::class.java.name, "referent"),
                status = WEAKLY_REACHABLE
            )
        )
    )

    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.exclusionStatus).isEqualTo(WEAKLY_REACHABLE)
  }

  @Test fun overrideSuperclassExclusion() {
    hprofFile.dump {
      "GcRoot" clazz {
        staticField["ref"] =
          keyedWeakReference(className = "Leaking", referentInstanceId = "Leaking" instance {})
      }
    }

    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
        exclusions = listOf(
            Exclusion(
                type = InstanceFieldExclusion(WeakReference::class.java.name, "referent"),
                status = WEAKLY_REACHABLE
            ), Exclusion(
            type = InstanceFieldExclusion(KeyedWeakReference::class.java.name, "referent"),
            status = NEVER_REACHABLE
        )
        )
    )
    assertThat(analysis.retainedInstances[0]).isInstanceOf(NoPathToInstance::class.java)
  }

}