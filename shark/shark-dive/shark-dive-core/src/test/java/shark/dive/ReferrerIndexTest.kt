package shark.dive

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ActualMatchingReferenceReaderFactory
import shark.GcRoot.JniGlobal
import shark.HeapGraph
import shark.HeapObject
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LibraryLeakReferenceMatcher
import shark.ReferencePattern.InstanceFieldPattern
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

  @Test fun `referrers come back highest object index first`() {
    // Which is load bearing rather than incidental: a breadth first walk up the referrers takes the first
    // of two equally distant ones, so this order decides which chain the window draws. See
    // notes/referrer-index.md for what reversing it costs.
    openIndexed { graph, index ->
      val referrers = index.referrerIndexesOf(graph.findByClassName("com.example.Shared"))

      assertThat(referrers).hasSize(3).isEqualTo(referrers.sortedDescending())
    }
  }

  @Test fun `a referrer is a last resort only if every field it holds an object by is one`() {
    val file = testFolder.newFile("library-leak.hprof")
    file.dump {
      val payload = "com.example.Payload" instance {}
      val mixed = "com.example.Mixed" instance {
        field["known"] = payload
        field["plain"] = payload
      }
      val allKnown = "com.example.AllKnown" instance {
        field["known"] = payload
        field["alsoKnown"] = payload
      }
      val holder = "com.example.Holder" instance {
        field["mixed"] = mixed
        field["allKnown"] = allKnown
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    file.openHeapGraph().use { graph ->
      val knownLeaks = listOf("Mixed" to "known", "AllKnown" to "known", "AllKnown" to "alsoKnown")
        .map { (className, fieldName) ->
          LibraryLeakReferenceMatcher(InstanceFieldPattern("com.example.$className", fieldName))
        }
      val index = ReferrerIndex.buildFor(
        graph,
        ActualMatchingReferenceReaderFactory(knownLeaks).createFor(graph)
      )

      val payload = index.indexOf(graph.findByClassName("com.example.Payload"))
      val lastResortByReferrer = mutableMapOf<String, Boolean>()
      index.forEachReferrer(payload) { referrer, isLowPriority ->
        lastResortByReferrer[graph.findObjectByIndex(referrer).simpleClassName()] = isLowPriority
      }

      // A field known to hold on to things is a reason to leave a referrer until last, but only if it is the
      // only way that referrer holds the object: the plain field holds it whatever the known one does.
      assertThat(lastResortByReferrer).containsOnly(entry("Mixed", false), entry("AllKnown", true))
    }
  }

  @Test fun `referrers written far apart in the heap dump are both found`() {
    // A referrer is stored as the step down from the one before it, seven bits of a step to a byte, so a
    // step spread over several bytes only comes up with thousands of objects between two referrers.
    val file = testFolder.newFile("far-apart.hprof")
    val fillerCount = 5_000
    file.dump {
      val payload = "com.example.Payload" instance {}
      val first = "com.example.First" instance { field["payload"] = payload }
      val fillerClassId = clazz("com.example.Filler")
      repeat(fillerCount) { instance(fillerClassId) }
      val last = "com.example.Last" instance { field["payload"] = payload }
      val holder = "com.example.Holder" instance {
        field["first"] = first
        field["last"] = last
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    file.openHeapGraph().use { graph ->
      val referenceReader = ActualMatchingReferenceReaderFactory(emptyList()).createFor(graph)
      val index = ReferrerIndex.buildFor(graph, referenceReader)

      val referrers = index.referrerIndexesOf(graph.findByClassName("com.example.Payload"))
      assertThat(referrers.map { graph.findObjectByIndex(it).simpleClassName() })
        .containsExactly("Last", "First")
      // What the filler is for: the two referrers really are too far apart to name in a single byte.
      assertThat(referrers.first() - referrers.last()).isGreaterThan(fillerCount)
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

  private fun ReferrerIndex.referrersOf(objectId: Long): List<Long> =
    referrerIndexesOf(objectId).map { objectIdAt(it) }

  private fun ReferrerIndex.referrerIndexesOf(objectId: Long): List<Int> {
    val referrers = mutableListOf<Int>()
    forEachReferrer(indexOf(objectId)) { referrer, _ -> referrers += referrer }
    return referrers
  }

  private fun HeapGraph.findByClassName(className: String): Long =
    findClassByName(className)!!.instances.single().objectId

  private fun HeapObject.simpleClassName(): String =
    asObjectArray?.arrayClassSimpleName ?: asInstance!!.instanceClassSimpleName
}
