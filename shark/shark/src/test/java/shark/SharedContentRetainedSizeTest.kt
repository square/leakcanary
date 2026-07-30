package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.Companion.NULL_REFERENCE
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder

/**
 * The traversal follows the reference from a string to the array holding its characters and from a
 * wrapper array to the boxed primitives it holds, so content shared by several objects is
 * attributed to a single dominator instead of being counted once per object holding it.
 *
 * Sharing is more common than it looks: `new String(String)` copies the reference to the array of
 * characters rather than the array, `Integer.valueOf()` caches -128 to 127, `Boolean.valueOf()`
 * returns one of two instances, and before Android Marshmallow `String.substring()` shared its
 * parent's array.
 *
 * See https://github.com/square/leakcanary/issues/2700, where sharing inflated the total retained
 * size past [Int.MAX_VALUE].
 */
class SharedContentRetainedSizeTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `strings sharing a value array are counted once`() {
    val file = testFolder.newFile("shared-string-value.hprof")
    file.dump {
      val stringClassId = clazz(
        className = "java.lang.String",
        fields = listOf(
          "value" to ReferenceHolder::class,
          "hash" to IntHolder::class
        )
      )
      val sharedValue = "a".repeat(SHARED_CHAR_COUNT).charArrayDump
      val strings = (0 until SHARING_COUNT).map {
        instance(stringClassId, listOf(sharedValue, IntHolder(0)))
      }
      gcRootedObjectArray(strings)
    }

    file.openHeapGraph().use { graph ->
      val root = ObjectDominators().buildDominatorTree(graph, emptyList())
        .getValue(NULL_REFERENCE)

      // The whole heap is reachable here, so the root dominates all of it, exactly once.
      assertThat(root.retainedSize).isEqualTo(graph.ownSizeOfEveryObject())
      assertThat(root.retainedCount).isEqualTo(graph.objectCount)
      // Would be SHARING_COUNT times that with the array credited to every string holding it.
      assertThat(root.retainedSize).isLessThan(2L * SHARED_CHAR_COUNT * BYTES_PER_CHAR)
    }
  }

  @Test fun `a boxed primitive shared by wrapper arrays is counted once`() {
    val file = testFolder.newFile("shared-boxed-primitive.hprof")
    file.dump {
      val integerClassId = clazz(
        className = "java.lang.Integer",
        fields = listOf("value" to IntHolder::class)
      )
      // Integer.valueOf() caches -128 to 127, so slots point at shared instances.
      val cached = instance(integerClassId, listOf(IntHolder(42)))
      val integerArrayClassId = arrayClass("java.lang.Integer")
      val arrays = (0 until SHARING_COUNT).map {
        ReferenceHolder(
          objectArray(integerArrayClassId, LongArray(SHARING_COUNT) { cached.value })
        )
      }
      gcRootedObjectArray(arrays)
    }

    file.openHeapGraph().use { graph ->
      val tree = ObjectDominators().buildDominatorTree(graph, emptyList())
      val root = tree.getValue(NULL_REFERENCE)
      val cached = graph.instances.single { it.instanceClassName == "java.lang.Integer" }

      assertThat(root.retainedSize).isEqualTo(graph.ownSizeOfEveryObject())
      assertThat(root.retainedCount).isEqualTo(graph.objectCount)
      assertThat(tree.getValue(cached.objectId).shallowSize).isEqualTo(cached.byteSize)
      // The one instance hangs off what holds all the arrays, not off any single one of them.
      val dominatorId = tree.entries.single { cached.objectId in it.value.dominatedObjectIds }.key
      assertThat(graph.findObjectById(dominatorId).asObjectArray!!.arrayClassName)
        .isEqualTo("java.lang.Object[]")
    }
  }

  @Test fun `a real jvm heap dump does not retain more than it holds`() {
    val bigString = "a".repeat(SHARED_CHAR_COUNT)
    // new String(String) copies the reference to the value array rather than the array itself, so
    // these all share one 2 MB array on a JVM, as substrings did before Android Marshmallow.
    val newStringOfString = String::class.java.getConstructor(String::class.java)
    val sharing = (0 until SHARING_COUNT).map { newStringOfString.newInstance(bigString) }

    val file = File(testFolder.newFolder(), "jvm.hprof")
    JvmTestHeapDumper.dumpHeap(file.absolutePath)
    check(sharing.size == SHARING_COUNT && bigString.length == SHARED_CHAR_COUNT)

    file.openHeapGraph().use { graph ->
      val stringCountByValueId = mutableMapOf<Long, Int>()
      graph.instances.filter { it.instanceClassName == "java.lang.String" }.forEach { string ->
        val valueId = string["java.lang.String", "value"]?.value?.asNonNullObjectId
          ?: return@forEach
        stringCountByValueId[valueId] = (stringCountByValueId[valueId] ?: 0) + 1
      }
      // Fail loudly if the scenario this test is about isn't in the dump anymore.
      assertThat(stringCountByValueId.values.maxOrNull()).isGreaterThanOrEqualTo(SHARING_COUNT)

      val root = ObjectDominators().buildDominatorTree(graph, emptyList())
        .getValue(NULL_REFERENCE)

      assertThat(root.retainedSize).isLessThanOrEqualTo(graph.ownSizeOfEveryObject())
    }
  }

  /** Makes [elements] reachable, all of them from a single GC rooted object array. */
  private fun HprofWriterHelper.gcRootedObjectArray(elements: List<ReferenceHolder>) {
    val holder = objectArray(*elements.toTypedArray())
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }

  /**
   * The sum of what every object in the heap dump takes on its own, i.e. what [ObjectDominators]
   * reports as retained by the whole graph when every object is counted once.
   */
  private fun HeapGraph.ownSizeOfEveryObject(): Long = objects.sumOf { heapObject ->
    when (heapObject) {
      is HeapClass -> heapObject.recordSize
      is HeapInstance -> heapObject.byteSize
      is HeapObjectArray -> heapObject.byteSize
      is HeapPrimitiveArray -> heapObject.byteSize
    }
  }

  companion object {
    private const val SHARING_COUNT = 200
    private const val SHARED_CHAR_COUNT = 1_000_000
    private const val BYTES_PER_CHAR = 2L
  }
}
