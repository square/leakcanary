package shark.dive

import com.sun.management.HotSpotDiagnosticMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import shark.dive.ReachabilityStrength.FINALIZER
import shark.dive.ReachabilityStrength.PHANTOM
import shark.dive.ReachabilityStrength.SOFT
import shark.dive.ReachabilityStrength.STRONG
import shark.dive.ReachabilityStrength.WEAK

/**
 * What Shark Dive makes of the four `java.lang.ref` strengths in a heap dump of a real JVM, this one:
 * the class hierarchies, the field names and the lists the runtime keeps its references on are the
 * runtime's own rather than this repo's idea of them.
 *
 * Worth a test of its own next to the `dump { }` ones, because the difference between the two is where
 * the bug was: the lists. A `Finalizer` and a `Cleaner` live on a doubly linked list hanging off one
 * static field, so `next` is the only thing that reaches any entry but the first, and Shark Dive used
 * to inherit LeakCanary's matchers, which drop those links so that no leak trace runs through the
 * finalization queue. Everything on such a list past its head read as uncollected garbage, and every
 * object waiting to be finalized or cleaned with it. Nothing about the shape of a reference class says
 * that — only a runtime that really keeps such a list does.
 *
 * **The dump is written without collecting first**, which taking a heap dump normally does, because a
 * collection clears exactly what this is about: a weak referent nothing else holds is then gone from the
 * dump rather than weakly reachable in it, and since JDK 9 a phantom referent is cleared as soon as the
 * collector finds the object phantom reachable. It is also the shape a real Android dump has —
 * `large-dump.hprof` holds 4769 `FinalizerReference`s and 3542 `Cleaner`s with a referent still in them.
 *
 * **The dump is left in `build/heap-dumps` rather than in a temporary folder**, so that Shark Dive can
 * be opened on the same heap dump these assertions ran against:
 *
 * ```
 * ./gradlew :shark:shark-dive:shark-dive-app:runNamed --args="--title=\"Reference strengths\" \
 *   shark/shark-dive/shark-dive-core/build/heap-dumps/jvm-reference-strengths.hprof"
 * ```
 *
 * Each payload holds an array large enough to be a rectangle of its own on the treemap, and no two of
 * them are the same size, so that the strengths tell each other apart there.
 */
class JvmReferenceStrengthTest {

  @Test fun `an object only a weak reference holds is weakly reachable`() {
    val weak = dive.tree.onlyInstanceOf("WeaklyHeldPayload")

    assertThat(weak.strength).isEqualTo(WEAK)
    assertThat(dive.tree.dominatorOf(weak.objectId)!!.label).isEqualTo("WeakReference")
    assertThat(dive.tree.rootPathTo(weak.objectId).stepLabels().last())
      .isEqualTo("referent → WeaklyHeldPayload")
  }

  @Test fun `an object only a soft reference holds is softly reachable`() {
    val soft = dive.tree.onlyInstanceOf("SoftlyHeldPayload")

    assertThat(soft.strength).isEqualTo(SOFT)
    assertThat(dive.tree.dominatorOf(soft.objectId)!!.label).isEqualTo("SoftReference")
  }

  @Test fun `an object only a phantom reference holds is phantom reachable`() {
    val phantom = dive.tree.onlyInstanceOf("PhantomlyHeldPayload")

    assertThat(phantom.strength).isEqualTo(PHANTOM)
    assertThat(dive.tree.dominatorOf(phantom.objectId)!!.label).isEqualTo("PhantomReference")
  }

  @Test fun `every object waiting to be finalized is finalizer reachable`() {
    val finalizable = dive.tree.instancesOf("FinalizablePayload")

    // All of them rather than only the first: they are entries of the list `Finalizer.unfinalized`
    // starts, and only the head of that list is reachable without following `next`.
    assertThat(finalizable).hasSize(FINALIZABLE_COUNT)
    assertThat(finalizable).allSatisfy { assertThat(it.strength).isEqualTo(FINALIZER) }
    assertThat(finalizable.map { dive.tree.dominatorOf(it.objectId)!!.label })
      .allMatch { it == "Finalizer" }
  }

  @Test fun `an object a field also holds is strongly reachable`() {
    val both = dive.tree.onlyInstanceOf("StronglyAndWeaklyHeldPayload")

    // A weak reference holds nothing that something else holds, so the field wins and the tree draws the
    // object under whatever owns it. Which here is the class rather than the singleton: a Kotlin object's
    // fields are reached through the `INSTANCE` static of its own class.
    assertThat(both.strength).isEqualTo(STRONG)
    assertThat(dive.tree.dominatorOf(both.objectId)!!.label).isEqualTo("class References")
  }

  @Test fun `a path through a weak reference is weak the rest of the way down`() {
    val box = dive.tree.onlyInstanceOf("WeaklyHeldBox")
    val boxed = dive.tree.onlyInstanceOf("BoxedPayload")

    // The box holds the payload with an ordinary field and only a weak reference holds the box, so the
    // payload goes when the box does and is no more firmly held than the box is.
    assertThat(box.strength).isEqualTo(WEAK)
    assertThat(boxed.strength).isEqualTo(WEAK)
    assertThat(dive.tree.dominatorOf(boxed.objectId)!!.label).isEqualTo("WeaklyHeldBox")
  }

  @Test fun `every object of the heap dump has a strength`() {
    // Which is what makes the strengths above a breakdown of the whole heap dump rather than of a part
    // of it: nothing is left over to be counted at no strength at all.
    assertThat(dive.sizes.objectCountByStrength.values.sum())
      .isEqualTo(dive.sizes.totalObjectCount)
  }

  companion object {
    private const val FINALIZABLE_COUNT = 3

    private val HEAP_DUMP_FILE = File("build/heap-dumps", "jvm-reference-strengths.hprof")

    /**
     * One heap dump for the whole class: writing it is IO and opening it is three passes over what came
     * out, and no test here changes either.
     */
    private lateinit var dive: HeapDive

    @BeforeClass @JvmStatic fun openHeapDumpOfThisJvm() {
      allocateEveryPayload()

      // Everything already dead in this JVM goes now, so that a dump of every object is a dump of the
      // live set plus the payloads. Without this the dump came out 172 MB against 21 MB, most of it what
      // the tests that ran before left behind, and opening that runs the test JVM out of heap. A hint
      // rather than a promise, but HotSpot collects on it unless told not to.
      //
      // Every payload is still strongly held while it runs, so it neither clears a reference to one nor
      // finalizes one, and it leaves the most room there is for the little that comes after it.
      System.gc()

      holdEachPayloadOnlyAsItsTestIsAbout()
      failIfAnythingCollectedTheReferents()

      HEAP_DUMP_FILE.parentFile.mkdirs()
      // Left behind by the last run, and the MBean refuses to overwrite a file.
      HEAP_DUMP_FILE.delete()
      dumpHeapWithoutCollecting(HEAP_DUMP_FILE)
      dive = HeapDive.open(HEAP_DUMP_FILE)
    }

    @AfterClass @JvmStatic fun closeHeapDump() {
      dive.close()
    }

    private fun allocateEveryPayload() {
      References.softlyHeld = SoftlyHeldPayload()
      References.weaklyHeld = WeaklyHeldPayload()
      References.phantomlyHeld = PhantomlyHeldPayload()
      References.boxHeld = WeaklyHeldBox()
      References.finalizableHeld = Array(FINALIZABLE_COUNT) { FinalizablePayload() }
      References.strong = StronglyAndWeaklyHeldPayload()
    }

    /**
     * Nothing but the references themselves is allocated between the collection above and the heap dump
     * below, because a collection in that window is what would clear them — see
     * [failIfAnythingCollectedTheReferents].
     */
    private fun holdEachPayloadOnlyAsItsTestIsAbout() {
      References.soft = SoftReference(References.softlyHeld)
      References.weak = WeakReference(References.weaklyHeld)
      References.phantomQueue = ReferenceQueue()
      References.phantom = PhantomReference(References.phantomlyHeld, References.phantomQueue)
      References.weakBox = WeakReference(References.boxHeld)
      References.weakToStrong = WeakReference(References.strong)
      References.releaseStrongHolds()
    }

    /**
     * A collection between the references being created and the heap dump being written would clear them,
     * and what that looks like from a test is a payload missing from the heap dump rather than held the
     * way it should be. Said here instead, where it can name the cause.
     */
    private fun failIfAnythingCollectedTheReferents() {
      val cleared = References.soft!!.get() == null ||
        References.weak!!.get() == null ||
        References.weakBox!!.get() == null ||
        // A phantom reference hands its referent to nobody, so what says it still has one is not having
        // been enqueued: since JDK 9 the collector clears a phantom referent as it finds it and enqueues
        // the reference in the same breath.
        References.phantomQueue!!.poll() != null
      check(!cleared) {
        "A garbage collection ran between these references being created and the heap dump being " +
          "written, and cleared them, so the heap dump holds nothing for this test to find. Whatever " +
          "was added between the System.gc() above and the dump allocates enough to have caused one."
      }
    }

    /**
     * Writes a heap dump of this JVM to [file], every object of it rather than only the live ones, which
     * is what keeps the collection that would clear these references from running first. See this class.
     *
     * Not [shark.JvmTestHeapDumper], which collects, as taking a heap dump normally should.
     */
    private fun dumpHeapWithoutCollecting(file: File) {
      val mBean = ManagementFactory.newPlatformMXBeanProxy(
        ManagementFactory.getPlatformMBeanServer(),
        "com.sun.management:type=HotSpotDiagnostic",
        HotSpotDiagnosticMXBean::class.java
      )
      mBean.dumpHeap(file.absolutePath, false)
    }
  }
}

/*
 * The objects [JvmReferenceStrengthTest] holds each way, one class per way so that each is found by its
 * own name and drawn as its own rectangle. Top level rather than nested, so that the name on that
 * rectangle is the name of the thing rather than the test's name with the thing after a dollar sign.
 */

/**
 * Where they are all held from: a Kotlin object, so a field here is a field of a singleton its own
 * class's static points at, which is a GC root.
 *
 * Every payload is held twice over its life. First strongly, through one of the `*Held` fields, so that
 * the collection the heap dump is taken after finds none of them collectable. Then only the way its test
 * is about, once [releaseStrongHolds] has let the first hold go — which nulls fields rather than dropping
 * an object holding them all, because such an object would be garbage still pointing at every payload,
 * and the tree draws an object that garbage holds under the garbage.
 */
private object References {
  @JvmField var softlyHeld: SoftlyHeldPayload? = null
  @JvmField var weaklyHeld: WeaklyHeldPayload? = null
  @JvmField var phantomlyHeld: PhantomlyHeldPayload? = null
  @JvmField var boxHeld: WeaklyHeldBox? = null
  @JvmField var finalizableHeld: Array<FinalizablePayload?>? = null

  @JvmField var soft: SoftReference<SoftlyHeldPayload>? = null
  @JvmField var weak: WeakReference<WeaklyHeldPayload>? = null
  @JvmField var phantom: PhantomReference<PhantomlyHeldPayload>? = null
  @JvmField var phantomQueue: ReferenceQueue<PhantomlyHeldPayload>? = null
  @JvmField var weakBox: WeakReference<WeaklyHeldBox>? = null

  /** The one payload held both ways at once, which is what says which of the two the tree draws. */
  @JvmField var strong: StronglyAndWeaklyHeldPayload? = null
  @JvmField var weakToStrong: WeakReference<StronglyAndWeaklyHeldPayload>? = null

  fun releaseStrongHolds() {
    softlyHeld = null
    weaklyHeld = null
    phantomlyHeld = null
    boxHeld = null
    // The array itself stays, emptied: dropping it is what would leave the garbage described above.
    finalizableHeld!!.fill(null)
  }
}

private const val MB = 1024 * 1024

private class WeaklyHeldPayload {
  @JvmField val bytes = ByteArray(4 * MB)
}

private class SoftlyHeldPayload {
  @JvmField val bytes = ByteArray(3 * MB)
}

private class PhantomlyHeldPayload {
  @JvmField val bytes = ByteArray(2 * MB)
}

private class StronglyAndWeaklyHeldPayload {
  @JvmField val bytes = ByteArray(MB)
}

/** Held weakly, and holding [BoxedPayload] with an ordinary field. */
private class WeaklyHeldBox {
  @JvmField val boxed = BoxedPayload()
}

private class BoxedPayload {
  @JvmField val bytes = ByteArray(5 * MB)
}

private class FinalizablePayload {
  @JvmField val bytes = ByteArray(MB)

  /**
   * Deliberately not empty: HotSpot registers no finalizer for a `finalize()` whose body only returns, so
   * an empty one here would mean no `Finalizer` in the heap dump and nothing for the test to find.
   *
   * Finalization is deprecated for removal, and a JVM run with `--finalization=disabled` writes no
   * `Finalizer` either — which would show as the objects that should be finalizer reachable being
   * reported as garbage.
   */
  protected fun finalize() {
    bytes[0] = 1
  }
}
