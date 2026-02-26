package shark.internal

import androidx.collection.mutableLongSetOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.DominatorTree
import shark.ValueHolder

@Suppress("UsePropertyAccessSyntax")
class DominatorTreeTest {

  var latestObjectId: Long = 0
  private fun newObjectId() = ObjectId(++latestObjectId)

  @Suppress("PrivatePropertyName")
  private val `10 bytes per object`: (Long) -> Int = { 10 }

  /**
   * DSL scope for setting up dominator relationships. Use [asRoot] to mark GC roots and the
   * `>` operator to register parent→child edges: `a > b` means "a is the parent of b", i.e.
   * [DominatorTree.updateDominated] with b as the object and a as the parent.
   */
  private inner class DominatorScope(val tree: DominatorTree) {
    fun newObjectId() = this@DominatorTreeTest.newObjectId()

    val forestRoot = ObjectId(ValueHolder.NULL_REFERENCE)

    operator fun ObjectId.compareTo(other: ObjectId): Int {
      tree.updateDominated(other.id, id)
      return 1
    }
  }

  private fun DominatorTree.updateDominators(block: DominatorScope.() -> Unit) =
    DominatorScope(this).block()

  @Test fun `new object is not already dominated`() {
    val tree = DominatorTree()
    val root = newObjectId()
    tree.updateDominators {
      forestRoot > root
    }

    val alreadyDominated = tree.updateDominated(newObjectId().id, root.id)

    assertThat(alreadyDominated).isFalse()
  }

  @Test fun `dominated object is already dominated`() {
    val tree = DominatorTree()
    val root1 = newObjectId()
    val root2 = newObjectId()
    val child = newObjectId()
    tree.updateDominators {
      forestRoot > root1
      forestRoot > root2
      root1 > child
    }

    val alreadyDominated = tree.updateDominated(child.id, root2.id)

    assertThat(alreadyDominated).isTrue()
  }

  @Test fun `only retained objects are returned in sizes map`() {
    val tree = DominatorTree()
    val root = newObjectId()
    val child = newObjectId()
    tree.updateDominators {
      forestRoot > root
      root > child
    }

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(child.id), `10 bytes per object`)

    val keys = mutableSetOf<Long>()
    sizes.forEachKey { keys += it }

    assertThat(keys).containsOnly(child.id)
  }

  @Test fun `single root has self size as retained size`() {
    val tree = DominatorTree()
    val root = newObjectId()
    tree.updateDominators {
      forestRoot > root
    }

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root.id), `10 bytes per object`)

    assertThat(sizes[root.id]).isEqualTo(10 packedWith 1)
  }

  @Test fun `size of dominator includes dominated`() {
    val tree = DominatorTree()
    val root = newObjectId()
    val child = newObjectId()
    tree.updateDominators {
      forestRoot > root
      root > child
    }

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root.id), `10 bytes per object`)

    assertThat(sizes[root.id]).isEqualTo(20 packedWith 2)
  }

  @Test fun `size of chain of dominators is additive`() {
    val tree = DominatorTree()
    val root = newObjectId()
    val child = newObjectId()
    val grandChild = newObjectId()
    tree.updateDominators {
      forestRoot > root
      root > child
      child > grandChild
    }

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root.id, child.id), `10 bytes per object`)

    assertThat(sizes[root.id]).isEqualTo(30 packedWith 3)
    assertThat(sizes[child.id]).isEqualTo(20 packedWith 2)
  }

  @Test fun `diamond dominators don't dominate`() {
    val tree = DominatorTree()
    val root = newObjectId()
    val child1 = newObjectId()
    val child2 = newObjectId()
    val grandChild = newObjectId()
    tree.updateDominators {
      forestRoot > root
      root > child1
      root > child2
      child1 > grandChild
      child2 > grandChild
    }

    val sizes = tree.computeRetainedSizes(
      mutableLongSetOf(root.id, child1.id, child2.id), `10 bytes per object`
    )

    assertThat(sizes[child1.id]).isEqualTo(10 packedWith 1)
    assertThat(sizes[child2.id]).isEqualTo(10 packedWith 1)
    assertThat(sizes[root.id]).isEqualTo(40 packedWith 4)
  }

  @Test fun `two dominators dominated by common ancestor`() {
    val tree = DominatorTree()
    val root = newObjectId()
    val child1 = newObjectId()
    val child2 = newObjectId()
    val grandChild = newObjectId()
    tree.updateDominators {
      forestRoot > root
      root > child1
      root > child2
      child1 > grandChild
      child2 > grandChild
    }

    val sizes = tree.computeRetainedSizes(
      mutableLongSetOf(root.id, child1.id, child2.id), `10 bytes per object`
    )

    assertThat(sizes[child1.id]).isEqualTo(10 packedWith 1)
    assertThat(sizes[child2.id]).isEqualTo(10 packedWith 1)
    assertThat(sizes[root.id]).isEqualTo(40 packedWith 4)
  }

  @Test fun `two dominators dominated by lowest common ancestor`() {
    val tree = DominatorTree()
    val root = newObjectId()
    val child = newObjectId()
    val grandChild1 = newObjectId()
    val grandChild2 = newObjectId()
    val grandGrandChild = newObjectId()
    tree.updateDominators {
      forestRoot > root
      root > child
      child > grandChild1
      child > grandChild2
      grandChild1 > grandGrandChild
      grandChild2 > grandGrandChild
    }

    val sizes = tree.computeRetainedSizes(
      mutableLongSetOf(root.id, child.id, grandChild1.id, grandChild2.id), `10 bytes per object`
    )

    assertThat(sizes[grandChild1.id]).isEqualTo(10 packedWith 1)
    assertThat(sizes[grandChild2.id]).isEqualTo(10 packedWith 1)
    assertThat(sizes[child.id]).isEqualTo(40 packedWith 4)
    assertThat(sizes[root.id]).isEqualTo(50 packedWith 5)
  }

  @Test fun `two separate trees do not share size`() {
    val tree = DominatorTree()
    val root1 = newObjectId()
    val root2 = newObjectId()
    tree.updateDominators {
      forestRoot > root1
      forestRoot > root2
      var desc1 = root1
      var desc2 = root2
      for (i in 1..10) {
        val next1 = newObjectId()
        val next2 = newObjectId()
        desc1 > next1
        desc2 > next2
        desc1 = next1
        desc2 = next2
      }
    }

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root1.id, root2.id), `10 bytes per object`)

    assertThat(sizes[root1.id]).isEqualTo(110 packedWith 11)
    assertThat(sizes[root2.id]).isEqualTo(110 packedWith 11)
  }

  @Test fun `no common descendant does not include size`() {
    val tree = DominatorTree()
    val root1 = newObjectId()
    val root2 = newObjectId()
    tree.updateDominators {
      forestRoot > root1
      forestRoot > root2
      var desc = root1
      for (i in 1..10) {
        val next = newObjectId()
        desc > next
        desc = next
      }
      root2 > desc
    }

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(root1.id, root2.id), `10 bytes per object`)

    assertThat(sizes[root1.id]).isEqualTo(100 packedWith 10)
    assertThat(sizes[root2.id]).isEqualTo(10 packedWith 1)
  }

  @Test fun `only compute retained size for retained objects`() {
    val tree = DominatorTree()
    val root1 = newObjectId()
    val root2 = newObjectId()
    val child = newObjectId()
    val grandChild = newObjectId()
    val grandGrandChild = newObjectId()
    tree.updateDominators {
      forestRoot > root1
      forestRoot > root2
      root1 > child
      child > grandChild
      grandChild > grandGrandChild
      root2 > grandGrandChild
    }

    val objectsWithComputedSize = mutableSetOf<Long>()
    tree.computeRetainedSizes(mutableLongSetOf(child.id)) { objectId ->
      objectsWithComputedSize += objectId
      1
    }

    assertThat(objectsWithComputedSize).containsOnly(child.id, grandChild.id)
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
    val root = newObjectId()
    val a = newObjectId()
    val d = newObjectId()
    val b = newObjectId()
    val c = newObjectId()
    val e = newObjectId()
    tree.updateDominators {
      forestRoot > root
      root > a       // tree edge
      root > d       // tree edge
      a > b          // tree edge: dom(B)=A
      a > c          // tree edge: dom(C)=A
      d > e          // tree edge: dom(E)=D
      b > c          // cross-edge: B→C processed with stale dom(B)=A → dom(C) stays A
      e > b          // cross-edge: E→B raises dom(B) to root ✓, but dom(C) never updated
    }

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(a.id), `10 bytes per object`)

    // BUG: dom(C)=A (stale). C is incorrectly attributed to A. A retains itself + C = 20 bytes.
    // Correct expected value would be: assertThat(sizes[a.id]).isEqualTo(10 packedWith 1)
    assertThat(sizes[a.id]).isEqualTo(20 packedWith 2)
  }

  @Test fun `convergence loop fixes stale dominator attribution`() {
    val tree = DominatorTree(collectCrossEdges = true)
    val root = newObjectId()
    val a = newObjectId()
    val d = newObjectId()
    val b = newObjectId()
    val c = newObjectId()
    val e = newObjectId()
    tree.updateDominators {
      forestRoot > root
      root > a
      root > d
      a > b
      a > c
      d > e
      b > c  // cross-edge: stale dom(B)=A at time of processing
      e > b  // cross-edge: raises dom(B) to root
    }

    tree.runConvergenceLoop(maxIterations = Int.MAX_VALUE)

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(a.id), `10 bytes per object`)

    // After convergence: dom(C)=root (fixed). A retains only itself.
    assertThat(sizes[a.id]).isEqualTo(10 packedWith 1)
  }

  @Test fun `convergence loop stops at maxIterations`() {
    val tree = DominatorTree(collectCrossEdges = true)
    val root = newObjectId()
    val a = newObjectId()
    val d = newObjectId()
    val b = newObjectId()
    val c = newObjectId()
    val e = newObjectId()
    tree.updateDominators {
      forestRoot > root
      root > a
      root > d
      a > b
      a > c
      d > e
      b > c
      e > b
    }

    // 0 iterations: no changes applied
    tree.runConvergenceLoop(maxIterations = 0)

    val sizes = tree.computeRetainedSizes(mutableLongSetOf(a.id), `10 bytes per object`)

    // Same as the unfixed bug: dom(C) still stale
    assertThat(sizes[a.id]).isEqualTo(20 packedWith 2)
  }

  @Test fun `null ref dominates all`() {
    val tree = DominatorTree()
    val root1 = newObjectId()
    val root2 = newObjectId()
    val root3 = newObjectId()
    val child = newObjectId()
    val grandChild = newObjectId()
    tree.updateDominators {
      forestRoot > root1
      forestRoot > root2
      forestRoot > root3
      root1 > child
      root2 > child
      child > grandChild
    }

    val fullDominatorTree = tree.buildFullDominatorTree(`10 bytes per object`)

    assertThat(fullDominatorTree.getValue(root1.id).retainedSize).isEqualTo(10)
    assertThat(fullDominatorTree.getValue(root2.id).retainedSize).isEqualTo(10)
    assertThat(fullDominatorTree.getValue(root3.id).retainedSize).isEqualTo(10)
    assertThat(fullDominatorTree.getValue(child.id).retainedSize).isEqualTo(20)
    assertThat(fullDominatorTree.getValue(grandChild.id).retainedSize).isEqualTo(10)
    assertThat(fullDominatorTree.getValue(ValueHolder.NULL_REFERENCE).retainedSize).isEqualTo(50)
  }

  @JvmInline value class ObjectId(val id: Long)
}
