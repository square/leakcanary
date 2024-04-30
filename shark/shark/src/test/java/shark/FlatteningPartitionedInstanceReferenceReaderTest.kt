package shark

import java.io.File
import kotlin.reflect.KClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.HeapObject.HeapInstance
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.Reference.LazyDetails

class FlatteningPartitionedInstanceReferenceReaderTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  class Internals {
    val array = IntArray(10)
    val objectArray = arrayOf(Any())
    val classRef = FlatteningPartitionedInstanceReferenceReaderTest::class.java
  }

  class DataStructure {
    val internals = Internals()

    // Intentionally not the first defined field.
    val bridge = Any()
  }

  val dataStructure = DataStructure()

  @Test
  fun `FlatteningPartitionedInstanceReferenceReader reads cut set then internals`() {
    dumpHeap().openHeapGraph().use { graph ->
      val references = graph.readFlattenedRefs()

      val referenceNames = references.map { it.lazyDetailsResolver.resolve().name }
      assertThat(referenceNames).containsExactly(
        // cut set should be first, then everything reachable, flattened as direct chilren.
        "bridge",
        "internals",
        "array",
        // Reference to class is surfaced, but class itself is not explored
        "classRef",
        "objectArray",
        // First element in objectArray. Shows that object array is explored.
        "0"
      )

      val leafObjects = references.map { it.isLeafObject }
      assertThat(leafObjects.first()).isFalse()
      assertThat(leafObjects.drop(1)).allMatch { true }
    }
  }

  private fun HeapGraph.readFlattenedRefs(): List<Reference> {
    val referenceReader = FlatteningPartitionedInstanceReferenceReader(
      this, FieldInstanceReferenceReader(this, emptyList())
    )
    val virtualizingReader =
      SimpleTestVirtualInstanceReferenceReader(DataStructure::class, "bridge")
    val source = this.findClassByName(DataStructure::class.java.name)!!.instances.single()

    return referenceReader.read(virtualizingReader, source).toList()
  }

  private fun dumpHeap(): File {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    return hprofFile
  }

  private class SimpleTestVirtualInstanceReferenceReader(
    matchingClass: KClass<*>,
    private val referenceBridgeFieldName: String
  ) : VirtualInstanceReferenceReader {

    private val matchingClassName = matchingClass.java.name

    override fun matches(instance: HeapInstance) = instance.instanceClassName == matchingClassName

    override val readsCutSet = true

    override fun read(source: HeapInstance): Sequence<Reference> {
      val valueObjectId = source[matchingClassName, referenceBridgeFieldName]!!
        .value
        .asObjectId!!

      return sequenceOf(
        Reference(
          valueObjectId = valueObjectId,
          isLowPriority = false,
          lazyDetailsResolver = {
            LazyDetails(
              name = referenceBridgeFieldName,
              locationClassObjectId = source.objectId,
              locationType = ReferenceLocationType.INSTANCE_FIELD,
              matchedLibraryLeak = null,
              isVirtual = true
            )
          }

        ))
    }
  }
}
