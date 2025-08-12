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

  @Test fun `py is wondering if this is broken`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }

    val ABParent = newObjectId().apply { tree.updateDominated(this, root) }
    val A = newObjectId().apply { tree.updateDominated(this, ABParent) }
    val B = newObjectId().apply { tree.updateDominated(this, ABParent) }
    val C = newObjectId().apply { tree.updateDominated(this, root) }
    val AB = newObjectId()
    tree.updateDominated(AB, A)
    tree.updateDominated(AB, B)
    val C1 = newObjectId()
    tree.updateDominated(C1, C)
    tree.updateDominated(B, C1)

    assertThat(tree[AB]).isEqualTo(root)
  }
}
