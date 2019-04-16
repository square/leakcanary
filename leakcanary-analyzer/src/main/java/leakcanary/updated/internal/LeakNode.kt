package leakcanary.updated.internal

import leakcanary.Exclusion
import leakcanary.updated.LeakReference

internal data class LeakNode(
  val exclusion: Exclusion?,
  val instance: Long,
  val parent: LeakNode?,
  val leakReference: LeakReference?
)