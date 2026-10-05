package shark.dive

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.AndroidMetadataExtractor
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.FilteringLeakingObjectFinder
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.OnAnalysisProgressListener
import shark.dive.LeakKind.APPLICATION

/**
 * The leaks Shark Dive finds in a heap dump, against the leaks LeakCanary finds in the same one, compared
 * by the leak fingerprint both print.
 *
 * Which is the strongest thing there is to say about Shark Dive's paths: a leak fingerprint is a hash
 * of the stretch of the path that explains the leak, so two tools agreeing on one agree about which references
 * hold the object, which of them the app is meant to have, and where the leak starts. Read the failure as
 * "the two found different paths" rather than as "the hash is wrong".
 *
 * The heap dumps here are the differences that used to make the two disagree, each on its own — see
 * `notes/decisions.md` for the sweep over the repo's real Android dumps and for the two differences that
 * remain, which are about which leaking objects get a path of their own rather than about the paths.
 */
class LeakFingerprintTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a leak in a collection is named by the collection`() {
    // Rather than by the array the collection keeps its elements in, which is a step neither an app's code
    // nor a LeakCanary report has.
    assertSameLeaks(testFolder.leakInAListHeapDump())
  }

  @Test fun `a leak on a stack is named by the field that also holds it`() {
    // The stack frame is the shorter way to it, and the field is the one worth reading.
    assertSameLeaks(testFolder.leakOnAStackAndInAFieldHeapDump())
  }

  /**
   * That the two find the same leaks in [heapDumpFile], by the leak fingerprint each prints under one and by
   * the leak trace each prints over it, and that they find any at all: two empty lists are equal and say
   * nothing.
   */
  private fun assertSameLeaks(heapDumpFile: File) {
    val analysis = HeapAnalyzer(OnAnalysisProgressListener.NO_OP).analyze(
      heapDumpFile = heapDumpFile,
      // Every leak of the heap dump rather than the ones watched since the last one, which is what the
      // dive lists: a dump opened in it was not necessarily taken by LeakCanary.
      leakingObjectFinder = FilteringLeakingObjectFinder(
        AndroidObjectInspectors.appLeakingObjectFilters
      ),
      referenceMatchers = AndroidReferenceMatchers.appDefaults,
      // On, so that the trace compared below is the one a LeakCanary report carries, with its
      // `Retaining … in … objects` line. Which number that line says is the one thing the two can differ
      // on — see [assertSameLeakTraces].
      computeRetainedHeapSize = true,
      objectInspectors = AndroidObjectInspectors.appDefaults,
      metadataExtractor = AndroidMetadataExtractor
    )
    check(analysis is HeapAnalysisSuccess) {
      "LeakCanary read no leak out of $heapDumpFile: ${(analysis as HeapAnalysisFailure).exception}"
    }

    HeapDive.open(heapDumpFile).use { dive ->
      val explored = dive.tree.findLeaks().sections.single { it.kind == APPLICATION }.groups
      assertThat(explored.map { it.leakFingerprint })
        .isNotEmpty
        .containsExactlyInAnyOrderElementsOf(analysis.applicationLeaks.map { it.leakFingerprint })

      val leakTracesByFingerprint = analysis.applicationLeaks.associate { leak ->
        leak.leakFingerprint to leak.leakTraces.single().toString()
      }
      explored.forEach { group ->
        // Any object of the group walks to the same suspect references, and `leakTraces.single()` above is
        // what says there is one of them here anyway. See [LeakGroup.objects].
        val path = dive.tree.rootPathTo(group.objects.first().objectId)
        assertSameLeakTraces(path.leakTrace()!!.toString(), leakTracesByFingerprint[group.leakFingerprint]!!)
      }
    }
  }

  /**
   * That Shark Dive renders a leak trace the way LeakCanary does, which is the thing that makes one worth
   * handing over: a reader comparing it against a report they already have is comparing the characters.
   *
   * **The retained sizes are blanked before comparing, and only those.** Both tools credit a size to the
   * leaking object alone, and the line saying so has to be in both — but the numbers come from two different
   * dominator trees, `shark.ApproximateDominatorTree` under the analysis and the exact
   * `shark.HeapDominatorTree` under the dive, so they agree to within the approximation and not to the byte.
   * Measured on `shark/shark-android/src/test/resources/leak_asynctask_o.hprof`: 211.0 kB in 986 objects
   * against 210978 bytes in 984. Blanking the two numbers is what leaves everything else — every step, every
   * underline, every label, every verdict and its reason — asserted character for character.
   */
  private fun assertSameLeakTraces(
    dived: String,
    analyzed: String
  ) {
    val retained = Regex("Retaining .+ in \\d+ objects")
    assertThat(dived.replace(retained, RETAINED_SIZE))
      .isEqualTo(analyzed.replace(retained, RETAINED_SIZE))
  }

  private companion object {
    const val RETAINED_SIZE = "Retaining <a size the two work out their own way>"
  }
}
