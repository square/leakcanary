package shark

import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.JniLocal
import shark.GcRoot.JniMonitor
import shark.GcRoot.MonitorUsed
import shark.GcRoot.NativeStack
import shark.GcRoot.StickyClass
import shark.GcRoot.ThreadBlock
import shark.GcRoot.ThreadObject
import shark.internal.HprofInMemoryIndex
import kotlin.reflect.KClass

/**
 * An index on a [HprofFile]. See [openHeapGraph].
 */
class HprofIndex private constructor(
  private val hprof: HprofFile,
  private val index: HprofInMemoryIndex
) {

  /**
   * Opens a [CloseableHeapGraph] which you can use to navigate the indexed hprof and then close.
   */
  fun openHeapGraph(): CloseableHeapGraph = HprofHeapGraph(hprof, hprof.randomAccessReader(), index)

  companion object {

    /**
     * Creates an in memory index of [hprof].
     */
    fun memoryIndex(
      hprof: HprofFile,
      proguardMapping: ProguardMapping? = null,
      indexedGcRootTypes: Set<KClass<out GcRoot>> = defaultIndexedGcRootTypes()
    ): HprofIndex {
      val index = HprofInMemoryIndex.createReadingHprof(hprof, proguardMapping, indexedGcRootTypes)
      return HprofIndex(hprof, index)
    }

    fun defaultIndexedGcRootTypes() = setOf(
        JniGlobal::class,
        JavaFrame::class,
        JniLocal::class,
        MonitorUsed::class,
        NativeStack::class,
        StickyClass::class,
        ThreadBlock::class,
        // ThreadObject points to threads, which we need to find the thread that a JavaLocalPattern
        // belongs to
        ThreadObject::class,
        JniMonitor::class
        /*
        Not included here:

        VmInternal: Ignoring because we've got 150K of it, but is this the right thing
        to do? What's VmInternal exactly? History does not go further than
        https://android.googlesource.com/platform/dalvik2/+/refs/heads/master/hit/src/com/android/hit/HprofParser.java#77
        We should log to figure out what objects VmInternal points to.

        ReferenceCleanup: We used to keep it, but the name doesn't seem like it should create a leak.

        Unknown: it's unknown, should we care?

        We definitely don't care about those for leak finding: InternedString, Finalizing, Debugger, Unreachable
         */
    )
  }
}