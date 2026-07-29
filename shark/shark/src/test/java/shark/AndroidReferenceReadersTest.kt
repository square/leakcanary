package shark

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import shark.AndroidReferenceReaders.SAFE_ITERABLE_MAP
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.Companion.NULL_REFERENCE
import shark.ValueHolder.ReferenceHolder

class AndroidReferenceReadersTest {

  @Test
  fun `SafeIterableMap entry surfaced as key and value`() {
    val referenceNames = readSafeIterableMapEntry { string("key") to string("value") }

    assertThat(referenceNames).containsExactly("key()", "\"key\"")
  }

  @Test
  fun `SafeIterableMap entry with a null key fails`() {
    assertThatThrownBy {
      readSafeIterableMapEntry { nullReference() to string("value") }
    }.isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("SafeIterableMap\$Entry.mKey should never be a null reference")
  }

  @Test
  fun `SafeIterableMap entry with a null value fails`() {
    assertThatThrownBy {
      readSafeIterableMapEntry { string("key") to nullReference() }
    }.isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("SafeIterableMap\$Entry.mValue should never be a null reference")
  }

  /**
   * The names of the references surfaced by [SAFE_ITERABLE_MAP] for a map with a single entry
   * holding the key and value returned by [keyAndValue].
   */
  private fun readSafeIterableMapEntry(
    keyAndValue: HprofWriterHelper.() -> Pair<ValueHolder, ValueHolder>
  ): List<String> {
    return dump {
      val entryClassId = clazz(
        className = SAFE_ITERABLE_MAP_ENTRY_CLASS_NAME,
        fields = listOf(
          "mKey" to ReferenceHolder::class,
          "mValue" to ReferenceHolder::class,
          "mNext" to ReferenceHolder::class,
        )
      )
      val mapClassId = clazz(
        className = SAFE_ITERABLE_MAP_CLASS_NAME,
        fields = listOf("mStart" to ReferenceHolder::class)
      )
      val (key, value) = keyAndValue()
      val entry = instance(
        classId = entryClassId,
        fields = listOf(key, value, nullReference())
      )
      instance(
        classId = mapClassId,
        fields = listOf(entry)
      )
    }.openHeapGraph().use { graph ->
      val map = graph.findClassByName(SAFE_ITERABLE_MAP_CLASS_NAME)!!.instances.single()
      val reader = SAFE_ITERABLE_MAP.create(graph)!!

      reader.read(map)
        .map { it.lazyDetailsResolver.resolve().name }
        .toList()
    }
  }

  private fun nullReference() = ReferenceHolder(NULL_REFERENCE)

  companion object {
    private const val SAFE_ITERABLE_MAP_CLASS_NAME = "androidx.arch.core.internal.SafeIterableMap"
    private const val SAFE_ITERABLE_MAP_ENTRY_CLASS_NAME =
      "androidx.arch.core.internal.SafeIterableMap\$Entry"
  }
}
