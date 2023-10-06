package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File
import javax.print.attribute.standard.PrinterMoreInfoManufacturer
import shark.AndroidReferenceMatchers.Companion.HMD_GLOBAL
import shark.AndroidReferenceMatchers.Companion.HUAWEI
import shark.AndroidReferenceMatchers.Companion.INFINIX
import shark.AndroidReferenceMatchers.Companion.LENOVO
import shark.AndroidReferenceMatchers.Companion.LG
import shark.AndroidReferenceMatchers.Companion.MEIZU
import shark.AndroidReferenceMatchers.Companion.MOTOROLA
import shark.AndroidReferenceMatchers.Companion.NVIDIA
import shark.AndroidReferenceMatchers.Companion.ONE_PLUS
import shark.AndroidReferenceMatchers.Companion.RAZER
import shark.AndroidReferenceMatchers.Companion.SAMSUNG
import shark.AndroidReferenceMatchers.Companion.SHARP
import shark.AndroidReferenceMatchers.Companion.VIVO
import shark.AndroidReferenceMatchers.Companion.XIAOMI
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ReferencePattern.StaticFieldPattern
import shark.ValueHolder.IntHolder

class AndroidReferenceMatchersTest {

  @Test fun `Expect anything under SDK 30 unknown XIAMI__resources_impl`() {
    expect(HMD_GLOBAL, 29, false)
  }

  @Test fun `Expect NVIDIA unknown XIAMI__resources_impl`() {
    expect(NVIDIA, 30, false)
  }

  @Test fun `Expect XIAOMI known XIAMI__resources_impl`() {
    expect(XIAOMI, 30, true)
  }

  @Test fun `Expect LENOVO known XIAMI__resources_impl`() {
    expect(LENOVO, 30, true)
  }

  @Test fun `Expect INFINIX known XIAMI__resources_impl`() {
    expect(INFINIX, 30, true)
  }

  @Test fun `Expect HMD_GLOBAL known XIAMI__resources_impl`() {
    expect(HMD_GLOBAL, 30, true)
  }

  /**
   * Validate whether or not we see a known library leak for the given device manufacturer
   * and sdkInt combination, resulting from a call to
   * AndroidReferenceMatchers.XIAMI__RESOURCES_IMPL.add(matchers).
   */
  private fun expect(manufacturer: String, sdkInt: Int, expectKnown: Boolean) {

    val hprofBytes = dumpToBytes {
      clazz("android.os.Build",
        staticFields = listOf("MANUFACTURER" to string(manufacturer), "ID" to string("someId"))
      )
      clazz("android.os.Build\$VERSION",
        staticFields = listOf("SDK_INT" to IntHolder(sdkInt))
      )
      val leaking = instance(clazz("android.content.Context"))
      keyedWeakReference(leaking)
      clazz(
        "android.content.res.ResourcesImpl",
        staticFields = listOf(
          "mAppContext" to leaking
        )
      )
    }

    val matchers = mutableListOf<ReferenceMatcher>()
    AndroidReferenceMatchers.XIAMI__RESOURCES_IMPL.add(matchers)
    val actualMatch = matchers.first()
    val analysis = hprofBytes.checkForLeaks<HeapAnalysisSuccess>(referenceMatchers = matchers)

    if(expectKnown) {
      // Expect no application leaks found
      assertThat(analysis.applicationLeaks)
        .describedAs(analysis.applicationLeaks.toString()).isEmpty()

      // Expect we see a known library leak instead matching the leak pattern we expect.
      assertThat(analysis.libraryLeaks).hasSize(1)
      val leak = analysis.libraryLeaks.first()
      assertThat(leak.pattern).isEqualTo(actualMatch.pattern)
    } else {
      // Expect that we don't know about this leak
      assertThat(analysis.libraryLeaks).isEmpty()

      // And hence it shows up as an application leak.
      assertThat(analysis.applicationLeaks)
        .describedAs(analysis.applicationLeaks.toString()).hasSize(1)
      val leak = analysis.applicationLeaks.first()

      val (expectedClassName, expectedRefName) = leak.leakTraces.first().referencePath.first().let {
        it.owningClassName to it.referenceName
      }

      val (actualClassName, actualRefName) = (actualMatch.pattern as StaticFieldPattern).let {
        it.className to it.fieldName
      }
      assertThat(expectedClassName).isEqualTo(actualClassName)
      assertThat(expectedRefName).isEqualTo(actualRefName)
    }
  }

  fun <T : HeapAnalysis> ByteArray.checkForLeaks(
    objectInspectors: List<ObjectInspector> = emptyList(),
    computeRetainedHeapSize: Boolean = false,
    referenceMatchers: List<ReferenceMatcher> = emptyList(),
    metadataExtractor: MetadataExtractor = MetadataExtractor.NO_OP,
    proguardMapping: ProguardMapping? = null,
    leakingObjectFinder: LeakingObjectFinder = FilteringLeakingObjectFinder(
      ObjectInspectors.jdkLeakingObjectFilters
    ),
  ): T {

    val basp = ByteArraySourceProvider(this)

    val inspectors = if (ObjectInspectors.KEYED_WEAK_REFERENCE !in objectInspectors) {
      objectInspectors + ObjectInspectors.KEYED_WEAK_REFERENCE
    } else {
      objectInspectors
    }
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)

    val result = basp.openHeapGraph(proguardMapping).use { graph ->
      heapAnalyzer.analyze(
        heapDumpFile = File("ignored"),
        graph = graph,
        leakingObjectFinder = leakingObjectFinder,
        referenceMatchers = referenceMatchers,
        computeRetainedHeapSize = computeRetainedHeapSize,
        objectInspectors = inspectors,
        metadataExtractor = metadataExtractor,
      )
    }
    if (result is HeapAnalysisFailure) {
      println(result)
    }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }
}
