package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph

class PrioritizingShortestPathFinderTest {

  @Test fun `leaking object only reachable through another leaking object is reported as a label`() {
    var childObjectId = 0L
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Parent" watchedInstance {
          field["child"] = ("Child" watchedInstance {}).also { childObjectId = it.value }
        }
      }
    }

    val analysis = heapDump.checkForLeaks<HeapAnalysisSuccess>()

    // Child is reachable, just not without going through Parent, so it's not unreachable and it
    // doesn't get a leak trace of its own.
    assertThat(analysis.unreachableObjects).isEmpty()
    val leakTraces = analysis.applicationLeaks.flatMap { it.leakTraces }
    assertThat(leakTraces).hasSize(1)
    val leakingObject = leakTraces.single().leakingObject
    assertThat(leakingObject.className).isEqualTo("Parent")
    assertThat(leakingObject.labels).contains(
      "Also retains leaking object ${childObjectId.asObjectIdString()} (Child)"
    )
  }

  @Test fun `leaking objects of the same class are reported in a single label`() {
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = "Parent" watchedInstance {
          field["firstChild"] = "Child" watchedInstance {}
          field["secondChild"] = "Child" watchedInstance {}
        }
      }
    }

    val analysis = heapDump.checkForLeaks<HeapAnalysisSuccess>()

    // One label rather than one per object id: a leaking object can retain dozens of others, and
    // a label each would bury the rest of the leak trace.
    val leakTraces = analysis.applicationLeaks.flatMap { it.leakTraces }
    assertThat(leakTraces).hasSize(1)
    assertThat(leakTraces.single().leakingObject.labels).contains(
      "Also retains 2 leaking Child objects"
    )
  }

  @Test fun `leaking object also reachable without going through another leaking object gets its own leak trace`() {
    var parentObjectId = 0L
    val heapDump = dump {
      val child = "Child" watchedInstance {}
      "GcRoot" clazz {
        staticField["shortestPath"] = ("Parent" watchedInstance {
          field["child"] = child
        }).also { parentObjectId = it.value }
        staticField["otherPath"] = "Holder" instance {
          field["child"] = child
        }
      }
    }

    val analysis = heapDump.checkForLeaks<HeapAnalysisSuccess>(computeRetainedHeapSize = true)

    // Fixing the Parent leak wouldn't free Child, since Child is also reachable through Holder,
    // so Child is a leak of its own, reported with the path that doesn't go through Parent.
    val leakTraces = analysis.applicationLeaks.flatMap { it.leakTraces }
      .associateBy { it.leakingObject.className }
    assertThat(leakTraces.keys).containsOnly("Parent", "Child")
    assertThat(leakTraces.getValue("Child").referencePath.map { it.referenceName })
      .containsExactly("otherPath", "child")

    // Only Child says so: the path it's reached through isn't in either leak trace, and Parent has
    // its own path to explain rather than the leaking objects it happens to also retain.
    assertThat(leakTraces.getValue("Child").leakingObject.labels).contains(
      "Also retained by leaking object ${parentObjectId.asObjectIdString()} (Parent), which has its own leak trace"
    )
    assertThat(leakTraces.getValue("Parent").leakingObject.labels).noneMatch {
      it.startsWith("Also retain")
    }
  }

  @Test fun `traversal stops once all leaking objects are found when not computing sizes`() {
    var leakingObjectId = 0L
    var childObjectId = 0L
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["shortestPath"] = ("Leaking" watchedInstance {
          field["child"] = ("Child" instance {}).also { childObjectId = it.value }
        }).also { leakingObjectId = it.value }
      }
    }

    val readObjectIds = mutableListOf<Long>()

    heapDump.openHeapGraph().use { graph ->
      val pathFinder = PrioritizingShortestPathFinder.Factory(
        listener = { },
        referenceReaderFactory = ReferenceReader.Factory<HeapObject> { heapGraph ->
          val delegate =
            ActualMatchingReferenceReaderFactory(JdkReferenceMatchers.defaults).createFor(heapGraph)
          ReferenceReader { source ->
            readObjectIds += source.objectId
            delegate.read(source)
          }
        },
        gcRootProvider = MatchingGcRootProvider(JdkReferenceMatchers.defaults),
        objectSizeCalculatorFactory = null,
      ).createFor(graph)

      val results = pathFinder.findShortestPathsFromGcRoots(setOf(leakingObjectId))

      assertThat(results.pathsToLeakingObjects.map { it.objectId }).containsExactly(leakingObjectId)
      assertThat(results.retainedSizes).isNull()
      // Phase 2 has nothing left to do: there are no retained sizes to compute and no leaking
      // object left to find, so the subgraph retained by the leaking object is never read.
      assertThat(readObjectIds).doesNotContain(leakingObjectId, childObjectId)
    }
  }
}
