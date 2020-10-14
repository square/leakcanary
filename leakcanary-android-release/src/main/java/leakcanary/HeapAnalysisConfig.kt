package leakcanary

import shark.AndroidMetadataExtractor
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.FilteringLeakingObjectFinder
import shark.LeakingObjectFinder
import shark.MetadataExtractor
import shark.ObjectInspector
import shark.ReferenceMatcher

data class HeapAnalysisConfig(

  /**
   * Known patterns of references in the heap, added here either to ignore them
   * ([IgnoredReferenceMatcher]) or to mark them as library leaks ([LibraryLeakReferenceMatcher]).
   *
   * When adding your own custom [LibraryLeakReferenceMatcher] instances, you'll most
   * likely want to set [LibraryLeakReferenceMatcher.patternApplies] with a filter that checks
   * for the Android OS version and manufacturer. The build information can be obtained by calling
   * [shark.AndroidBuildMirror.fromHeapGraph].
   *
   * Defaults to [AndroidReferenceMatchers.appDefaults]
   */
  val referenceMatchers: List<ReferenceMatcher> = AndroidReferenceMatchers.appDefaults,

  /**
   * List of [ObjectInspector] that provide LeakCanary with insights about objects found in the
   * heap. You can create your own [ObjectInspector] implementations, and also add
   * a [shark.AppSingletonInspector] instance created with the list of internal singletons.
   *
   * Defaults to [AndroidObjectInspectors.appDefaults]
   */
  val objectInspectors: List<ObjectInspector> = AndroidObjectInspectors.appDefaults,

  /**
   * Extracts metadata from a hprof to be reported in [shark.HeapAnalysisSuccess.metadata].
   * Called on a background thread during heap analysis.
   *
   * Defaults to [AndroidMetadataExtractor]
   */
  val metadataExtractor: MetadataExtractor = AndroidMetadataExtractor,

  /**
   * Whether to compute the retained heap size, which is the total number of bytes in memory that
   * would be reclaimed if the detected leaks didn't happen. This includes native memory
   * associated to Java objects (e.g. Android bitmaps).
   *
   * Computing the retained heap size can slow down the analysis because it requires navigating
   * from GC roots through the entire object graph, whereas [shark.HeapAnalyzer] would otherwise
   * stop as soon as all leaking instances are found.
   *
   * Defaults to true.
   */
  val computeRetainedHeapSize: Boolean = true,

  /**
   * Finds the objects that are leaking, for which LeakCanary will compute leak traces.
   *
   * Defaults to a [FilteringLeakingObjectFinder] that scans all objects in the heap dump and
   * delegates the decision to [AndroidObjectInspectors.appLeakingObjectFilters].
   */
  val leakingObjectFinder: LeakingObjectFinder = FilteringLeakingObjectFinder(
      AndroidObjectInspectors.appLeakingObjectFilters
  ),

  /**
   * Whether the first step after a heap dump should be to replace the content of all arrays with
   * zeroes. This increases the overall processing time but limits the amount of time the heap
   * dump exists on disk with potential PII.
   */
  val stripHeapDump: Boolean = false
)