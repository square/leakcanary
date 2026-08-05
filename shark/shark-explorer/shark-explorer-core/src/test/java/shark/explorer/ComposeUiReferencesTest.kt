package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump

/**
 * How a Compose UI reads: a tree of nodes under the view hosting it, each node holding the modifiers it
 * draws with, rather than a flat list of everything Compose points at.
 *
 * [LayoutNodeChildReferenceReader] is what gives a node a reference to each of its children, and the
 * [OwnerRule]s of [OwnerReferences] are what make a node belong to its parent and a modifier to the chain
 * it is on.
 */
class ComposeUiReferencesTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a node of a Compose UI is held by its parent rather than by the window's registry`() {
    val ui = composeUiHeapDump()
    HeapExplorer.open(ui.file).use { explorer ->
      val tree = explorer.tree

      // AndroidComposeView keeps every node of a window in one map, by semantics id, so without the
      // ownership rule a screen's node tree is a list hanging off the view. The step down to a child names
      // the child's index on the parent's own class, the vector and the array it grows in being no part of
      // the way down — see [LayoutNodeChildReferenceReader].
      assertThat(tree.dominatorOf(ui.childNodeId)!!.nodeId).isEqualTo(ui.rootNodeId)
      assertThat(tree.independentPathsBelowDominator(ui.childNodeId).paths.map { it.stepLabels() })
        .containsExactly(listOf("0 → LayoutNode"))
      // And the node at the top of the window belongs to the view hosting it, which is what puts a Compose
      // UI's bytes inside the hierarchy of the screen showing it.
      assertThat(tree.dominatorOf(ui.rootNodeId)!!.label).isEqualTo("AndroidComposeView")
    }
  }

  @Test fun `a node in a slot its parent doesn't count is not one of its children`() {
    val ui = composeUiHeapDump()
    HeapExplorer.open(ui.file).use { explorer ->
      val tree = explorer.tree

      // What a parent holds is what the vector's size covers, not what its array has room for: a slot past
      // the size is a node the parent has let go of, and calling it a child would attribute a leaked piece
      // of UI to the UI that dropped it. So nothing owns this one, and the array pointing at it is how it
      // is held after all — parked until nothing that owns it turned up, then counted, since dropping the
      // rival outright would have made a reachable node read as garbage.
      assertThat(tree.dominatorOf(ui.uncountedNodeId)!!.label).isEqualTo("LayoutNode[]")
    }
  }

  @Test fun `a modifier is held by the chain of the node it is on`() {
    val ui = composeUiHeapDump()
    HeapExplorer.open(ui.file).use { explorer ->
      val tree = explorer.tree
      val painter = tree.findByLabel("PainterNode")

      // Compose's modifier nodes point back at their coordinators and at each other, so one reference into
      // any of them — here the focus listener a window's ViewTreeObserver holds — would otherwise hold the
      // lot from a GC root of its own. The chain owns them instead, outermost first through each `child`.
      assertThat(tree.dominatorOf(painter.objectId)!!.label).isEqualTo("SizeNode")
      assertThat(tree.independentPathsBelowDominator(painter.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("child → PainterNode"))
      assertThat(tree.dominatorOf(tree.findByLabel("SizeNode").objectId)!!.label).isEqualTo("NodeChain")
      // And what the modifier draws is under the modifier, so it is under the node drawing it.
      val painted = tree.findByLabel(PAINTED_BY_A_MODIFIER)
      assertThat(tree.dominatorOf(painted.objectId)!!.nodeId).isEqualTo(painter.objectId)
    }
  }

  @Test fun `the tree still retains every byte of a Compose UI`() {
    val ui = composeUiHeapDump()
    HeapExplorer.open(ui.file).use { explorer ->
      // Rules that park a reference are a way of taking edges out of the graph the tree is built from.
      // This is what says they take no object out of it: every one of them still hangs somewhere under the
      // root, and nothing reads as garbage.
      assertThat(explorer.tree.weight(explorer.tree.root)).isEqualTo(explorer.sizes.totalByteCount)
      assertThat(explorer.sizes.reachableByteCount + explorer.sizes.unreachableByteCount)
        .isEqualTo(explorer.sizes.totalByteCount)
      assertThat(explorer.sizes.unreachableByteCount).isZero()
    }
  }

  /**
   * A heap dump shaped like a window of a Compose app: a view holding the node at the top of its UI and a
   * registry of every node in it, a node tree two deep, and a chain of two modifiers on the lower node.
   *
   * The rivals a real dump has are here too, each a GC root of its own so that all of them are closer to a
   * root than the view is: the registry of every node, a focus listener pointing into the modifier chain,
   * and a node left in its parent's array after the parent stopped counting it.
   */
  @Suppress("LongMethod")
  private fun composeUiHeapDump(): ComposeUi {
    val file = testFolder.newFile("compose-ui.hprof")
    var ids: ComposeUi? = null
    file.dump {
      val objectArrayClassId = arrayClass("java.lang.Object")
      val layoutNodeArrayClassId = arrayClass("androidx.compose.ui.node.LayoutNode")

      /** An instance of a class of its own, so that the tests can name it, holding bytes worth moving. */
      fun payload(simpleClassName: String) = instance(
        clazz(
          className = "com.example.$simpleClassName",
          fields = listOf("payload" to ReferenceHolder::class)
        ),
        listOf(ReferenceHolder(objectArray(objectArrayClassId, LongArray(PAYLOAD_ELEMENT_COUNT))))
      )

      val vectorClassId = clazz(
        className = "androidx.compose.runtime.collection.MutableVector",
        fields = listOf(
          "content" to ReferenceHolder::class,
          "size" to IntHolder::class
        )
      )
      val trackedVectorClassId = clazz(
        className = "androidx.compose.ui.node.MutableVectorWithMutationTracking",
        fields = listOf("vector" to ReferenceHolder::class)
      )

      /**
       * Children the way a node keeps them: a vector with a mutation callback around it, over an array it
       * grows in powers of two, with only the first [childCount] of the slots in use.
       */
      fun children(
        vararg children: ReferenceHolder,
        childCount: Int = children.size
      ): ReferenceHolder {
        val content = LongArray(CHILD_ARRAY_CAPACITY) { index ->
          children.getOrNull(index)?.value ?: ValueHolder.NULL_REFERENCE
        }
        val vector = instance(
          vectorClassId,
          listOf(ReferenceHolder(objectArray(layoutNodeArrayClassId, content)), IntHolder(childCount))
        )
        return instance(trackedVectorClassId, listOf(vector))
      }

      val viewClassId = clazz(
        className = "android.view.View",
        fields = listOf(
          "mParent" to ReferenceHolder::class,
          "mWindowAttachCount" to IntHolder::class,
          "mAttachInfo" to ReferenceHolder::class,
          "mContext" to ReferenceHolder::class
        )
      )
      val layoutNodeClassId = clazz(
        className = "androidx.compose.ui.node.LayoutNode",
        fields = listOf(
          "_foldedChildren" to ReferenceHolder::class,
          "nodes" to ReferenceHolder::class
        )
      )
      val modifierNodeClassId = clazz(
        className = "androidx.compose.ui.Modifier\$Node",
        fields = listOf(
          "parent" to ReferenceHolder::class,
          "child" to ReferenceHolder::class
        )
      )
      // The chain points back at the node it is on and the nodes at each other, so their ids have to exist
      // before the objects holding them are written.
      val rootNode = reserveObjectId()
      val childNode = reserveObjectId()
      val painterNode = reserveObjectId()

      // What the lower node draws with, and the value it paints: a chain of two modifiers, the outer one
      // held by the chain and the inner one by the outer one's `child`.
      val painted = payload(PAINTED_BY_A_MODIFIER)
      instance(
        clazz(
          className = "androidx.compose.ui.draw.PainterNode",
          superclassId = modifierNodeClassId,
          fields = listOf("painter" to ReferenceHolder::class)
        ),
        listOf(painted, NO_REFERENCE, NO_REFERENCE),
        objectId = painterNode
      )
      val sizeNode = instance(
        clazz(
          className = "androidx.compose.foundation.layout.SizeNode",
          superclassId = modifierNodeClassId
        ),
        listOf(NO_REFERENCE, painterNode)
      )
      val nodeChain = "androidx.compose.ui.node.NodeChain" instance {
        field["head"] = sizeNode
        field["tail"] = painterNode
        field["layoutNode"] = childNode
      }

      // A node tree two deep, plus a node left in the parent's array past the size it counts.
      instance(layoutNodeClassId, listOf(children(), nodeChain), objectId = childNode)
      val uncountedNode = instance(layoutNodeClassId, listOf(children(), NO_REFERENCE))
      instance(
        layoutNodeClassId,
        listOf(children(childNode, uncountedNode, childCount = 1), NO_REFERENCE),
        objectId = rootNode
      )

      val composeView = instance(
        clazz(
          className = "androidx.compose.ui.platform.AndroidComposeView",
          superclassId = viewClassId,
          fields = listOf(
            "root" to ReferenceHolder::class,
            "layoutNodes" to ReferenceHolder::class
          )
        ),
        listOf(
          rootNode,
          // The registry of every node of the window, which is what the ownership rule beats.
          ReferenceHolder(
            objectArray(objectArrayClassId, longArrayOf(rootNode.value, childNode.value))
          ),
          // Then what android.view.View declares: mParent, mWindowAttachCount, mAttachInfo, mContext.
          // The context is there because the object inspectors read it with `!!`, and a view without one
          // fails to summarise with a bare NullPointerException out of shark-android.
          NO_REFERENCE,
          IntHolder(1),
          NO_REFERENCE,
          instance(
            clazz(
              className = "com.example.MainActivity",
              superclassId = clazz(
                className = "android.app.Activity",
                fields = listOf("mDestroyed" to BooleanHolder::class)
              )
            ),
            listOf(BooleanHolder(false))
          )
        )
      )
      // The reference into a modifier chain a real dump has: a listener registered on the window's
      // ViewTreeObserver, which an input method manager reaches from a root of its own.
      val viewTreeObserver = "android.view.ViewTreeObserver" instance {
        field["mOnGlobalFocusListeners"] = painterNode
      }
      gcRoot(JniGlobal(id = composeView.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = viewTreeObserver.value, jniGlobalRefId = 1))
      ids = ComposeUi(
        file = file,
        rootNodeId = rootNode.value,
        childNodeId = childNode.value,
        uncountedNodeId = uncountedNode.value
      )
    }
    return ids!!
  }

  /**
   * The objects of [composeUiHeapDump] the tests name by id, every `LayoutNode` of a dump reading the same
   * as the next.
   */
  private class ComposeUi(
    val file: File,
    val rootNodeId: Long,
    val childNodeId: Long,
    val uncountedNodeId: Long
  )

  companion object {
    /** Larger than the number of children in use, the way a vector grows. */
    private const val CHILD_ARRAY_CAPACITY = 4

    private const val PAYLOAD_ELEMENT_COUNT = 1024

    private const val PAINTED_BY_A_MODIFIER = "PaintedByAModifier"

    /** A field holding nothing, which is what the chain of a node with no modifiers is. */
    private val NO_REFERENCE = ReferenceHolder(ValueHolder.NULL_REFERENCE)
  }
}
