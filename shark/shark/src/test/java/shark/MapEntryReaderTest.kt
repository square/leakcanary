package shark

import java.io.File
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph

/**
 * Reads maps of a heap dump of this very JVM, so that the maps read are the ones the JDK in use writes
 * rather than a fixture's idea of them.
 */
class MapEntryReaderTest {

  class Value(val name: String)

  companion object {
    @JvmStatic
    var root: Any? = null
  }

  @get:Rule
  val testFolder = TemporaryFolder()

  @After fun tearDown() {
    root = null
  }

  @Test fun `HashMap read as its entries`() {
    root = hashMapOf("a" to Value("A"), "b" to Value("B"))

    val entries = readRootEntries()

    assertThat(entries).containsOnly("a" to "A", "b" to "B")
  }

  @Test fun `LinkedHashMap read as its entries`() {
    root = LinkedHashMap<Any, Any>().apply { put("a", Value("A")) }

    val entries = readRootEntries()

    assertThat(entries).containsOnly("a" to "A")
  }

  @Test fun `ConcurrentHashMap read as its entries`() {
    root = ConcurrentHashMap<Any, Any>().apply { put("a", Value("A")) }

    val entries = readRootEntries()

    assertThat(entries).containsOnly("a" to "A")
  }

  @Test fun `entry read as the node the map holds it in`() {
    root = hashMapOf("a" to Value("A"))

    val nodeClassNames = readRoot { entries -> entries.map { it.instance.instanceClassName } }

    assertThat(nodeClassNames).containsExactly("java.util.HashMap\$Node")
  }

  @Test fun `entry with a null value read as an entry`() {
    root = hashMapOf("a" to null)

    val entries = readRootEntries()

    assertThat(entries).containsOnly("a" to null)
  }

  @Test fun `map with nothing in it read as no entries`() {
    root = HashMap<Any, Any>()

    val entries = readRootEntries()

    assertThat(entries).isEmpty()
  }

  @Test fun `WeakHashMap read as no map`() {
    root = WeakHashMap<Any, Any>().apply { put("a", Value("A")) }

    val entries = readRootEntries()

    assertThat(entries).isNull()
  }

  @Test fun `set read as no map`() {
    root = hashSetOf("a")

    val entries = readRootEntries()

    assertThat(entries).isNull()
  }

  @Test fun `object that is no map read as no map`() {
    root = Value("A")

    val entries = readRootEntries()

    assertThat(entries).isNull()
  }

  /** Every entry of [root] as its key and the name of the [Value] it maps, or null for what is no map. */
  private fun readRootEntries(): List<Pair<String?, String?>>? = readRoot { entries ->
    entries.map { entry ->
      val key = entry.key.asObject?.asInstance?.readAsJavaString()
      val value = entry.value.asObject?.asInstance?.get(Value::class, "name")?.valueAsInstance
      key to value?.readAsJavaString()
    }
  }

  private fun <T> readRoot(readEntries: (Sequence<HeapMapEntry>) -> Sequence<T>): List<T>? {
    return dumpHeap().openHeapGraph().use { graph ->
      val testClass = graph.findClassByName(MapEntryReaderTest::class.java.name)!!
      val root = testClass[::root.name]!!.valueAsInstance!!
      val entries = MapEntryReader.createFor(graph).readEntriesOf(root)
      entries?.let { readEntries(it).toList() }
    }
  }

  private fun dumpHeap(): File {
    val hprofFile = File(testFolder.newFolder(), "jvm_heap.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    return hprofFile
  }
}
