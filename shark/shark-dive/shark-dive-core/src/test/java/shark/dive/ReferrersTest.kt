package shark.dive

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.LeakTrace
import shark.ValueHolder.ReferenceHolder
import shark.dive.Verdict.STUCK
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
      assertThat(referrers.hasMore).isFalse()
      assertThat(referrers.gcRootType).isNull()
    }
  }

  @Test fun `a capped list says how many it left out`() {
    HeapDive.open(testFolder.sharedParentHeapDump()).use { dive ->
      val tree = dive.tree

      val referrers = tree.referrersOf(tree.findByLabel("Object[]").objectId, limit = 1)

      assertThat(referrers.referrers).hasSize(1)
      assertThat(referrers.referrerCount).isEqualTo(2)
      assertThat(referrers.hasMore).isTrue()
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
      assertThat(referrers.referrerCount).isEqualTo(2)
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

  @Test fun `a gc rooted object nothing points at says which root holds it`() {
    HeapDive.open(testFolder.cachedPayloadHeapDump()).use { dive ->
      val tree = dive.tree

      val referrers = tree.referrersOf(tree.findByLabel("Tile").objectId)

      assertThat(referrers.referrers).isEmpty()
      assertThat(referrers.referrerCount).isEqualTo(0)
      assertThat(referrers.gcRootType).isEqualTo(LeakTrace.GcRootType.JNI_GLOBAL)
    }
  }

  @Test fun `a referrer carries the verdict set on it by hand`() {
    HeapDive.open(testFolder.sharedParentHeapDump()).use { dive ->
      val tree = dive.tree
      val footer = tree.findByLabel("Footer")
      val overrides = VerdictOverrides.of(
        listOf(VerdictOverride(objectId = footer.objectId, verdict = STUCK, reason = "the footer is gone"))
      )

      val referrers = tree.referrersOf(tree.findByLabel("Object[]").objectId, overrides = overrides)

      val footerStep = referrers.referrers.single { it.step.objectId == footer.objectId }.step
      assertThat(footerStep.verdict).isEqualTo(STUCK)
      assertThat(footerStep.reference).isNull()
    }
  }

  @Test fun `the whole heap dump has nothing pointing at it`() {
    HeapDive.open(testFolder.sharedParentHeapDump()).use { dive ->
      val tree = dive.tree

      assertThat(tree.referrersOf(tree.root)).isEqualTo(ObjectReferrers.NONE)
    }
  }

  /**
   * How these tests read a list of referrers: the class declaring each reference, the reference, and
   * whether the tree holds the object through it.
   */
  private fun ObjectReferrers.referenceLabels(): List<String> = referrers.flatMap { referrer ->
    referrer.references.map { (reference, holds) ->
      "${reference.ownerClassName}.${reference.name}" + if (holds) "" else " (holds nothing)"
    }
  }

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
