package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.ByteSize.Companion.bytes
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

    assertThat(secondTraversal).isInstanceOf(HeapGrowthTraversal::class.java)
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
  fun `object growth computes retained size increase with 2 iterations`() {
    val detector = ObjectGrowthDetector.forJvmHeap().listRepeatingHeapGraph()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hello")
      },
      dump {
        classWithStringsInStaticField("Hello", "World!")
      }
    )

    val heapTraversal =  detector.findRepeatedlyGrowingObjects(dumps)

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.retainedIncrease.objectCount).isEqualTo(1)
    val expectedRetainedSizeIncrease = (12 + "World!".length * 2).bytes
    assertThat(growingObject.retainedIncrease.heapSize).isEqualTo(expectedRetainedSizeIncrease)
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

    val heapTraversal =  detector.findRepeatedlyGrowingObjects(dumps)

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

  class ListRepeatingHeapGraphObjectGrowthDetector(
    objectGrowthDetector: ObjectGrowthDetector
  ) {
    private val delegate = objectGrowthDetector.repeatingHeapGraph()

    fun findRepeatedlyGrowingObjects(
      heapGraphs: List<CloseableHeapGraph>,
      scenarioLoopsPerGraph: Int = InitialState.DEFAULT_SCENARIO_LOOPS_PER_GRAPH,
    ): HeapGrowthTraversal {
      return delegate.findRepeatedlyGrowingObjects(
        initialState = InitialState(
          scenarioLoopsPerGraph = scenarioLoopsPerGraph,
          heapGraphCount = heapGraphs.size
        ),
        heapGraphSequence = heapGraphs.asSequence()
      ).apply {
        check(traversalCount == heapGraphs.size) {
          "Expected traversalCount $traversalCount to be equal to heapGraphs size ${heapGraphs.size} for $this"
        }
      }
    }
  }

  private fun ObjectGrowthDetector.listRepeatingHeapGraph(): ListRepeatingHeapGraphObjectGrowthDetector =
    ListRepeatingHeapGraphObjectGrowthDetector(this)

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
