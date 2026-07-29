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
      "Also retains leaking object $childObjectId (Child)"
    )
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
