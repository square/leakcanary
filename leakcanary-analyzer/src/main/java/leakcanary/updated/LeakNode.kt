package leakcanary.updated

import leakcanary.Exclusion

internal data class LeakNode(
  val exclusion: Exclusion?,
  val instance: Long,
  val parent: LeakNode?,
  val leakReference: LeakReference?
)