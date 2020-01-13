package leakcanary

import shark.LeakingObjectFinder
import shark.MetadataExtractor
import shark.ObjectInspector
import shark.ReferenceMatcher

/**
 * Builder for [LeakCanary.Config] intended to be used only from Java code.
 *
 * Usage:
 * ```
 * LeakCanary.Config config = LeakCanary.getConfig().newBuilder()
 *    .retainedVisibleThreshold(3)
 *    .build();
 * LeakCanary.setConfig(config);
 * ```
 *
 * For idiomatic Kotlin use `copy()` method instead:
 * ```
 * LeakCanary.config = LeakCanary.config.copy(retainedVisibleThreshold = 3)
 * ```
 */
@Suppress("TooManyFunctions")
class ConfigBuilder(config: LeakCanary.Config) {
  private var dumpHeap: Boolean = config.dumpHeap
  private var dumpHeapWhenDebugging: Boolean = config.dumpHeapWhenDebugging
  private var retainedVisibleThreshold: Int = config.retainedVisibleThreshold
  private var referenceMatchers: List<ReferenceMatcher> = config.referenceMatchers
  private var objectInspectors: List<ObjectInspector> = config.objectInspectors
  private var onHeapAnalyzedListener: OnHeapAnalyzedListener = config.onHeapAnalyzedListener
  private var metadataExtractor: MetadataExtractor = config.metadataExtractor
  private var computeRetainedHeapSize: Boolean = config.computeRetainedHeapSize
  private var maxStoredHeapDumps: Int = config.maxStoredHeapDumps
  private var requestWriteExternalStoragePermission: Boolean =
    config.requestWriteExternalStoragePermission
  private var leakingObjectFinder: LeakingObjectFinder = config.leakingObjectFinder

  /** @see [LeakCanary.Config.dumpHeap] */
  fun dumpHeap(dumpHeap: Boolean) =
    apply { this.dumpHeap = dumpHeap }

  /** @see [LeakCanary.Config.dumpHeapWhenDebugging] */
  fun dumpHeapWhenDebugging(dumpHeapWhenDebugging: Boolean) =
    apply { this.dumpHeapWhenDebugging = dumpHeapWhenDebugging }

  /** @see [LeakCanary.Config.retainedVisibleThreshold] */
  fun retainedVisibleThreshold(retainedVisibleThreshold: Int) =
    apply { this.retainedVisibleThreshold = retainedVisibleThreshold }

  /** @see [LeakCanary.Config.referenceMatchers] */
  fun referenceMatchers(referenceMatchers: List<ReferenceMatcher>) =
    apply { this.referenceMatchers = referenceMatchers }

  /** @see [LeakCanary.Config.objectInspectors] */
  fun objectInspectors(objectInspectors: List<ObjectInspector>) =
    apply { this.objectInspectors = objectInspectors }

  /** @see [LeakCanary.Config.onHeapAnalyzedListener] */
  fun onHeapAnalyzedListener(onHeapAnalyzedListener: OnHeapAnalyzedListener) =
    apply { this.onHeapAnalyzedListener = onHeapAnalyzedListener }

  /** @see [LeakCanary.Config.metadataExtractor] */
  fun metadataExtractor(metadataExtractor: MetadataExtractor) =
    apply { this.metadataExtractor = metadataExtractor }

  /** @see [LeakCanary.Config.computeRetainedHeapSize] */
  fun computeRetainedHeapSize(computeRetainedHeapSize: Boolean) =
    apply { this.computeRetainedHeapSize = computeRetainedHeapSize }

  /** @see [LeakCanary.Config.maxStoredHeapDumps] */
  fun maxStoredHeapDumps(maxStoredHeapDumps: Int) =
    apply { this.maxStoredHeapDumps = maxStoredHeapDumps }

  /** @see [LeakCanary.Config.requestWriteExternalStoragePermission] */
  fun requestWriteExternalStoragePermission(requestWriteExternalStoragePermission: Boolean) =
    apply { this.requestWriteExternalStoragePermission = requestWriteExternalStoragePermission }

  /** @see [LeakCanary.Config.leakingObjectFinder] */
  fun leakingObjectFinder(leakingObjectFinder: LeakingObjectFinder) =
    apply { this.leakingObjectFinder = leakingObjectFinder }

  fun build(): LeakCanary.Config =
    LeakCanary.config.copy(
        dumpHeap = this.dumpHeap,
        dumpHeapWhenDebugging = this.dumpHeapWhenDebugging,
        retainedVisibleThreshold = this.retainedVisibleThreshold,
        referenceMatchers = this.referenceMatchers,
        objectInspectors = this.objectInspectors,
        onHeapAnalyzedListener = this.onHeapAnalyzedListener,
        metadataExtractor = this.metadataExtractor,
        computeRetainedHeapSize = this.computeRetainedHeapSize,
        maxStoredHeapDumps = this.maxStoredHeapDumps,
        requestWriteExternalStoragePermission = this.requestWriteExternalStoragePermission,
        leakingObjectFinder = this.leakingObjectFinder
    )
}