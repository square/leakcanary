package shark.dive

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
import shark.dive.ReachabilityStrength.STRONG

/**
 * How a Compose UI reads: the values a composition remembers, attributed to the node whose composable
 * remembered them, and the node tree they hang off.
 *
 * Two mechanisms are under test. [SlotTableReferenceReader] hands the elements of a composition's one flat
 * array out from the groups holding them, and the [OwnerRule]s of [OwnerReferences] make a node belong to
 * its parent and a modifier to the chain it is on.
 */
class ComposeUiReferencesTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `what a composable remembers is held by the node it ran in`() {
    val ui = composeUiHeapDump()
    HeapDive.open(ui.file).use { dive ->
      val tree = dive.tree
      val remembered = tree.findByLabel(REMEMBERED_BY_THE_NODE)

      // The array all of it really lives in is under the composition, nowhere near the UI, so this is the
      // whole point of reading a slot as its group's: the value is drawn under the node that remembers it,
      // and the step down to it says which slot of the table it is in.
      assertThat(remembered.strength).isEqualTo(STRONG)
      assertThat(tree.dominatorOf(remembered.objectId)!!.nodeId).isEqualTo(ui.childNodeId)
      assertThat(tree.independentPathsBelowDominator(remembered.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("slot 4 → $REMEMBERED_BY_THE_NODE"))
      // And a group's slots stop where the group it contains begins, so what the node above remembers is
      // the node above's rather than everything below it.
      val rememberedHigherUp = tree.findByLabel(REMEMBERED_BY_THE_ROOT_NODE)
      assertThat(tree.dominatorOf(rememberedHigherUp.objectId)!!.nodeId).isEqualTo(ui.rootNodeId)
    }
  }

  @Test fun `a value a composable remembers and its modifier holds is held by the node once`() {
    val ui = composeUiHeapDump()
    HeapDive.open(ui.file).use { dive ->
      val tree = dive.tree
      val painted = tree.findByLabel(PAINTED_BY_A_MODIFIER)

      // Two ways to it, a slot of the node's group and the modifier the node draws with, and both of them
      // start at the node — which is what puts an image a composable both remembers and draws under that
      // composable rather than above everything that reaches into Compose.
      assertThat(tree.dominatorOf(painted.objectId)!!.nodeId).isEqualTo(ui.childNodeId)
      assertThat(tree.independentPathsBelowDominator(painted.objectId).paths.map { it.stepLabels() })
        .containsExactlyInAnyOrder(
          listOf("slot 5 → $PAINTED_BY_A_MODIFIER"),
          listOf(
            "nodes → NodeChain",
            "head → SizeNode",
            "child → PainterNode",
            "painter → $PAINTED_BY_A_MODIFIER"
          )
        )
    }
  }

  @Test fun `a composition's array of slots holds nothing of its own`() {
    val ui = composeUiHeapDump()
    HeapDive.open(ui.file).use { dive ->
      val tree = dive.tree

      // Still a node of the tree, reached through the table's own field and holding its own bytes — the
      // dive needs every object of a heap dump to be a node exactly once — and retaining nothing of
      // what it points at, every element of it having been handed out from the group it belongs to.
      assertThat(tree.dominatorOf(ui.slotArrayId)!!.nodeId).isEqualTo(ui.slotTableId)
      assertThat(tree.descendantsOf(ui.slotArrayId)).isEmpty()
    }
  }

  @Test fun `a slot no node of the composition is under is the composition's own`() {
    val ui = composeUiHeapDump()
    HeapDive.open(ui.file).use { dive ->
      val tree = dive.tree
      val aboveTheFirstNode = tree.findByLabel(REMEMBERED_BY_THE_TABLE)
      val leftInTheGap = tree.findByLabel(LEFT_IN_THE_GAP)

      // Two of them: a group that emitted no node has no piece of UI to belong to, and the array is longer
      // than the slots in use, its tail being the gap the next write is made through — a reference left in
      // there is the composition's too.
      assertThat(tree.dominatorOf(aboveTheFirstNode.objectId)!!.nodeId).isEqualTo(ui.slotTableId)
      assertThat(tree.dominatorOf(leftInTheGap.objectId)!!.nodeId).isEqualTo(ui.slotTableId)
      assertThat(tree.independentPathsBelowDominator(leftInTheGap.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("slot 6 → $LEFT_IN_THE_GAP"))
    }
  }

  @Test fun `a slot table this can't read keeps its own references`() {
    val ui = composeUiHeapDump()
    HeapDive.open(ui.file).use { dive ->
      val tree = dive.tree
      val chunked = tree.findByLabel(HELD_BY_A_TABLE_OF_ANOTHER_SHAPE)

      // Compose ships a second implementation whose slots are chunks rather than one array, and a table
      // whose shape this can't read has to keep every reference out of it: the alternative is an object
      // reachable through nothing, which would read as uncollected garbage. So it reads the way it did
      // before [SlotTableReferenceReader] existed, through the array itself.
      assertThat(chunked.strength).isEqualTo(STRONG)
      val chunk = tree.dominatorOf(chunked.objectId)!!
      assertThat(chunk.label).isEqualTo("Object[]")
      assertThat(tree.dominatorOf(chunk.nodeId)!!.nodeId).isEqualTo(ui.chunkedTableId)
    }
  }

  @Test fun `a node of a Compose UI is held by its parent rather than by the window's registry`() {
    val ui = composeUiHeapDump()
    HeapDive.open(ui.file).use { dive ->
      val tree = dive.tree

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
    HeapDive.open(ui.file).use { dive ->
      val tree = dive.tree

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
    HeapDive.open(ui.file).use { dive ->
      val tree = dive.tree
      val painter = tree.findByLabel("PainterNode")

      // Compose's modifier nodes point back at their coordinators and at each other, so one reference into
      // any of them — here the focus listener a window's ViewTreeObserver holds — would otherwise hold the
      // lot from a GC root of its own. The chain owns them instead, outermost first through each `child`.
      assertThat(tree.dominatorOf(painter.objectId)!!.label).isEqualTo("SizeNode")
      assertThat(tree.independentPathsBelowDominator(painter.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("child → PainterNode"))
      assertThat(tree.dominatorOf(tree.findByLabel("SizeNode").objectId)!!.label).isEqualTo("NodeChain")
    }
  }

  @Test fun `the tree still retains every byte of a Compose UI`() {
    val ui = composeUiHeapDump()
    HeapDive.open(ui.file).use { dive ->
      // A reader that moves an array's references and rules that park them are both ways of taking edges
      // out of the graph the tree is built from. This is what says they take no object out of it: every one
      // of them still hangs somewhere under the root, and nothing reads as garbage.
      assertThat(dive.tree.weight(dive.tree.root)).isEqualTo(dive.sizes.totalByteCount)
      assertThat(dive.sizes.reachableByteCount + dive.sizes.unreachableByteCount)
        .isEqualTo(dive.sizes.totalByteCount)
      assertThat(dive.sizes.unreachableByteCount).isZero()
    }
  }

  /**
   * A heap dump shaped like a window of a Compose app: a view holding the node at the top of its UI and a
   * registry of every node in it, a node tree two deep, a chain of two modifiers on the lower node, and a
   * composition whose slot table remembers a value against each of them.
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

      // What the composition remembers: a value against the group of each node, one above the first node
      // of it, and one left in the gap past the slots in use.
      val rememberedByTheTable = payload(REMEMBERED_BY_THE_TABLE)
      val rememberedByTheRootNode = payload(REMEMBERED_BY_THE_ROOT_NODE)
      val rememberedByTheNode = payload(REMEMBERED_BY_THE_NODE)
      val leftInTheGap = payload(LEFT_IN_THE_GAP)
      val slotArrayId = objectArray(
        objectArrayClassId,
        longArrayOf(
          // Group 0, which emitted no node: its own slot, and so the composition's.
          rememberedByTheTable.value,
          // Group 1, a node group: the node in its first slot, then what it remembers.
          rootNode.value,
          rememberedByTheRootNode.value,
          // Group 2, the node group inside it, the same way, and a value its modifier holds too.
          childNode.value,
          rememberedByTheNode.value,
          painted.value,
          // Past the slots in use: the gap, and a reference the last write left behind in it.
          leftInTheGap.value,
          ValueHolder.NULL_REFERENCE
        )
      )
      val slotTable = "androidx.compose.runtime.composer.gapbuffer.SlotTable" instance {
        // Five ints a group: the key it was composed under, its flags, where its parent is, how many
        // groups it contains, and where its slots start.
        field["groups"] = ReferenceHolder(
          primitiveIntArray(
            intArrayOf(
              100, 0, -1, 3, 0,
              101, NODE_GROUP_FLAG, 0, 2, 1,
              102, NODE_GROUP_FLAG, 1, 1, 3
            )
          )
        )
        field["groupsSize"] = IntHolder(3)
        field["slots"] = ReferenceHolder(slotArrayId)
        field["slotsSize"] = IntHolder(6)
        field["writer"] = BooleanHolder(false)
      }
      // Compose's other implementation, whose slots are chunks: a table this can't decode, and so one that
      // has to be left holding its own references.
      val chunkedTable = "androidx.compose.runtime.composer.linkbuffer.SlotTable" instance {
        field["chunks"] = ReferenceHolder(
          objectArray(
            objectArrayClassId,
            longArrayOf(payload(HELD_BY_A_TABLE_OF_ANOTHER_SHAPE).value)
          )
        )
      }
      val composition = "androidx.compose.runtime.CompositionImpl" instance {
        field["slotStorage"] = slotTable
      }
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
      gcRoot(JniGlobal(id = composition.value, jniGlobalRefId = 1))
      gcRoot(JniGlobal(id = chunkedTable.value, jniGlobalRefId = 2))
      gcRoot(JniGlobal(id = viewTreeObserver.value, jniGlobalRefId = 3))
      ids = ComposeUi(
        file = file,
        rootNodeId = rootNode.value,
        childNodeId = childNode.value,
        uncountedNodeId = uncountedNode.value,
        slotTableId = slotTable.value,
        slotArrayId = slotArrayId,
        chunkedTableId = chunkedTable.value
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
    val uncountedNodeId: Long,
    val slotTableId: Long,
    val slotArrayId: Long,
    val chunkedTableId: Long
  )

  companion object {
    /** Bit 30 of a group's flags, which is how Compose says the group emitted a node. */
    private const val NODE_GROUP_FLAG = 1 shl 30

    /** Larger than the number of children in use, the way a vector grows. */
    private const val CHILD_ARRAY_CAPACITY = 4

    private const val PAYLOAD_ELEMENT_COUNT = 1024

    private const val REMEMBERED_BY_THE_TABLE = "RememberedByTheTable"

    private const val REMEMBERED_BY_THE_ROOT_NODE = "RememberedByTheRootNode"

    private const val REMEMBERED_BY_THE_NODE = "RememberedByTheNode"

    private const val PAINTED_BY_A_MODIFIER = "PaintedByAModifier"

    private const val LEFT_IN_THE_GAP = "LeftInTheGap"

    private const val HELD_BY_A_TABLE_OF_ANOTHER_SHAPE = "HeldByATableOfAnotherShape"

    /** A field holding nothing, which is what the chain of a node with no modifiers is. */
    private val NO_REFERENCE = ReferenceHolder(ValueHolder.NULL_REFERENCE)
  }
}
