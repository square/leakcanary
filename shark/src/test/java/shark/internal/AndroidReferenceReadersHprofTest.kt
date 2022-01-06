package shark.internal

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapAnalysisSuccess
import shark.LeakTraceReference.ReferenceType.ARRAY_ENTRY
import shark.checkForLeaks

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
}

fun String.classpathFile(): File {
  val classLoader = Thread.currentThread()
    .contextClassLoader
  val url = classLoader.getResource(this)!!
  return File(url.path)
}


