package shark

import shark.internal.HprofInMemoryIndex
import java.util.EnumSet

/**
 * An index on a Hprof file. See [openHeapGraph].
 */
class HprofIndex private constructor(
  private val sourceProvider: RandomAccessSourceProvider,
  private val header: HprofHeader,
  private val index: HprofInMemoryIndex
) {

  /**
   * Opens a [CloseableHeapGraph] which you can use to navigate the indexed hprof and then close.
   */
  fun openHeapGraph(): CloseableHeapGraph {
    val reader = RandomAccessHprofReader.openReaderFor(sourceProvider, header)
    return HprofHeapGraph(header, reader, index)
  }

  companion object {
    /**
     * Creates an in memory index of an hprof source provided by [hprofSourceProvider].
     */
    fun indexRecordsOf(
      hprofSourceProvider: DualSourceProvider,
      hprofHeader: HprofHeader,
      proguardMapping: ProguardMapping? = null,
      indexedGcRootTags: Set<HprofRecordTag> = defaultIndexedGcRootTags()
    ): HprofIndex {
      val reader = StreamingHprofReader.readerFor(hprofSourceProvider, hprofHeader)
      val index = HprofInMemoryIndex.indexHprof(
          reader = reader,
          hprofHeader = hprofHeader,
          proguardMapping = proguardMapping,
          indexedGcRootTags = indexedGcRootTags
      )
      return HprofIndex(hprofSourceProvider, hprofHeader, index)
    }

    fun defaultIndexedGcRootTags() = EnumSet.of(
        HprofRecordTag.ROOT_JNI_GLOBAL,
        HprofRecordTag.ROOT_JAVA_FRAME,
        HprofRecordTag.ROOT_JNI_LOCAL,
        HprofRecordTag.ROOT_MONITOR_USED,
        HprofRecordTag.ROOT_NATIVE_STACK,
        HprofRecordTag.ROOT_STICKY_CLASS,
        HprofRecordTag.ROOT_THREAD_BLOCK,
        // ThreadObject points to threads, which we need to find the thread that a JavaLocalPattern
        // belongs to
        HprofRecordTag.ROOT_THREAD_OBJECT,
        HprofRecordTag.ROOT_JNI_MONITOR
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