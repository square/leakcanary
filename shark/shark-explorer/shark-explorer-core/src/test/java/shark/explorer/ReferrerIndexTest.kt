package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ActualMatchingReferenceReaderFactory
import shark.GcRoot.JniGlobal
import shark.HeapGraph
import shark.HeapObject
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.ReferenceHolder
import shark.dump

class ReferrerIndexTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `an object is a referrer once however many of its fields point at it`() {
    openIndexed { graph, index ->
      val holder = graph.findByClassName("com.example.Holder")
      val payload = graph.findByClassName("com.example.Payload")

      // Two fields pointing at the same object is one way of holding it, and a path through it would read
      // the same either way. Counting it twice would report the same path twice over.
      assertThat(index.referrersOf(payload)).containsExactly(holder)
    }
  }

  @Test fun `every object pointing at one is a referrer of it`() {
    openIndexed { graph, index ->
      val shared = graph.findByClassName("com.example.Shared")

      assertThat(index.referrersOf(shared).map { graph.findObjectById(it).simpleClassName() })
        .containsExactlyInAnyOrder("Holder", "Payload", "Object[]")
    }
  }

  @Test fun `nothing points at a gc root's own object`() {
    openIndexed { graph, index ->
      assertThat(index.referrersOf(graph.findByClassName("com.example.Holder"))).isEmpty()
    }
  }

  @Test fun `an object id the heap dump has no object for is not an object`() {
    openIndexed { graph, index ->
      assertThat(index.objectCount).isEqualTo(graph.objectCount)
      assertThat(index.indexOf(0L)).isEqualTo(ReferrerIndex.NOT_AN_OBJECT)
      val holder = graph.findByClassName("com.example.Holder")
      assertThat(index.objectIdAt(index.indexOf(holder))).isEqualTo(holder)
    }
  }

  /**
   * Indexes a heap dump where a holder points at a payload twice, and the holder, the payload and an
   * array all point at one shared object.
   */
  private fun openIndexed(block: (HeapGraph, ReferrerIndex) -> Unit) {
    val file = testFolder.newFile("referrers.hprof")
    file.dump {
      val shared = "com.example.Shared" instance {}
      val payload = "com.example.Payload" instance { field["shared"] = shared }
      val array = objectArray(arrayClass("java.lang.Object"), longArrayOf(shared.value))
      val holder = "com.example.Holder" instance {
        field["payload"] = payload
        field["alsoPayload"] = payload
        field["shared"] = shared
        field["array"] = ReferenceHolder(array)
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    file.openHeapGraph().use { graph ->
      val referenceReader = ActualMatchingReferenceReaderFactory(emptyList()).createFor(graph)
      block(graph, ReferrerIndex.buildFor(graph, referenceReader))
    }
  }

  private fun ReferrerIndex.referrersOf(objectId: Long): List<Long> {
    val referrers = mutableListOf<Long>()
    forEachReferrer(indexOf(objectId)) { referrer, _ -> referrers += objectIdAt(referrer) }
    return referrers
  }

  private fun HeapGraph.findByClassName(className: String): Long =
    findClassByName(className)!!.instances.single().objectId

  private fun HeapObject.simpleClassName(): String =
    asObjectArray?.arrayClassSimpleName ?: asInstance!!.instanceClassSimpleName
}
