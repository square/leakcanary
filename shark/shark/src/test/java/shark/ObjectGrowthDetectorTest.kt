package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.Companion.NULL_REFERENCE

class ObjectGrowthDetectorTest {

  @Test
  fun `first traversal returns FirstHeapTraversal`() {
    val detector = ObjectGrowthDetector.forJvmHeap()

    val firstTraversal = detector.findGrowingObjects(
      heapGraph = dump {
      },
      previousTraversal = InitialState(scenarioLoopsPerGraph = 1),
    )

    assertThat(firstTraversal).isInstanceOf(FirstHeapTraversal::class.java)
  }

  @Test
  fun `second traversal returns HeapTraversalWithDiff`() {
    val detector = ObjectGrowthDetector.forJvmHeap()
    val first = detector.findGrowingObjects(
      heapGraph = emptyHeapDump(),
      previousTraversal = InitialState(scenarioLoopsPerGraph = 1),
    )

    val secondTraversal = detector.findGrowingObjects(
      heapGraph = emptyHeapDump(),
      previousTraversal = first,
    )

    assertThat(secondTraversal).isInstanceOf(HeapDiff::class.java)
  }

  @Test
  fun `detect no growth on identical heaps`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hi")
      },
      dump {
        classWithStringsInStaticField("Hi")
      }
    )

    val growingObjects = detector.findRepeatedlyGrowingObjects(dumps).growingObjects

    assertThat(growingObjects).isEmpty()
  }

  @Test
  fun `detect no growth on structurally identical heap`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hi")
      },
      dump {
        classWithStringsInStaticField("Bonjour")
      }
    )

    val growingObjects = detector.findRepeatedlyGrowingObjects(dumps).growingObjects

    assertThat(growingObjects).isEmpty()
  }

  @Test
  fun `detect static field growth`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hello")
      },
      dump {
        classWithStringsInStaticField("Hello", "World!")
      }
    )

    val growingObjects = detector.findRepeatedlyGrowingObjects(dumps).growingObjects

    assertThat(growingObjects).hasSize(1)
  }

  @Test
  fun `null list element does not fail the traversal`() {
    val detector = ObjectGrowthDetector.forJvmHeap()

    val traversal = detector.findGrowingObjects(
      heapGraph = dump {
        linkedListInStaticField(nullReference(), string("Hello"))
      },
    )

    val listNode = traversal.findNode("instance of java.util.LinkedList")
    val listElements = listNode.children.map { it.name }.filter { it.startsWith("ARRAY_ENTRY") }
    assertThat(listElements)
      .containsExactly("ARRAY_ENTRY LinkedList.[x] -> instance of java.lang.String")
  }

  @Test
  fun `object growth computes retained size with 2 iterations`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hello")
      },
      dump {
        classWithStringsInStaticField("Hello", "World!")
      }
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(dumps)

    val growingObject = heapTraversal.growingObjects.single()
    // The string array and the 2 strings it holds.
    assertThat(growingObject.retained.objectCount).isEqualTo(3)
    val expectedRetainedSize = (2 * 4 + (8 + "Hello".length * 2) + (8 + "World!".length * 2)).bytes
    assertThat(growingObject.retained.heapSize).isEqualTo(expectedRetainedSize)
    // The first traversal has no growing objects to compute a retained size from, so there's
    // nothing to diff the retained size of the second traversal against.
    assertThat(growingObject.retainedIncrease).isEqualTo(ZERO_RETAINED)
  }

  @Test
  fun `retained size skips objects reachable without going through a growing object`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        arrayOfWrappersOfStablyHeldInstances(wrapperCount = 1)
      },
      dump {
        arrayOfWrappersOfStablyHeldInstances(wrapperCount = 2)
      }
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(dumps)

    val growingObject = heapTraversal.growingObjects.single()
    // The array and the 2 wrappers it holds. The 2 wrapped instances stay reachable through the
    // static field that holds them directly, so releasing the array wouldn't free them.
    assertThat(growingObject.retained.objectCount).isEqualTo(3)
    val arraySize = 2 * 4
    val wrappersSize = 2 * 4
    assertThat(growingObject.retained.heapSize).isEqualTo((arraySize + wrappersSize).bytes)
  }

  @Test
  fun `retained size of shared objects is split between growing objects`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        twoArraysOfWrappersSharingInstances(sharedInstanceCount = 1)
      },
      dump {
        twoArraysOfWrappersSharingInstances(sharedInstanceCount = 2)
      }
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(dumps)

    assertThat(heapTraversal.growingObjects).hasSize(2)
    heapTraversal.growingObjects.forEach { growingObject ->
      // The array, the 2 wrappers it holds and half of the 2 shared instances.
      assertThat(growingObject.retained.objectCount).isEqualTo(4)
      val arraySize = 2 * 4
      val wrappersSize = 2 * 4
      val halfOfSharedSize = 2 * 4 / 2
      assertThat(growingObject.retained.heapSize)
        .isEqualTo((arraySize + wrappersSize + halfOfSharedSize).bytes)
    }
  }

  @Test
  fun `object growth computes retained size increase with 3 iterations`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hello")
      },
      dump {
        classWithStringsInStaticField("Hello", "World!")
      },
      dump {
        classWithStringsInStaticField("Hello", "World!", "Turtles")
      }
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(dumps)

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.retainedIncrease.objectCount).isEqualTo(1)
    val expectedRetainedSizeIncrease = (12 + "Turtles".length * 2).bytes
    assertThat(growingObject.retainedIncrease.heapSize).isEqualTo(expectedRetainedSizeIncrease)
  }

  @Test
  fun `detect growth of custom linked list`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        val customLinkedListClass = clazz(
          className = "CustomLinkedList",
          fields = listOf("next" to ValueHolder.ReferenceHolder::class),
        )
        val linkedListTail = instance(customLinkedListClass, listOf(nullReference()))
        val linkedListHead = instance(customLinkedListClass, listOf(linkedListTail))
        clazz(
          className = "ListHolder",
          staticFields = listOf("staticList" to linkedListHead)
        )
      },
      dump {
        val customLinkedListClass = clazz(
          className = "CustomLinkedList",
          fields = listOf("next" to ValueHolder.ReferenceHolder::class),
        )
        val linkedListTail = instance(customLinkedListClass, listOf(nullReference()))
        val linkedListMiddle = instance(customLinkedListClass, listOf(linkedListTail))
        val linkedListHead = instance(customLinkedListClass, listOf(linkedListMiddle))
        clazz(
          className = "ListHolder",
          staticFields = listOf("staticList" to linkedListHead)
        )
      }
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(dumps)

    assertThat(heapTraversal.growingObjects).hasSize(1)
  }

  @Test
  fun `custom leaky linked list reports descendant to root as flattened collection`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        val customLinkedListClass = clazz(
          className = "CustomLinkedList",
          fields = listOf("next" to ValueHolder.ReferenceHolder::class),
        )
        val linkedListTail = instance(customLinkedListClass, listOf(nullReference()))
        val linkedListHead = instance(customLinkedListClass, listOf(linkedListTail))
        clazz(
          className = "ListHolder",
          staticFields = listOf("staticList" to linkedListHead)
        )
      },
      dump {
        val customLinkedListClass = clazz(
          className = "CustomLinkedList",
          fields = listOf("next" to ValueHolder.ReferenceHolder::class),
        )
        val linkedListTail = instance(customLinkedListClass, listOf(nullReference()))
        val linkedListMiddle1 = instance(customLinkedListClass, listOf(linkedListTail))
        val linkedListMiddle2 = instance(customLinkedListClass, listOf(linkedListMiddle1))
        val linkedListMiddle3 = instance(customLinkedListClass, listOf(linkedListMiddle2))
        val linkedListMiddle4 = instance(customLinkedListClass, listOf(linkedListMiddle3))
        val linkedListHead = instance(customLinkedListClass, listOf(linkedListMiddle4))
        clazz(
          className = "ListHolder",
          staticFields = listOf("staticList" to linkedListHead)
        )
      }
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(dumps)

    val growingObject = heapTraversal.growingObjects.single()
    val growingChild = growingObject.growingChildren.single()
    assertThat(growingChild.objectCountIncrease).isEqualTo(4)
  }

  @Test
  fun `detect no growth if more loops than node increase`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hello")
      },
      dump {
        classWithStringsInStaticField("Hello", "World!")
      }
    )

    val growingObjects = detector.findRepeatedlyGrowingObjects(
      heapGraphs = dumps,
      scenarioLoopsPerGraph = 2
    ).growingObjects

    assertThat(growingObjects).isEmpty()
  }

  @Test
  fun `detect static field growth counts`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()

    val heapDumpCount = 3
    val scenarioLoopCount = 7

    val dumps = (1..heapDumpCount).toList().map { heapDumpIndex ->
      val stringCount = heapDumpIndex * scenarioLoopCount
      val strings = (1..stringCount).toList().map { "Hi $it" }.toTypedArray()
      dump {
        classWithStringsInStaticField(*strings)
      }
    }

    val growingObjects = detector.findRepeatedlyGrowingObjects(
      heapGraphs = dumps,
      scenarioLoopsPerGraph = scenarioLoopCount
    ).growingObjects

    val growingNode = growingObjects.first()

    assertThat(growingNode.selfObjectCount).isEqualTo(1)
    assertThat(growingNode.children.sumOf { it.selfObjectCount }).isEqualTo(
      heapDumpCount * scenarioLoopCount
    )
    val growingChildren = growingNode.growingChildren
    assertThat(growingChildren).hasSize(1)
    assertThat(growingChildren.first().objectCountIncrease).isEqualTo(scenarioLoopCount)
    assertThat(growingNode.children).hasSize(1)
  }

  @Test
  fun `no heap growth when node with no children grows`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()

    val dumps = listOf(
      dump {
        clazz("SomeClass")
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "strings" to objectArray(),
          )
        )
      },
      dump {
        clazz("SomeClass")
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "strings" to objectArray(
              string("Hello 1"),
              string("Hello 2")
            ),
          )
        )
      },
    )
    val growingObjects = detector.findRepeatedlyGrowingObjects(
      heapGraphs = dumps,
      scenarioLoopsPerGraph = 2
    ).growingObjects
    assertThat(growingObjects).isEmpty()
  }

  @Test
  fun `detect heap growth when node with existing children grows`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()

    val dumps = listOf(
      dump {
        clazz("SomeClass")
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "strings" to objectArray(
              string("Hello 1"),
              string("Hello 2")
            ),
          )
        )
      },
      dump {
        clazz("SomeClass")
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "strings" to objectArray(
              string("Hello 1"),
              string("Hello 2"),
              string("Hello 3"),
              string("Hello 4"),
            ),
          )
        )
      },
    )
    val growingObjects = detector.findRepeatedlyGrowingObjects(
      heapGraphs = dumps,
      scenarioLoopsPerGraph = 2
    ).growingObjects
    assertThat(growingObjects).hasSize(1)
  }

  @Test
  fun `detect no growth if sum of children over threshold but individual children under threshold`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()

    val dumps = listOf(
      dump {
        clazz(
          "ClassWithStatics",
          staticFields = listOf("strings1" to objectArray(string("Hello")))
        )
      },
      dump {
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "strings1" to objectArray(string("Hello")),
            "strings2" to objectArray(string("World")),
            "strings3" to objectArray(string("!")),
          )
        )
      }
    )
    val growingObjects = detector.findRepeatedlyGrowingObjects(
      heapGraphs = dumps,
      scenarioLoopsPerGraph = 2
    ).growingObjects
    assertThat(growingObjects).isEmpty()
  }

  @Test
  fun `detect no growth if different individual children over threshold`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        val otherType = clazz("SomeClass")
        clazz("SomeClass")
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "list" to objectArray(
              string("Hello 1"),
              string("Hello 2"),
              instance(otherType),
              instance(otherType),
            ),
          )
        )
      },
      dump {
        val otherType = clazz("SomeClass")
        clazz("SomeClass")
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "list" to objectArray(
              string("Hello 1"),
              string("Hello 2"),
              string("Hello 3"),
              string("Hello 4"),
              instance(otherType),
              instance(otherType),
            ),
          )
        )
      },
      dump {
        val otherType = clazz("SomeClass")
        clazz("SomeClass")
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "list" to objectArray(
              string("Hello 1"),
              string("Hello 2"),
              string("Hello 3"),
              string("Hello 4"),
              instance(otherType),
              instance(otherType),
              instance(otherType),
              instance(otherType),
            ),
          )
        )
      },
    )

    val heapGrowthTraversal = detector.findRepeatedlyGrowingObjects(
      heapGraphs = dumps,
      scenarioLoopsPerGraph = 2
    )
    assertThat(heapGrowthTraversal.traversalCount).isEqualTo(dumps.size)
    val growingObjects = heapGrowthTraversal.growingObjects
    assertThat(growingObjects).isEmpty()
  }

  @Test
  fun `growth along shared sub paths reported as single growth of shortest path`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        val pairClass = clazz(
          "Pair", fields = listOf(
          "first" to ValueHolder.ReferenceHolder::class,
          "second" to ValueHolder.ReferenceHolder::class,
        )
        )
        val growingClass = clazz(
          "GrowingClass", fields = listOf("growingField" to ValueHolder.ReferenceHolder::class)
        )
        val pair = instance(pairClass, listOf(instance(objectClassId), instance(objectClassId)))
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "list" to objectArray(
              instance(growingClass, listOf(pair)),
            ),
          )
        )
      },
      dump {
        val pairClass = clazz(
          "Pair", fields = listOf(
          "first" to ValueHolder.ReferenceHolder::class,
          "second" to ValueHolder.ReferenceHolder::class,
        )
        )
        val growingClass = clazz(
          "GrowingClass", fields = listOf("growingField" to ValueHolder.ReferenceHolder::class)
        )
        val pair1 = instance(pairClass, listOf(instance(objectClassId), instance(objectClassId)))
        val pair2 = instance(pairClass, listOf(instance(objectClassId), instance(objectClassId)))
        clazz(
          "ClassWithStatics",
          staticFields = listOf(
            "list" to objectArray(
              instance(growingClass, listOf(pair1)),
              instance(growingClass, listOf(pair2)),
            ),
          )
        )
      },
    )

    val heapGrowthTraversal = detector.findRepeatedlyGrowingObjects(dumps)

    val growingObject = heapGrowthTraversal.growingObjects.single()
    assertThat(growingObject.name).startsWith("STATIC_FIELD ClassWithStatics.list")
  }

  class ListRepeatingHeapGraphObjectGrowthDetector(
    private val objectGrowthDetector: ObjectGrowthDetector
  ) {

    fun findRepeatedlyGrowingObjects(
      heapGraphs: List<HeapGraph>,
      scenarioLoopsPerGraph: Int = InitialState.DEFAULT_SCENARIO_LOOPS_PER_GRAPH,
    ): HeapDiff {
      var previousTraversal: HeapTraversalInput = InitialState(scenarioLoopsPerGraph)
      for (heapGraph in heapGraphs) {
        previousTraversal = objectGrowthDetector.findGrowingObjects(heapGraph, previousTraversal)
        if (previousTraversal is HeapDiff && !previousTraversal.isGrowing) {
          check(previousTraversal.traversalCount == heapGraphs.size) {
            "Expected to go through all ${heapGraphs.size} heap dumps, stopped at ${previousTraversal.traversalCount}"
          }
        }
      }
      return previousTraversal as HeapDiff
    }
  }

  private fun ObjectGrowthDetector.listRepeatingHeapGraph(): ListRepeatingHeapGraphObjectGrowthDetector =
    ListRepeatingHeapGraphObjectGrowthDetector(this)

  /**
   * A `java.util.LinkedList` holding [items], shaped the way the OpenJDK implementation is so that
   * [OpenJdkInstanceRefReaders.LINKED_LIST] expands it.
   */
  private fun HprofWriterHelper.linkedListInStaticField(vararg items: ValueHolder.ReferenceHolder) {
    val nodeClassId = clazz(
      "java.util.LinkedList\$Node",
      fields = listOf(
        "item" to ValueHolder.ReferenceHolder::class,
        "next" to ValueHolder.ReferenceHolder::class,
      )
    )
    val linkedListClassId = clazz(
      "java.util.LinkedList",
      fields = listOf("first" to ValueHolder.ReferenceHolder::class)
    )
    val first = items.foldRight(nullReference()) { item, next ->
      instance(nodeClassId, listOf(item, next))
    }
    clazz(
      "ClassWithStatics",
      staticFields = listOf("list" to instance(linkedListClassId, listOf(first)))
    )
  }

  /** The single node of [HeapTraversalOutput.shortestPathTree] whose name ends with [nameSuffix]. */
  private fun HeapTraversalOutput.findNode(nameSuffix: String): ShortestPathObjectNode {
    val matching = mutableListOf<ShortestPathObjectNode>()
    fun visit(node: ShortestPathObjectNode) {
      if (node.name.endsWith(nameSuffix)) {
        matching += node
      }
      node.children.forEach { visit(it) }
    }
    visit(shortestPathTree)
    return matching.single()
  }

  /**
   * A static field holding an array of [wrapperCount] wrappers, each wrapping one of the 2
   * instances that another static field holds onto directly, so that those 2 instances stay
   * reachable without going through the growing array.
   */
  private fun HprofWriterHelper.arrayOfWrappersOfStablyHeldInstances(wrapperCount: Int) {
    val heldClassId = clazz("Held", fields = listOf("value" to ValueHolder.IntHolder::class))
    val wrapperClassId = clazz(
      "Wrapper",
      fields = listOf("held" to ValueHolder.ReferenceHolder::class)
    )
    val heldInstances = (1..2).map { value ->
      instance(heldClassId, listOf(ValueHolder.IntHolder(value)))
    }
    val wrappers = heldInstances.take(wrapperCount).map { instance(wrapperClassId, listOf(it)) }
    clazz(
      "ClassWithStatics",
      staticFields = listOf(
        "stable" to objectArray(*heldInstances.toTypedArray()),
        "growing" to objectArray(*wrappers.toTypedArray())
      )
    )
  }

  /**
   * Two static fields, each holding an array of [sharedInstanceCount] wrappers, where the wrapper
   * at a given index in one array and the wrapper at that index in the other array both reference
   * the same shared instance.
   */
  private fun HprofWriterHelper.twoArraysOfWrappersSharingInstances(sharedInstanceCount: Int) {
    val sharedClassId = clazz("Shared", fields = listOf("value" to ValueHolder.IntHolder::class))
    val wrapperClassId = clazz(
      "Wrapper",
      fields = listOf("shared" to ValueHolder.ReferenceHolder::class)
    )
    val sharedInstances = (1..sharedInstanceCount).map { value ->
      instance(sharedClassId, listOf(ValueHolder.IntHolder(value)))
    }
    val wrapperArray = {
      objectArray(*sharedInstances.map { instance(wrapperClassId, listOf(it)) }.toTypedArray())
    }
    clazz(
      "ClassWithStatics",
      staticFields = listOf("first" to wrapperArray(), "second" to wrapperArray())
    )
  }

  private fun HprofWriterHelper.classWithStringsInStaticField(vararg strings: String) {
    clazz(
      "ClassWithStatics",
      staticFields = listOf("strings" to objectArray(*strings.map { string(it) }.toTypedArray()))
    )
  }

  private fun emptyHeapDump() = dump {}

  private fun dump(
    block: HprofWriterHelper.() -> Unit
  ): CloseableHeapGraph {
    return dump(HprofHeader(), block).openHeapGraph()
  }

  private fun nullReference() = ValueHolder.ReferenceHolder(NULL_REFERENCE)
}
