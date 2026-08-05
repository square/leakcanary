package shark.explorer

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
import shark.explorer.LeakKind.APPLICATION

/**
 * The leaks the explorer finds in a heap dump, against the leaks LeakCanary finds in the same one, compared
 * by the signature both print.
 *
 * Which is the strongest thing there is to say about the explorer's chains: a signature is a hash of the
 * stretch of the chain that explains the leak, so two tools agreeing on one agree about which references
 * hold the object, which of them the app is meant to have, and where the leak starts. Read the failure as
 * "the two found different paths" rather than as "the hash is wrong".
 *
 * The heap dumps here are the differences that used to make the two disagree, each on its own — see
 * `notes/decisions.md` for the sweep over the repo's real Android dumps and for the two differences that
 * remain, which are about which leaking objects get a chain of their own rather than about the chains.
 */
class LeakSignatureTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a leak in a collection is named by the collection`() {
    // Rather than by the array the collection keeps its elements in, which is a step neither an app's code
    // nor a LeakCanary report has.
    assertSameSignatures(testFolder.leakInAListHeapDump())
  }

  @Test fun `a leak on a stack is named by the field that also holds it`() {
    // The stack frame is the shorter way to it, and the field is the one worth reading.
    assertSameSignatures(testFolder.leakOnAStackAndInAFieldHeapDump())
  }

  /**
   * That the two find the same leaks in [heapDumpFile], by the signature each prints under one, and that
   * they find any at all: two empty lists are equal and say nothing.
   */
  private fun assertSameSignatures(heapDumpFile: File) {
    val analysis = HeapAnalyzer(OnAnalysisProgressListener.NO_OP).analyze(
      heapDumpFile = heapDumpFile,
      // Every leak of the heap dump rather than the ones watched since the last one, which is what the
      // explorer lists: a dump opened in it was not necessarily taken by LeakCanary.
      leakingObjectFinder = FilteringLeakingObjectFinder(
        AndroidObjectInspectors.appLeakingObjectFilters
      ),
      referenceMatchers = AndroidReferenceMatchers.appDefaults,
      computeRetainedHeapSize = false,
      objectInspectors = AndroidObjectInspectors.appDefaults,
      metadataExtractor = AndroidMetadataExtractor
    )
    check(analysis is HeapAnalysisSuccess) {
      "LeakCanary read no leak out of $heapDumpFile: ${(analysis as HeapAnalysisFailure).exception}"
    }

    HeapExplorer.open(heapDumpFile).use { explorer ->
      val explored = explorer.tree.findLeaks().sections.single { it.kind == APPLICATION }.groups
      assertThat(explored.map { it.signature })
        .isNotEmpty
        .containsExactlyInAnyOrderElementsOf(analysis.applicationLeaks.map { it.signature })
    }
  }
}
