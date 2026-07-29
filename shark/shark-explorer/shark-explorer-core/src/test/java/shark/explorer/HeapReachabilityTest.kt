package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.HprofWriterHelper
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.ReachabilityStrength.FINALIZER
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.SOFT
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.WEAK

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

    HeapExplorer.open(file).use { explorer ->
      val sizes = explorer.sizes

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

    HeapExplorer.open(file).use { explorer ->
      val sizes = explorer.sizes

      assertThat(sizes.reachableByteCount + sizes.unreachableByteCount)
        .isEqualTo(sizes.totalByteCount)
      assertThat(sizes.byteCountByStrength.getValue(SOFT)).isEqualTo(64L * ID_BYTE_SIZE)
      assertThat(sizes.byteCountByStrength.getValue(WEAK)).isEqualTo(128L * ID_BYTE_SIZE)
      assertThat(sizes.unreachableByteCount).isEqualTo(32L * ID_BYTE_SIZE)
    }
  }

  @Test fun `the strongly reachable tree leaves out everything weaker`() {
    val file = testFolder.newFile("weaker.hprof")
    file.dump {
      val classes = referenceClasses()
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val holder = "com.example.Holder" instance {
        field["softly"] = reference(classes.softId, payload)
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }

    HeapExplorer.open(file).use { explorer ->
      val strongOnly = explorer.treeFor(emptySet())
      val withSoft = explorer.treeFor(setOf(SOFT))

      assertThat(withSoft.weight(withSoft.root) - strongOnly.weight(strongOnly.root))
        .isEqualTo(PAYLOAD_ELEMENT_COUNT.toLong() * ID_BYTE_SIZE)
      // Following soft references only: a weak one still retains nothing.
      assertThat(explorer.treeFor(setOf(WEAK)).weight(strongOnly.root))
        .isEqualTo(strongOnly.weight(strongOnly.root))
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
    return HeapExplorer.open(file).use { explorer ->
      // Following every strength, so that the payload is a node of the tree whatever it comes out as.
      val tree = explorer.treeFor(ReachabilityStrength.values().toSet())
      tree.strengthOf(tree.payloadObjectId())
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
