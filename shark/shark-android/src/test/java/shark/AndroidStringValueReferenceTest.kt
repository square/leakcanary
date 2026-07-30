package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph

/**
 * Indexing picks up the id of the array holding the characters of every string as it reads a heap
 * dump, which relies on it working out where java.lang.String keeps that array before reaching the
 * instances. Android heap dumps don't cooperate: most string records come before the class dump of
 * java.lang.String, so the first of the two indexing passes is what finds the layout. These check
 * that against real Android heap dumps, where being off by a field would go unnoticed with a
 * synthetic one.
 */
class AndroidStringValueReferenceTest {

  @Test fun `strings point at the array their record holds on Android O`() {
    assertStringsPointAtTheArrayTheirRecordHolds("leak_asynctask_o.hprof")
  }

  @Test fun `strings point at the array their record holds on Android M`() {
    assertStringsPointAtTheArrayTheirRecordHolds("leak_asynctask_m.hprof")
  }

  @Test fun `strings point at the array their record holds pre Android M`() {
    assertStringsPointAtTheArrayTheirRecordHolds("leak_asynctask_pre_m.hprof")
  }

  private fun assertStringsPointAtTheArrayTheirRecordHolds(hprofFileName: String) {
    hprofFileName.classpathFile().openHeapGraph().use { graph ->
      // No reference matcher, so that reading a string goes through the index rather than falling
      // back to reading its record.
      val referenceReader = FieldInstanceReferenceReader(graph, emptyList())
      val strings = graph.findClassByName("java.lang.String")!!.instances.toList()
      var withValueArray = 0

      strings.forEach { string ->
        val fromRecord = string["java.lang.String", "value"]?.value?.asNonNullObjectId
        val references = referenceReader.read(string).toList()
        assertThat(references.map { it.valueObjectId })
          .describedAs("references of string ${string.objectId}")
          .isEqualTo(listOfNotNull(fromRecord))
        references.forEach { reference ->
          assertThat(reference.lazyDetailsResolver.resolve().name).isEqualTo("value")
        }
        if (fromRecord != null) {
          withValueArray++
        }
      }

      // Fail loudly rather than pass on an empty check if strings stop holding an array.
      assertThat(withValueArray).isGreaterThan(strings.size / 2)
    }
  }
}
