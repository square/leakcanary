package shark.internal

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapAnalysisSuccess
import shark.HeapGraph
import shark.JdkReferenceMatchers
import shark.LeakTraceReference.ReferenceType.ARRAY_ENTRY
import shark.LeakingObjectFinder
import shark.ReferencePattern
import shark.checkForLeaks
import shark.ignored

class AndroidReferenceReadersHprofTest {

  @Test fun `safe iterable map traversed as dictionary`() {
    val hprofFile = "safe_iterable_map.hprof".classpathFile()
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()
    val leakTrace = analysis.applicationLeaks.single().leakTraces.single()

    val mapReference =
      leakTrace.referencePath.single { it.owningClassSimpleName == "FastSafeIterableMap" }
    assertThat(mapReference.referenceName).isEqualTo("key()")
    assertThat(mapReference.referenceType).isEqualTo(ARRAY_ENTRY)
  }

  @Test fun `API 25 HashMap$HashMapEntry supported`() {
    val hprofFile = "hashmap_api_25.hprof".classpathFile()
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>()
    val leakTrace = analysis.applicationLeaks.single().leakTraces.single()

    val mapReference =
      leakTrace.referencePath.single { it.owningClassSimpleName == "HashMap" }
    assertThat(mapReference.referenceName).isEqualTo("\"leaking\"")
    assertThat(mapReference.referenceType).isEqualTo(ARRAY_ENTRY)
  }

  @Test fun `ArraySet traversed as set`() {
    // This hprof happens to have an ArraySet in it.
    val hprofFile = "safe_iterable_map.hprof".classpathFile()

    val analysis = hprofFile.checkForFakeArraySetLeak()

    val leakTrace = analysis.applicationLeaks.single().leakTraces.single()

    println(leakTrace)

    val mapReference =
      leakTrace.referencePath.single { it.owningClassSimpleName == "ArraySet" }
    assertThat(mapReference.referenceName).isEqualTo("element()")
    assertThat(mapReference.referenceType).isEqualTo(ARRAY_ENTRY)
  }
}

fun File.checkForFakeArraySetLeak(): HeapAnalysisSuccess {
  val instanceHeldByArraySet =
    "android.view.accessibility.AccessibilityNodeInfo\$AccessibilityAction"

  class ArraySetFakeLeakingObjectFinder : LeakingObjectFinder {
    override fun findLeakingObjectIds(graph: HeapGraph): Set<Long> {
      val arraySetInstances = graph.findClassByName("android.util.ArraySet")!!
        .instances
        .map { arraySetInstance ->
          arraySetInstance to arraySetInstance["android.util.ArraySet", "mArray"]!!
            .valueAsObjectArray!!
            .readElements()
            .filter {
              it.asObject?.asInstance?.instanceClass?.name == instanceHeldByArraySet
            }
            .toList()
        }
      val firstElementReferencedByArraySet = arraySetInstances.first { (_, elements) ->
        elements.isNotEmpty()
      }.second.first()

      return setOf(firstElementReferencedByArraySet.asObjectId!!)
    }
  }
  return checkForLeaks(
    referenceMatchers = JdkReferenceMatchers.defaults + ReferencePattern.staticField(
      className = instanceHeldByArraySet,
      fieldName = "ACTION_FOCUS"
    ).ignored(),
    leakingObjectFinder = ArraySetFakeLeakingObjectFinder()
  )
}

fun String.classpathFile(): File {
  val classLoader = Thread.currentThread()
    .contextClassLoader
  val url = classLoader.getResource(this)!!
  return File(url.path)
}


