package leakcanary.experimental.internal

import leakcanary.Exclusion
import leakcanary.LeakReference

/**
 * TODO We can improve memory usage but turning this into two longs: instance object id and parent
 * object id. "Exclusion" and "leakReference" can be reconstructed once we have the leak chain,
 * costing a few extra reads. Currently exclusion and leakReference are mostly null so use up 0
 * bytes, so it's 8 bits for instance and 32 bits for parent, which we could change to 8 bits,
 * a reduction of 40% (currently 5Mb of nodes with InstrumentationLeakDetectorTest)
 *
 */
internal data class LeakNode(
  val exclusion: Exclusion?,
  val instance: Long,
  val parent: LeakNode?,
  val leakReference: LeakReference?
)