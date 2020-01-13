package leakcanary

import shark.LeakingObjectFinder
import shark.MetadataExtractor
import shark.ObjectInspector
import shark.ReferenceMatcher

/**
 * Builder for [LeakCanary.Config] intended to use only by Java callers.
 *
 * Usage:
 * ```
 * LeakCanary.Config config = LeakCanary.getConfig().newBuilder()
 *    .dumpHeap(false)
 *    .retainedVisibleThreshold(3)
 *    .maxStoredHeapDumps(10)
 *    .build();
 * LeakCanary.setConfig(config);
 * ```
 *
 * Kotlin callers should be using `copy()` method instead:
 * ```
 * LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
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
  fun dumpHeap(value: Boolean) = apply { dumpHeap = value }

  /** @see [LeakCanary.Config.dumpHeapWhenDebugging] */
  fun dumpHeapWhenDebugging(value: Boolean) = apply { dumpHeapWhenDebugging = value }

  /** @see [LeakCanary.Config.retainedVisibleThreshold] */
  fun retainedVisibleThreshold(value: Int) = apply { retainedVisibleThreshold = value }

  /** @see [LeakCanary.Config.referenceMatchers] */
  fun referenceMatchers(value: List<ReferenceMatcher>) = apply { referenceMatchers = value }

  /** @see [LeakCanary.Config.objectInspectors] */
  fun objectInspectors(value: List<ObjectInspector>) = apply { objectInspectors = value }

  /** @see [LeakCanary.Config.onHeapAnalyzedListener] */
  fun onHeapAnalyzedListener(value: OnHeapAnalyzedListener) =
    apply { onHeapAnalyzedListener = value }

  /** @see [LeakCanary.Config.metadataExtractor] */
  fun metadataExtractor(value: MetadataExtractor) = apply { metadataExtractor = value }

  /** @see [LeakCanary.Config.computeRetainedHeapSize] */
  fun computeRetainedHeapSize(value: Boolean) = apply { computeRetainedHeapSize = value }

  /** @see [LeakCanary.Config.maxStoredHeapDumps] */
  fun maxStoredHeapDumps(value: Int) = apply { maxStoredHeapDumps = value }

  /** @see [LeakCanary.Config.requestWriteExternalStoragePermission] */
  fun requestWriteExternalStoragePermission(value: Boolean) =
    apply { requestWriteExternalStoragePermission = value }

  /** @see [LeakCanary.Config.leakingObjectFinder] */
  fun leakingObjectFinder(value: LeakingObjectFinder) = apply { leakingObjectFinder = value }

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