package leakcanary.internal.perflib

import com.squareup.haha.perflib.Instance
import leakcanary.Exclusion
import leakcanary.LeakReference

internal data class LeakNode(
  val exclusion: Exclusion?,
  val instance: Instance,
  val parent: LeakNode?,
  val leakReference: LeakReference?
)