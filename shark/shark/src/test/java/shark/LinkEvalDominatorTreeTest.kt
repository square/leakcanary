package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.ReferenceHolder
import java.io.File

class LinkEvalDominatorTreeTest {

  @get:Rule
  var testFolder = TemporaryFolder()
  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  /**
   * Diamond graph — the exact case where the BFS-based [DominatorTree] gives a wrong answer.
   *
   * Edges:  root→a, root→d, a→b, a→c, d→e, e→b (cross), b→c (cross)
   *
   * True immediate dominators (Lengauer-Tarjan gives exact result):
   *   dom(a)=root, dom(d)=root, dom(b)=root, dom(e)=d, dom(c)=root
   *
   * BFS gives the wrong dom(c)=a because the B→C cross-edge is processed while dom(B) is
   * still stale (=A), before the E→B cross-edge raises dom(B) to root.
   * Consequence: with 10 bytes/object, BFS reports a.retainedSize=20 (a+c), but the correct
   * answer is a.retainedSize=10 (a only).
   */
  @Test fun `LinkEvalDominatorTree gives exact result for diamond graph`() {
    var rootId = 0L
    var aId = 0L
    var bId = 0L
    var cId = 0L
    var dId = 0L
    var eId = 0L

    hprofFile.dump {
      // Node class with two reference fields. Use ReferenceHolder(0) as a null child.
      val nodeClassId = clazz(
        className = "Node",
        fields = listOf(
          "child1" to ReferenceHolder::class,
          "child2" to ReferenceHolder::class
        )
      )
      val noChild = ReferenceHolder(0)

      // Create instances leaf-to-root so IDs are available when we reference them.
      val cRef = instance(nodeClassId, listOf(noChild, noChild))
      val bRef = instance(nodeClassId, listOf(cRef, noChild))
      val eRef = instance(nodeClassId, listOf(bRef, noChild))
      val aRef = instance(nodeClassId, listOf(bRef, cRef))
      val dRef = instance(nodeClassId, listOf(eRef, noChild))
      val rootRef = instance(nodeClassId, listOf(aRef, dRef))

      cId = cRef.value
      bId = bRef.value
      eId = eRef.value
      aId = aRef.value
      dId = dRef.value
      rootId = rootRef.value

      // Make root a GC root so the whole graph is reachable.
      gcRoot(JniGlobal(rootId, 1))
    }

    hprofFile.openHeapGraph().use { graph ->
      val tree = LinkEvalDominatorTree(
        graph,
        ActualMatchingReferenceReaderFactory(emptyList()),
        MatchingGcRootProvider(emptyList())
      )
      // Give every object a shallow size of 10 bytes.
      val result = tree.compute { _ -> 10 }

      // With correct L-T dominators (10 bytes/object each):
      //
      //   root is dominated only by the virtual root, and dominates a, b, c, d, e:
      //     retained = 60 (root + a + b + c + d + e)
      assertThat(result.getValue(rootId).retainedSize).isEqualTo(60)

      //   a only dominates itself (dom(b)=root, dom(c)=root):
      //     retained = 10
      assertThat(result.getValue(aId).retainedSize).isEqualTo(10)

      //   d dominates e (e is only reachable through d):
      //     retained = 20 (d + e)
      assertThat(result.getValue(dId).retainedSize).isEqualTo(20)

      //   b, c, e each dominate only themselves:
      assertThat(result.getValue(bId).retainedSize).isEqualTo(10)
      assertThat(result.getValue(cId).retainedSize).isEqualTo(10)
      assertThat(result.getValue(eId).retainedSize).isEqualTo(10)
    }
  }
}
