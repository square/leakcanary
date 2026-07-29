package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.internal.ObjectIdSet

class ObjectIdSetTest {

  /**
   * A heap dump with objects of all four record types, so that the mapping from object id to object
   * index has to resolve each of the four per-record-type indexes.
   */
  private fun dumpWithEveryRecordType() = dump {
    "com.example.Empty" clazz {}
    val referenced = instance(clazz("com.example.Referenced"))
    objectArray(referenced, referenced)
    primitiveLongArray(longArrayOf(1, 2, 3))
    "com.example.Holder" instance {
      field["referenced"] = referenced
    }
    primitiveLongArray(longArrayOf(4))
  }

  @Test fun `every object index round trips through its object id`() {
    dumpWithEveryRecordType().openHeapGraph().use { graph ->
      graph as HprofHeapGraph
      val indexByObjectId = (0 until graph.objectCount).associateBy { objectIndex ->
        graph.findObjectByIndex(objectIndex).objectId
      }
      // Every object index maps to a distinct object id, so no index was computed twice.
      assertThat(indexByObjectId).hasSize(graph.objectCount)

      indexByObjectId.forEach { (objectId, objectIndex) ->
        assertThat(graph.objectIndexOrNull(objectId)).isEqualTo(objectIndex)
      }
    }
  }

  @Test fun `object ids that aren't in the heap dump have no object index`() {
    dumpWithEveryRecordType().openHeapGraph().use { graph ->
      graph as HprofHeapGraph
      assertThat(graph.objectIndexOrNull(Long.MAX_VALUE)).isEqualTo(-1)
    }
  }

  @Test fun `an object is added once and then contained`() {
    dumpWithEveryRecordType().openHeapGraph().use { graph ->
      val set = ObjectIdSet(graph)
      val objectIds = (0 until graph.objectCount).map { graph.findObjectByIndex(it).objectId }

      objectIds.forEach { objectId ->
        assertThat(objectId in set).isFalse()
      }
      objectIds.forEach { objectId ->
        assertThat(set.add(objectId)).isTrue()
      }
      objectIds.forEach { objectId ->
        assertThat(objectId in set).isTrue()
        assertThat(set.add(objectId)).isFalse()
      }
    }
  }

  @Test fun `adding one object leaves the others out of the set`() {
    dumpWithEveryRecordType().openHeapGraph().use { graph ->
      val objectIds = (0 until graph.objectCount).map { graph.findObjectByIndex(it).objectId }

      objectIds.forEach { addedObjectId ->
        val set = ObjectIdSet(graph)
        set.add(addedObjectId)
        objectIds.forEach { objectId ->
          assertThat(objectId in set).isEqualTo(objectId == addedObjectId)
        }
      }
    }
  }

  @Test fun `object ids that aren't in the heap dump are held too`() {
    dumpWithEveryRecordType().openHeapGraph().use { graph ->
      val set = ObjectIdSet(graph)
      val unknownObjectId = Long.MAX_VALUE

      assertThat(unknownObjectId in set).isFalse()
      assertThat(set.add(unknownObjectId)).isTrue()
      assertThat(unknownObjectId in set).isTrue()
      assertThat(set.add(unknownObjectId)).isFalse()
    }
  }
}
