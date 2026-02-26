package shark.internal

import androidx.collection.mutableLongSetOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.DominatorTree
import shark.ValueHolder

@Suppress("UsePropertyAccessSyntax")
class DominatorTreeTest {

  var latestObjectId: Long = 0
  private fun newObjectId() = ++latestObjectId

  @Suppress("PrivatePropertyName")
  private val `10 bytes per object`: (Long) -> Int = { 10 }

  @Test fun `new object is not already dominated`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }

    val alreadyDominated = tree.updateDominated(newObjectId(), root)

    assertThat(alreadyDominated).isFalse()
  }

  @Test fun `dominated object is already dominated`() {
    val tree = DominatorTree()
    val root1 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root2 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root1) }

    val alreadyDominated = tree.updateDominated(child, root2)

    assertThat(alreadyDominated).isTrue()
  }

  @Test fun `only retained objects are returned in sizes map`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root) }

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(child), `10 bytes per object`)

    val keys = mutableSetOf<Long>()
    sizes.forEachKey { keys += it }

    assertThat(keys).containsOnly(child)
  }

  @Test fun `single root has self size as retained size`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root), `10 bytes per object`)

    assertThat(sizes[root]).isEqualTo(10 packedWith 1)
  }

  @Test fun `size of dominator includes dominated`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    tree.updateDominated(newObjectId(), root)

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root), `10 bytes per object`)

    assertThat(sizes[root]).isEqualTo(20 packedWith 2)
  }

  @Test fun `size of chain of dominators is additive`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root) }
    tree.updateDominated(newObjectId(), child)

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root, child), `10 bytes per object`)

    assertThat(sizes[root]).isEqualTo(30 packedWith 3)
    assertThat(sizes[child]).isEqualTo(20 packedWith 2)
  }

  @Test fun `diamond dominators don't dominate`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child1 = newObjectId().apply { tree.updateDominated(this, root) }
    val child2 = newObjectId().apply { tree.updateDominated(this, root) }
    val grandChild = newObjectId()
    tree.updateDominated(grandChild, child1)
    tree.updateDominated(grandChild, child2)

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root, child1, child2), `10 bytes per object`)

    assertThat(sizes[child1]).isEqualTo(10 packedWith 1)
    assertThat(sizes[child2]).isEqualTo(10 packedWith 1)
    assertThat(sizes[root]).isEqualTo(40 packedWith 4)
  }

  @Test fun `two dominators dominated by common ancestor`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child1 = newObjectId().apply { tree.updateDominated(this, root) }
    val child2 = newObjectId().apply { tree.updateDominated(this, root) }
    val grandChild = newObjectId()
    tree.updateDominated(grandChild, child1)
    tree.updateDominated(grandChild, child2)

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root, child1, child2), `10 bytes per object`)

    assertThat(sizes[child1]).isEqualTo(10 packedWith 1)
    assertThat(sizes[child2]).isEqualTo(10 packedWith 1)
    assertThat(sizes[root]).isEqualTo(40 packedWith 4)
  }

  @Test fun `two dominators dominated by lowest common ancestor`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root) }
    val grandChild1 = newObjectId().apply { tree.updateDominated(this, child) }
    val grandChild2 = newObjectId().apply { tree.updateDominated(this, child) }
    val grandGrandChild = newObjectId()
    tree.updateDominated(grandGrandChild, grandChild1)
    tree.updateDominated(grandGrandChild, grandChild2)

    val sizes =
      tree.computeRetainedSizes(mutableLongSetOf(root, child, grandChild1, grandChild2), `10 bytes per object`)

    assertThat(sizes[grandChild1]).isEqualTo(10 packedWith 1)
    assertThat(sizes[grandChild2]).isEqualTo(10 packedWith 1)
    assertThat(sizes[child]).isEqualTo(40 packedWith 4)
    assertThat(sizes[root]).isEqualTo(50 packedWith 5)
  }

  @Test fun `two separate trees do not share size`() {
    val tree = DominatorTree()
    val root1 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root2 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    var descendant1 = root1
    var descendant2 = root2
    for (i in 1..10) {
      descendant1 = newObjectId().apply { tree.updateDominated(this, descendant1) }
      descendant2 = newObjectId().apply { tree.updateDominated(this, descendant2) }
    }

    val sizes =
      tree.computeRetainedSizes(mutableLongSetOf(root1, root2), `10 bytes per object`)

    assertThat(sizes[root1]).isEqualTo(110 packedWith 11)
    assertThat(sizes[root2]).isEqualTo(110 packedWith 11)
  }

  @Test fun `no common descendant does not include size`() {
    val tree = DominatorTree()
    val root1 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root2 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    var descendant = root1
    for (i in 1..10) {
      descendant = newObjectId().apply { tree.updateDominated(this, descendant) }
    }
    tree.updateDominated(descendant, root2)

    val sizes =
      tree.computeRetainedSizes(mutableLongSetOf(root1, root2), `10 bytes per object`)

    assertThat(sizes[root1]).isEqualTo(100 packedWith 10)
    assertThat(sizes[root2]).isEqualTo(10 packedWith 1)
  }

  @Test fun `only compute retained size for retained objects`() {
    val tree = DominatorTree()
    val root1 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root2 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root1) }
    val grandChild = newObjectId().apply { tree.updateDominated(this, child) }
    val grandGrandChild = newObjectId().apply { tree.updateDominated(this, grandChild) }
    tree.updateDominated(grandGrandChild, root2)

    val objectsWithComputedSize = mutableSetOf<Long>()
    tree.computeRetainedSizes(mutableLongSetOf(child)) { objectId ->
      objectsWithComputedSize += objectId
      1
    }

    assertThat(objectsWithComputedSize).containsOnly(child, grandChild)
  }

  @Test fun `known bug - BFS ordering leaves child dominator too specific`() {
    // Graph (arrows = heap references):
    //
    //   root → A → B   (tree edges; A is enqueued/processed before D)
    //   root → A → C   (tree edges; C first discovered via A)
    //   root → D → E → B   (E→B is a cross-edge at the same BFS level as B)
    //               ↑
    //               B → C   (cross-edge: C already visited; processed when B is dequeued)
    //
    // BFS level layout:
    //   level 0: root
    //   level 1: A, D               (both enqueued from root)
    //   level 2: B, C, E            (B and C via A; E via D)
    //
    // BFS dequeue order for level 2: B, C, E  (B enqueued before C and E)
    //
    // True immediate dominators:
    //   dom(B) = root   (paths root→A→B and root→D→E→B; A is not on both)
    //   dom(C) = root   (paths root→A→C, root→A→B→C, root→D→E→B→C; root→D→E→B→C bypasses A)
    //
    // BUG: B→C (cross-edge) is processed when B is dequeued, with stale dom(B)=A.
    //   LCA(dom(C)=A, B) with dom(B)=A → walks B's chain: dom(B)=A ∈ {A,root} → LCA=A.
    //   dom(C) stays A.
    // Later: E→B (cross-edge) is processed when E is dequeued. LCA(A, E)=root. dom(B)=root ✓
    // But dom(C) is never revisited — it remains A even though root→D→E→B→C bypasses A.
    //
    // Consequence: C is incorrectly attributed to A. A's retained size is over-reported.
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val a = newObjectId().apply { tree.updateDominated(this, root) } // root→A
    val d = newObjectId().apply { tree.updateDominated(this, root) } // root→D
    val b = newObjectId().apply { tree.updateDominated(this, a) } // A→B, dom(B)=A
    val c = newObjectId().apply { tree.updateDominated(this, a) } // A→C, dom(C)=A
    val e = newObjectId().apply { tree.updateDominated(this, d) } // D→E, dom(E)=D
    // B is dequeued first: B→C cross-edge processed with stale dom(B)=A
    tree.updateDominated(c, b) // B→C: LCA(A,B) with dom(B)=A → LCA=A. dom(C) stays A. (stale)
    // E is dequeued after B: E→B cross-edge correctly raises dom(B)
    tree.updateDominated(b, e) // E→B: LCA(A,E)=root. dom(B)=root ✓
    // dom(C) is still A — it was not updated when dom(B) changed

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(a), `10 bytes per object`)

    // BUG: dom(C)=A (stale). C is incorrectly attributed to A. A retains itself + C = 20 bytes.
    // Correct expected value would be: assertThat(sizes[a]).isEqualTo(10 packedWith 1)
    assertThat(sizes[a]).isEqualTo(20 packedWith 2)
  }

  @Test fun `convergence loop fixes stale dominator attribution`() {
    val tree = DominatorTree(collectCrossEdges = true)
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val a = newObjectId().apply { tree.updateDominated(this, root) } // root→A
    val d = newObjectId().apply { tree.updateDominated(this, root) } // root→D
    val b = newObjectId().apply { tree.updateDominated(this, a) } // A→B
    val c = newObjectId().apply { tree.updateDominated(this, a) } // A→C
    val e = newObjectId().apply { tree.updateDominated(this, d) } // D→E
    tree.updateDominated(c, b) // B→C cross-edge (stale dom(B)=A at time of processing)
    tree.updateDominated(b, e) // E→B cross-edge raises dom(B) to root

    tree.runConvergenceLoop(maxIterations = Int.MAX_VALUE)

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(a), `10 bytes per object`)

    // After convergence: dom(C)=root (fixed). A retains only itself.
    assertThat(sizes[a]).isEqualTo(10 packedWith 1)
  }

  @Test fun `convergence loop stops at maxIterations`() {
    val tree = DominatorTree(collectCrossEdges = true)
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val a = newObjectId().apply { tree.updateDominated(this, root) }
    val d = newObjectId().apply { tree.updateDominated(this, root) }
    val b = newObjectId().apply { tree.updateDominated(this, a) }
    val c = newObjectId().apply { tree.updateDominated(this, a) }
    val e = newObjectId().apply { tree.updateDominated(this, d) }
    tree.updateDominated(c, b)
    tree.updateDominated(b, e)

    // 0 iterations: no changes applied
    tree.runConvergenceLoop(maxIterations = 0)

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(a), `10 bytes per object`)

    // Same as the unfixed bug: dom(C) still stale
    assertThat(sizes[a]).isEqualTo(20 packedWith 2)
  }

  @Test fun `null ref dominates all`() {
    val tree = DominatorTree()
    val root1 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root2 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root3 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId()
    tree.updateDominated(child, root1)
    tree.updateDominated(child, root2)
    val grandChild = newObjectId().apply { tree.updateDominated(this, child) }

    val fullDominatorTree = tree.buildFullDominatorTree(`10 bytes per object`)

    assertThat(fullDominatorTree.getValue(root1).retainedSize).isEqualTo(10)
    assertThat(fullDominatorTree.getValue(root2).retainedSize).isEqualTo(10)
    assertThat(fullDominatorTree.getValue(root3).retainedSize).isEqualTo(10)
    assertThat(fullDominatorTree.getValue(child).retainedSize).isEqualTo(20)
    assertThat(fullDominatorTree.getValue(grandChild).retainedSize).isEqualTo(10)
    assertThat(fullDominatorTree.getValue(ValueHolder.NULL_REFERENCE).retainedSize).isEqualTo(50)
  }
}
