package shark.dive

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.InternedString
import shark.GcRoot.JniGlobal
import shark.GcRoot.StickyClass
import shark.HprofWriterHelper
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.dive.HeapDominatorTreemap.Companion.UNREACHABLE_NODE_ID
import shark.dive.ReachabilityStrength.FINALIZER
import shark.dive.ReachabilityStrength.PHANTOM
import shark.dive.ReachabilityStrength.SOFT
import shark.dive.ReachabilityStrength.STRONG
import shark.dive.ReachabilityStrength.WEAK

class HeapReachabilityTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `an object a soft reference is the only path to is softly reachable`() {
    assertThat(strengthOfPayloadHeldBy { it.softId }).isEqualTo(SOFT)
  }

  @Test fun `an object a weak reference is the only path to is weakly reachable`() {
    assertThat(strengthOfPayloadHeldBy { it.weakId }).isEqualTo(WEAK)
  }

  @Test fun `an object a phantom reference is the only path to is phantom reachable`() {
    assertThat(strengthOfPayloadHeldBy { it.phantomId }).isEqualTo(PHANTOM)
  }

  @Test fun `an object waiting to be finalized is finalizer reachable`() {
    // FinalizerReference extends PhantomReference, so this only comes out as FINALIZER because the
    // class hierarchy is read subclass first.
    val strength = strengthOfPayload { classes, payload ->
      finalizerReference(classes, referent = payload)
    }

    assertThat(strength).isEqualTo(FINALIZER)
  }

  @Test fun `an object being finalized right now is finalizer reachable`() {
    val strength = strengthOfPayload { classes, payload ->
      // Android moves the referent into the zombie field while finalize() runs.
      finalizerReference(classes, zombie = payload)
    }

    assertThat(strength).isEqualTo(FINALIZER)
  }

  @Test fun `an object the finalizer list is the only holder of is finalizer reachable`() {
    val file = testFolder.newFile("finalizer-list.hprof")
    var payloadObjectId = 0L
    file.dump {
      // ART puts a FinalizerReference on the list FinalizerReference.head starts for every object whose
      // class overrides finalize(), and that list is the only thing holding it: the head through the
      // static, every entry after it through the one before. So an entry that isn't the head is only
      // reachable through `next`, and an object waiting to be finalized only through that entry.
      val headId = reserveObjectId()
      val classes = referenceClasses(finalizerListHead = headId)
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      payloadObjectId = payload.value
      val second = finalizerReference(classes, referent = payload)
      finalizerReference(classes, next = second, objectId = headId)
      gcRoot(StickyClass(id = classes.finalizerId))
    }

    HeapDive.open(file).use { dive ->
      val tree = dive.tree

      assertThat(tree.strengthOf(payloadObjectId)).isEqualTo(FINALIZER)
      assertThat(tree.dominatorOf(payloadObjectId)!!.label).isEqualTo("FinalizerReference")
      assertThat(dive.sizes.unreachableByteCount).isZero()
    }
  }

  @Test fun `an object the cleaner list is the only holder of is phantom reachable`() {
    val file = testFolder.newFile("cleaner-list.hprof")
    var payloadObjectId = 0L
    file.dump {
      // Same list, one class down: a Cleaner is a PhantomReference, so what only a Cleaner holds is
      // phantom reachable rather than waiting to be finalized.
      val firstId = reserveObjectId()
      val classes = referenceClasses()
      val cleanerClassId = cleanerClass(classes, firstCleaner = firstId)
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      payloadObjectId = payload.value
      val second = cleaner(cleanerClassId, referent = payload)
      cleaner(cleanerClassId, next = second, objectId = firstId)
      gcRoot(StickyClass(id = cleanerClassId))
    }

    HeapDive.open(file).use { dive ->
      val tree = dive.tree

      assertThat(tree.strengthOf(payloadObjectId)).isEqualTo(PHANTOM)
      assertThat(tree.dominatorOf(payloadObjectId)!!.label).isEqualTo("Cleaner")
      assertThat(dive.sizes.unreachableByteCount).isZero()
    }
  }

  @Test fun `a strong reference wins over a weak one`() {
    val strength = strengthOfPayload { classes, payload ->
      "com.example.Holder" instance {
        field["payload"] = payload
        field["weakly"] = reference(classes.weakId, payload)
      }
    }

    assertThat(strength).isEqualTo(STRONG)
  }

  @Test fun `a weak reference wins over a phantom one`() {
    // The example that makes the rule concrete: reachable through both, so it's weakly reachable.
    val strength = strengthOfPayload { classes, payload ->
      "com.example.Holder" instance {
        field["weakly"] = reference(classes.weakId, payload)
        field["phantomly"] = reference(classes.phantomId, payload)
      }
    }

    assertThat(strength).isEqualTo(WEAK)
  }

  @Test fun `a path is only as strong as its weakest reference`() {
    // root -> holder -> weak -> box -> payload: the payload is reached by strong references only,
    // but the path to it goes through a weak reference, so it goes when the box does.
    val strength = strengthOfPayload { classes, payload ->
      val box = "com.example.Box" instance { field["payload"] = payload }
      "com.example.Holder" instance { field["weakly"] = reference(classes.weakId, box) }
    }

    assertThat(strength).isEqualTo(WEAK)
  }

  @Test fun `an object nothing reaches is unreachable`() {
    val file = testFolder.newFile("unreachable.hprof")
    val payloadByteCount = PAYLOAD_ELEMENT_COUNT * ID_BYTE_SIZE
    file.dump {
      referenceClasses()
      // Written but never referenced and not a GC root, which is what garbage looks like in a dump.
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      val holder = "com.example.Holder" instance {
        field["name"] = string("Strongly reachable")
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }

    HeapDive.open(file).use { dive ->
      val sizes = dive.sizes

      // Exactly the array: a string's char array is not a node of the graph either, and it must not
      // be counted here, because its bytes are already counted inside the string.
      assertThat(sizes.unreachableByteCount).isEqualTo(payloadByteCount.toLong())
      assertThat(sizes.byteCountByStrength.getValue(WEAK)).isZero()
    }
  }

  @Test fun `the reachable and unreachable bytes add up to the whole heap dump`() {
    val file = testFolder.newFile("mixed.hprof")
    file.dump {
      val classes = referenceClasses()
      objectArray(arrayClass("java.lang.Object"), LongArray(32))
      val softly = ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(64)))
      val weakly = ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(128)))
      val holder = "com.example.Holder" instance {
        field["name"] = string("Strongly reachable")
        field["softly"] = reference(classes.softId, softly)
        field["weakly"] = reference(classes.weakId, weakly)
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }

    HeapDive.open(file).use { dive ->
      val sizes = dive.sizes

      assertThat(sizes.reachableByteCount + sizes.unreachableByteCount)
        .isEqualTo(sizes.totalByteCount)
      // What a retained size is shown as a share of: everything the GC roots reach, less what only a
      // soft or a weak reference points at, since a collection that needs the room can take those.
      assertThat(sizes.stronglyReachableByteCount)
        .isEqualTo(sizes.reachableByteCount - (64L + 128L) * ID_BYTE_SIZE)
      assertThat(sizes.byteCountByStrength.getValue(SOFT)).isEqualTo(64L * ID_BYTE_SIZE)
      assertThat(sizes.byteCountByStrength.getValue(WEAK)).isEqualTo(128L * ID_BYTE_SIZE)
      assertThat(sizes.unreachableByteCount).isEqualTo(32L * ID_BYTE_SIZE)
    }
  }

  @Test fun `the tree retains every byte of the heap dump, at every strength`() {
    val file = testFolder.newFile("weaker.hprof")
    file.dump {
      val classes = referenceClasses()
      val softly = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      val holder = "com.example.Holder" instance {
        field["softly"] = reference(classes.softId, softly)
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }

    HeapDive.open(file).use { dive ->
      // Nothing is left out of the tree: not what only a soft reference reaches, and not the garbage.
      assertThat(dive.tree.weight(dive.tree.root)).isEqualTo(dive.sizes.totalByteCount)
      assertThat(dive.sizes.byteCountByStrength.getValue(SOFT))
        .isEqualTo(PAYLOAD_ELEMENT_COUNT.toLong() * ID_BYTE_SIZE)
      assertThat(dive.sizes.unreachableByteCount)
        .isEqualTo(PAYLOAD_ELEMENT_COUNT.toLong() * ID_BYTE_SIZE)
    }
  }

  @Test fun `garbage is drawn beside the objects the GC roots hold`() {
    val file = testFolder.newFile("garbage.hprof")
    file.dump {
      referenceClasses()
      val garbage = objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      // Held by garbage, so the tree has to nest it under that rather than list it at the top.
      objectArray(arrayClass("java.lang.Object"), longArrayOf(garbage))
      val holder = "com.example.Holder" instance {
        field["name"] = string("Strongly reachable")
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }

    HeapDive.open(file).use { dive ->
      val tree = dive.tree
      val topLevel = tree.children(tree.root)

      // One rectangle beside the objects a GC root holds, rather than a level of its own beside a level of
      // theirs: the root of the map is the whole heap dump wherever the reader is.
      assertThat(topLevel.map { tree.label(it) }).contains("Holder", ReachabilityStrength.UNREACHABLE.label)
      val unreachable = tree.groupOrNull(UNREACHABLE_NODE_ID)!!
      assertThat(unreachable.strength).isEqualTo(ReachabilityStrength.UNREACHABLE)
      assertThat(unreachable.objectCount).isEqualTo(2)
      // The referring array, which the one it points at nests under.
      assertThat(tree.children(unreachable.nodeId)).hasSize(1)
    }
  }

  @Test fun `a garbage cycle is walked from one of its objects`() {
    val file = testFolder.newFile("cycle.hprof")
    file.dump {
      referenceClasses()
      // Two objects pointing at each other and at nothing else: neither is an entry point, so a walk of
      // the garbage only reaches them by picking one of them arbitrarily.
      val nodeClassId = clazz("com.example.Node", fields = listOf("next" to ReferenceHolder::class))
      val firstId = reserveObjectId()
      val second = instance(nodeClassId, listOf(firstId))
      instance(nodeClassId, listOf(second), objectId = firstId)
      val holder = "com.example.Holder" instance {
        field["name"] = string("Strongly reachable")
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }

    HeapDive.open(file).use { dive ->
      val tree = dive.tree
      val garbage = tree.groupOrNull(tree.children(tree.root).last())!!

      assertThat(garbage.objectCount).isEqualTo(2)
      assertThat(garbage.retainedSize).isEqualTo(dive.sizes.unreachableByteCount)
    }
  }

  @Test fun `the characters of a garbage string are counted inside it`() {
    val file = testFolder.newFile("garbage-string.hprof")
    file.dump {
      referenceClasses()
      // A string's char array is no node of the graph — its bytes are folded into the string — so it must
      // not show up as a second piece of garbage.
      string("Garbage")
      val holder = "com.example.Holder" instance {
        field["name"] = string("Strongly reachable")
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }

    HeapDive.open(file).use { dive ->
      val tree = dive.tree
      val garbage = tree.groupOrNull(tree.children(tree.root).last())!!

      assertThat(tree.children(garbage.nodeId).map { tree.label(it) }).containsExactly("String")
      assertThat(dive.sizes.reachableByteCount + dive.sizes.unreachableByteCount)
        .isEqualTo(dive.sizes.totalByteCount)
    }
  }

  @Test fun `a string the intern table is the only holder of is reachable`() {
    val file = testFolder.newFile("interned.hprof")
    file.dump {
      referenceClasses()
      // Nothing points at it: an app dump has 67 K of these, and they only have a root record.
      val interned = string("Interned")
      gcRoot(InternedString(id = interned.value))
    }

    HeapDive.open(file).use { dive ->
      assertThat(dive.sizes.unreachableByteCount).isZero()
      assertThat(dive.sizes.byteCountByStrength.getValue(STRONG))
        .isEqualTo(dive.sizes.totalByteCount)
    }
  }

  @Test fun `the tables ART embeds in a class are held by the class`() {
    val file = testFolder.newFile("class-overhead.hprof")
    var holderClassId = 0L
    file.dump {
      // A long array rather than the byte array ART writes: what's being tested is the field name.
      val overhead = primitiveLongArray(LongArray(8))
      holderClassId = clazz(
        className = "com.example.Holder",
        staticFields = listOf("\$classOverhead" to ReferenceHolder(overhead))
      )
      gcRoot(StickyClass(id = holderClassId))
    }

    HeapDive.open(file).use { dive ->
      val tree = dive.tree

      assertThat(dive.sizes.unreachableByteCount).isZero()
      assertThat(tree.children(holderClassId).map { tree.label(it) }).containsExactly("long[]")
    }
  }

  /** The strength of a payload array reachable only through a reference of class [referenceClass]. */
  private fun strengthOfPayloadHeldBy(referenceClass: (ReferenceClasses) -> Long) =
    strengthOfPayload { classes, payload -> reference(referenceClass(classes), payload) }

  /**
   * The strength of a payload array, where [holdPayload] builds whatever the one GC root points at
   * and is the only thing referencing the payload.
   */
  private fun strengthOfPayload(
    holdPayload: HprofWriterHelper.(ReferenceClasses, ReferenceHolder) -> ReferenceHolder
  ): ReachabilityStrength {
    val file = payloadHeapDump(holdPayload)
    return HeapDive.open(file).use { dive ->
      dive.tree.run { strengthOf(payloadObjectId()) }
    }
  }

  private fun payloadHeapDump(
    holdPayload: HprofWriterHelper.(ReferenceClasses, ReferenceHolder) -> ReferenceHolder
  ): File {
    val file = testFolder.newFile("payload.hprof")
    file.dump {
      val classes = referenceClasses()
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val root = holdPayload(classes, payload)
      gcRoot(JniGlobal(id = root.value, jniGlobalRefId = 0))
    }
    return file
  }

  /** The one `Object[]` of a payload heap dump, found by walking the tree. */
  private fun HeapDominatorTreemap.payloadObjectId(): Long {
    val toVisit = ArrayDeque(listOf(root))
    while (toVisit.isNotEmpty()) {
      val objectId = toVisit.removeFirst()
      if (objectId != root && label(objectId) == "Object[]") {
        return objectId
      }
      toVisit += children(objectId)
    }
    throw AssertionError("No Object[] in the tree")
  }

  companion object {
    private const val PAYLOAD_ELEMENT_COUNT = 1024
    private const val ID_BYTE_SIZE = 4
  }
}
