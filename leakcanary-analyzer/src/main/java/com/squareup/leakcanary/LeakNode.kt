package com.squareup.leakcanary

import com.squareup.haha.perflib.Instance

data class LeakNode(
  val exclusion: Exclusion?,
  val instance: Instance?,
  val parent: LeakNode?,
  val leakReference: LeakReference?
)