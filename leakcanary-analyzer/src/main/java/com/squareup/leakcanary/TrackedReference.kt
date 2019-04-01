package com.squareup.leakcanary

data class TrackedReference(
  /** Corresponds to [KeyedWeakReference.key].  */
  val key: String,
  /** Corresponds to [KeyedWeakReference.name].  */
  val name: String,
  /** Class of the tracked instance.  */
  val className: String,
  /** List of all fields (member and static) for that instance.  */
  val fields: List<LeakReference>
)