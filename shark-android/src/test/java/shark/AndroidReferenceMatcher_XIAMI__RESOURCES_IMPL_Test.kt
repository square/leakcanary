package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import shark.AndroidReferenceMatcher_XIAMI__RESOURCES_IMPL_Test.Companion.expectedKnownClassLeakingContext
import shark.AndroidReferenceMatcher_XIAMI__RESOURCES_IMPL_Test.Companion.expectedKnownReferenceName
import shark.AndroidReferenceMatchers.Companion.HMD_GLOBAL
import shark.AndroidReferenceMatchers.Companion.INFINIX
import shark.AndroidReferenceMatchers.Companion.LENOVO
import shark.AndroidReferenceMatchers.Companion.NVIDIA
import shark.AndroidReferenceMatchers.Companion.XIAOMI
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ReferencePattern.StaticFieldPattern
import shark.ValueHolder.IntHolder

/**
 * Tests for [AndroidReferenceMatchers.XIAMI__RESOURCES_IMPL] known leaks, to ensure we
 * don't report the ones we know about as application leaks, and do report the ones we don't.
 */
@RunWith(Parameterized::class)
class AndroidReferenceMatcher_XIAMI__RESOURCES_IMPL_Test(
  private val manufacturer: String,
  private val sdkInt: Int,
  private val verifyResults: HeapAnalysisSuccess.() -> Unit
) {

  companion object {

    /**
     * It's known that some device manufacturers on SDK 30 and above leak
     * a statically held [android.content.Context] from a class [android.content.res.ResourcesImpl]
     * in a variable named [mContext].
     */
    const val expectedKnownClassLeakingContext = "android.content.res.ResourcesImpl"
    const val expectedKnownReferenceName = "mAppContext"

    @JvmStatic
    @Parameterized.Parameters
    fun data() = listOf(
      // HMD_GLOBAL is in the list of known offenders, but we want to validate that
      // below SDK 30 unknown, above SDK 30 known
      arrayOf(HMD_GLOBAL, 29, HeapAnalysisSuccess::expectApplicationLeak),
      arrayOf(HMD_GLOBAL, 30, HeapAnalysisSuccess::expectKnownLibraryLeak),
      arrayOf(HMD_GLOBAL, 31, HeapAnalysisSuccess::expectKnownLibraryLeak),

      // NVIDIA is not in the list of known manufacturers,
      // so validate a leak is reported even if we're above SDK 30
      arrayOf(NVIDIA, 30, HeapAnalysisSuccess::expectApplicationLeak),

      // Ensure that each known offender is reported as known and not an application leak
      arrayOf(XIAOMI, 30, HeapAnalysisSuccess::expectKnownLibraryLeak),
      arrayOf(LENOVO, 30, HeapAnalysisSuccess::expectKnownLibraryLeak),
      arrayOf(INFINIX, 30, HeapAnalysisSuccess::expectKnownLibraryLeak),
      arrayOf(HMD_GLOBAL, 30, HeapAnalysisSuccess::expectKnownLibraryLeak),
    )
  }

  /**
   * Sets up the known leak for some manufacturers in a fake, in-memory heap dump.
   * Then analyzes for leaks with [AndroidReferenceMatchers.XIAMI__RESOURCES_IMPL] added.
   * The HeapAnalysis is returned for assertions against it.
   */
  @Test fun givenLeakingContextFor() {
    val hprofBytes = dumpToBytes {
      clazz(
        "android.os.Build",
        staticFields = listOf("MANUFACTURER" to string(manufacturer), "ID" to string("someId"))
      )
      clazz(
        "android.os.Build\$VERSION",
        staticFields = listOf("SDK_INT" to IntHolder(sdkInt))
      )
      val leaking = instance(clazz("android.content.Context"))
      keyedWeakReference(leaking)
      clazz(
        expectedKnownClassLeakingContext,
        staticFields = listOf(expectedKnownReferenceName to leaking)
      )
    }

    val matchers = mutableListOf<ReferenceMatcher>()
    AndroidReferenceMatchers.XIAMI__RESOURCES_IMPL.add(matchers)
    val analysis =
      hprofBytes.checkForLeaks<HeapAnalysisSuccess>(referenceMatchers = matchers)

    analysis.verifyResults()
  }
}

fun <T : HeapAnalysis> ByteArray.checkForLeaks(
  referenceMatchers: List<ReferenceMatcher>
): T {
  val basp = ByteArraySourceProvider(this)
  val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
  val result = basp.openHeapGraph(proguardMapping = null).use { graph ->
    heapAnalyzer.analyze(
      heapDumpFile = File("ignored"),
      graph = graph,
      leakingObjectFinder = FilteringLeakingObjectFinder(
        ObjectInspectors.jdkLeakingObjectFilters
      ),
      referenceMatchers = referenceMatchers,
      computeRetainedHeapSize = false,
      objectInspectors = listOf(ObjectInspectors.KEYED_WEAK_REFERENCE),
      metadataExtractor = MetadataExtractor.NO_OP,
    )
  }
  if (result is HeapAnalysisFailure) {
    println(result)
  }
  @Suppress("UNCHECKED_CAST")
  return result as T
}

/**
 * Validate that there are no known library leaks, and instead the application leak we expect.
 */
private fun HeapAnalysisSuccess.expectApplicationLeak() {
  // Expect that we don't know about this leak
  assertThat(libraryLeaks).isEmpty()

  // And hence it shows up as an application leak.
  assertThat(applicationLeaks)
    .describedAs(applicationLeaks.toString()).hasSize(1)

  val (actualLeakedClassName, actualLeakedRefName) =
    applicationLeaks.first()
      .leakTraces.first()
      .referencePath.first().let {
        it.owningClassName to it.referenceName
      }

  assertThat(actualLeakedClassName).isEqualTo(expectedKnownClassLeakingContext)
  assertThat(actualLeakedRefName).isEqualTo(expectedKnownReferenceName)
}

/**
 * Validate that this is a known library leak, that we do not report it as an application leak.
 */
private fun HeapAnalysisSuccess.expectKnownLibraryLeak() {
  // Expect no application leaks found
  assertThat(applicationLeaks)
    .describedAs(applicationLeaks.toString()).isEmpty()

  // Expect we see a known library leak instead matching the leak pattern we expect.
  assertThat(libraryLeaks).hasSize(1)
  val (actualKnownClassName, actualKnownPattern) =
    (libraryLeaks.first().pattern as StaticFieldPattern).let {
      it.className to it.fieldName
    }

  assertThat(actualKnownClassName).isEqualTo(expectedKnownClassLeakingContext)
  assertThat(actualKnownPattern).isEqualTo(expectedKnownReferenceName)
}
