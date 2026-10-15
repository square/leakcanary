package shark.dive

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.ThreadObject
import shark.LeakTrace
import shark.ValueHolder.ReferenceHolder
import shark.dump

/**
 * [HeapDominatorTreemap.referrersOf]: every object pointing at one object, and which of its references the
 * tree holds the object through. The owner rules' side of it, a reference that holds nothing because an
 * owner holds the object, is in [OwnerReferencesTest] beside the views those rules are about.
 */
class ReferrersTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `two objects under one parent are one way of holding and two referrers`() {
    HeapDive.open(testFolder.sharedParentHeapDump()).use { dive ->
      val tree = dive.tree
      val payload = tree.findByLabel("Object[]")

      // The screen is on both ways down from the app to the payload, so the search for ways that share no
      // object finds one. One step up there are two objects, and the footer holds the payload twice.
      assertThat(tree.independentPathsFromRoots(payload.objectId).paths).hasSize(1)
      val referrers = tree.referrersOf(payload.objectId)
      assertThat(referrers.referenceLabels()).containsExactlyInAnyOrder(
        "Header.image",
        "Footer.image",
        "Footer.placeholder"
      )
      assertThat(referrers.referrerCount).isEqualTo(2)
      assertThat(referrers.holdingReferrerCount).isEqualTo(2)
      assertThat(referrers.nextOffset).isNull()
    }
  }

  @Test fun `pages of referrers add up to the whole list, in its order`() {
    HeapDive.open(testFolder.sharedParentHeapDump()).use { dive ->
      val tree = dive.tree
      val payload = tree.findByLabel("Object[]")

      val first = tree.referrersOf(payload.objectId, offset = 0, limit = 1)
      val second = tree.referrersOf(payload.objectId, offset = first.nextOffset!!, limit = 1)

      // The footer has two fields to the header's one, so it is the larger and comes first.
      assertThat(first.referenceLabels()).containsExactly("Footer.image", "Footer.placeholder")
      assertThat(first.nextOffset).isEqualTo(1)
      assertThat(second.referenceLabels()).containsExactly("Header.image")
      assertThat(second.nextOffset).isNull()
      assertThat(first.referrers + second.referrers).isEqualTo(tree.referrersOf(payload.objectId).referrers)
      assertThat(listOf(first, second).map { it.referrerCount to it.holdingReferrerCount })
        .containsOnly(2 to 2)
    }
  }

  @Test fun `a weak reference to an object held strongly points at it and holds nothing`() {
    HeapDive.open(testFolder.stronglyAndWeaklyReachablePayloadHeapDump()).use { dive ->
      val tree = dive.tree

      val referrers = tree.referrersOf(tree.findByLabel("Object[]").objectId)

      assertThat(referrers.referenceLabels()).containsExactly(
        "Holder.payload",
        "WeakReference.referent (holds nothing)"
      )
      assertThat(referrers.holdingReferrerCount).isEqualTo(1)
    }
  }

  @Test fun `what holds an object comes before what only points at it, whatever that retains`() {
    HeapDive.open(testFolder.taggedWeakReferenceHeapDump()).use { dive ->
      val tree = dive.tree
      val payload = tree.findByLabel("Object[]")
      val weakReference = tree.findByLabel("TaggedWeakReference")
      val holder = tree.findByLabel("Holder")

      val referrers = tree.referrersOf(payload.objectId)

      // The weak reference retains its tag, twice the payload, so the largest first would put it on top.
      assertThat(weakReference.retainedSize).isGreaterThan(holder.retainedSize)
      assertThat(referrers.referenceLabels()).containsExactly(
        "Holder.payload",
        "TaggedWeakReference.referent (holds nothing)"
      )
    }
  }

  @Test fun `a gc root on an object nothing points at is its one referrer`() {
    HeapDive.open(testFolder.cachedPayloadHeapDump()).use { dive ->
      val tree = dive.tree

      val referrers = tree.referrersOf(tree.findByLabel("Tile").objectId)

      assertThat(referrers.referrers)
        .containsExactly(GcRootReferrer(gcRootType = LeakTrace.GcRootType.JNI_GLOBAL, holds = true))
      assertThat(referrers.holdingReferrerCount).isEqualTo(1)
    }
  }

  @Test fun `a local variable pointing at an object a field holds is a root that holds nothing`() {
    val heapDump = testFolder.inAFieldAndARootHeapDump { payload ->
      JavaFrame(id = payload, threadSerialNumber = 1, frameNumber = 0)
    }
    HeapDive.open(heapDump).use { dive ->
      val tree = dive.tree

      val referrers = tree.referrersOf(tree.findByLabel("Object[]").objectId)

      assertThat(referrers.referenceLabels()).containsExactly("Holder.payload", "JAVA_FRAME (holds nothing)")
      assertThat(referrers.holdingReferrerCount).isEqualTo(1)
    }
  }

  /**
   * A local variable of a thread the heap dump names is read twice: as the GC root the dump records, and as
   * a reference from the thread. Neither holds an object a field holds, so it is drawn under the field.
   */
  @Test fun `a local variable of a named thread holds nothing a field holds, read either way`() {
    HeapDive.open(testFolder.onAStackAndInAFieldHeapDump()).use { dive ->
      val tree = dive.tree
      val payload = tree.findByLabel("Object[]")

      val referrers = tree.referrersOf(payload.objectId)

      assertThat(referrers.referenceLabels()).containsExactly(
        "Holder.payload",
        "JAVA_FRAME (holds nothing)",
        "Thread.<local variable> (virtual, holds nothing)"
      )
      assertThat(referrers.holdingReferrerCount).isEqualTo(1)
      assertThat(tree.dominatorOf(payload.objectId)?.nodeId).isEqualTo(tree.findByLabel("Holder").objectId)
    }
  }

  /**
   * And an object only a local variable holds is the thread's, through the reference that says which thread.
   * Through the root as well, it would have two holders and be drawn under the whole heap dump.
   */
  @Test fun `an object only a local variable of a named thread holds is held through its thread`() {
    HeapDive.open(testFolder.onlyOnAStackHeapDump()).use { dive ->
      val tree = dive.tree
      val payload = tree.findByLabel("Object[]")

      val referrers = tree.referrersOf(payload.objectId)

      assertThat(referrers.referenceLabels()).containsExactly(
        "Thread.<local variable> (virtual)",
        "JAVA_FRAME (holds nothing)"
      )
      assertThat(tree.dominatorOf(payload.objectId)?.nodeId)
        .isEqualTo(tree.instancesOf("Thread").single().objectId)
      assertThat(tree.strengthOf(payload.objectId)).isEqualTo(ReachabilityStrength.LOCAL)
    }
  }

  /**
   * Shark reads a list as holding its elements, where the heap dump has it holding an array that holds them.
   * Both are referrers, and the list's reference says it is Shark's reading.
   */
  @Test fun `an element of a list is held by the list and by the array the list keeps it in`() {
    HeapDive.open(testFolder.leakInAListHeapDump()).use { dive ->
      val tree = dive.tree

      val referrers = tree.referrersOf(tree.instancesOf("MainActivity").single().objectId)

      assertThat(referrers.referenceLabels()).containsExactly("ArrayList[x] (virtual)", "Object[][x]")
      assertThat(referrers.holdingReferrerCount).isEqualTo(2)
    }
  }

  @Test fun `a gc root holding an object comes before the objects holding it`() {
    val heapDump = testFolder.inAFieldAndARootHeapDump { payload -> JniGlobal(id = payload, jniGlobalRefId = 1) }
    HeapDive.open(heapDump).use { dive ->
      val tree = dive.tree

      val referrers = tree.referrersOf(tree.findByLabel("Object[]").objectId)

      assertThat(referrers.referenceLabels()).containsExactly("JNI_GLOBAL", "Holder.payload")
      assertThat(referrers.holdingReferrerCount).isEqualTo(2)
    }
  }

  @Test fun `the whole heap dump has nothing pointing at it`() {
    HeapDive.open(testFolder.sharedParentHeapDump()).use { dive ->
      val tree = dive.tree

      assertThat(tree.referrersOf(tree.root)).isEqualTo(ObjectReferrers.NONE)
    }
  }

  /**
   * How these tests read a list of referrers: each reference the way a leak is named after it, or the type of
   * a GC root, then whether it is Shark's reading and whether the tree holds the object through it.
   */
  private fun ObjectReferrers.referenceLabels(): List<String> = referrers.flatMap { referrer ->
    when (referrer) {
      is GcRootReferrer -> listOf(referrer.gcRootType.name to listOfNotNull(holdsNothing(referrer.holds)))
      is ObjectReferrer -> referrer.references.map { (reference, holds) ->
        reference.leakLabel() to listOfNotNull("virtual".takeIf { reference.isVirtual }, holdsNothing(holds))
      }
    }
  }.map { (label, notes) -> if (notes.isEmpty()) label else "$label (${notes.joinToString()})" }

  private fun holdsNothing(holds: Boolean) = "holds nothing".takeIf { !holds }

  /**
   * A heap dump where a GC rooted app holds a screen, which holds a header and a footer both pointing at
   * the same payload, the footer twice.
   *
   * The app is there so that the screen is between a GC rooted object and the payload. A path walked from
   * the roots starts at the object a root holds, so that one is on every path without being shared.
   */
  private fun TemporaryFolder.sharedParentHeapDump(): File {
    val file = newFile("shared-parent.hprof")
    file.dump {
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val header = "com.example.Header" instance { field["image"] = payload }
      val footer = "com.example.Footer" instance {
        field["image"] = payload
        field["placeholder"] = payload
      }
      val screen = "com.example.Screen" instance {
        field["header"] = header
        field["footer"] = footer
      }
      val app = "com.example.App" instance { field["screen"] = screen }
      gcRoot(JniGlobal(id = app.value, jniGlobalRefId = 0))
    }
    return file
  }

  /**
   * A heap dump where a GC rooted holder holds a payload that [gcRootOn] is a second GC root on.
   *
   * A [JavaFrame] here has a thread that is no object of the dump, so the local is a GC root on the payload
   * and nothing else. A thread the dump names would be a referrer too, since Shark reads a local as a
   * reference from its thread: see [onAStackAndInAFieldHeapDump].
   */
  private fun TemporaryFolder.inAFieldAndARootHeapDump(gcRootOn: (payload: Long) -> GcRoot): File {
    val file = newFile("in-a-field-and-a-root.hprof")
    file.dump {
      val payload = objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      val holder = "com.example.Holder" instance { field["payload"] = ReferenceHolder(payload) }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
      gcRoot(gcRootOn(payload))
    }
    return file
  }

  /** A heap dump where a payload is a local variable of a method running on the main thread, and nothing else. */
  private fun TemporaryFolder.onlyOnAStackHeapDump(): File {
    val file = newFile("only-on-a-stack.hprof")
    file.dump {
      val thread = instance(
        clazz(className = "java.lang.Thread", fields = listOf("name" to ReferenceHolder::class)),
        fields = listOf(string("main"))
      )
      gcRoot(ThreadObject(id = thread.value, threadSerialNumber = 42, stackTraceSerialNumber = 0))
      val payload = objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      gcRoot(JavaFrame(id = payload, threadSerialNumber = 42, frameNumber = 0))
    }
    return file
  }

  /**
   * A heap dump where a holder holds a payload and a weak reference points at it, the weak reference also
   * holding a tag twice the payload's size. The tag is an array of another class, so that the payload is
   * the one `Object[]`.
   */
  private fun TemporaryFolder.taggedWeakReferenceHeapDump(): File {
    val file = newFile("tagged-weak-reference.hprof")
    file.dump {
      val classes = referenceClasses()
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val tag = ReferenceHolder(
        objectArray(arrayClass("com.example.Tag"), LongArray(PAYLOAD_ELEMENT_COUNT * 2))
      )
      val holder = "com.example.Holder" instance { field["payload"] = payload }
      val taggedWeakReferenceClassId = clazz(
        className = "com.example.TaggedWeakReference",
        superclassId = classes.weakId,
        fields = listOf("tag" to ReferenceHolder::class)
      )
      // Most derived class first, so the tag and then the referent.
      val weakReference = instance(taggedWeakReferenceClassId, fields = listOf(tag, payload))
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = weakReference.value, jniGlobalRefId = 1))
    }
    return file
  }
}
