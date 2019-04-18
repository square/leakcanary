package leakcanary.experimental.internal

import leakcanary.Exclusion
import leakcanary.LeakReference

internal data class LeakNode(
  val exclusion: Exclusion?,
  val instance: Long,
  val parent: LeakNode?,
  val leakReference: LeakReference?
)