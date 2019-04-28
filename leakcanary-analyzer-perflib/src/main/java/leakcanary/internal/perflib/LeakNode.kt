package leakcanary.internal.perflib

import com.squareup.haha.perflib.Instance
import leakcanary.PerflibExclusion
import leakcanary.LeakReference

internal data class LeakNode(
  val exclusion: PerflibExclusion?,
  val instance: Instance,
  val parent: LeakNode?,
  val leakReference: LeakReference?
)