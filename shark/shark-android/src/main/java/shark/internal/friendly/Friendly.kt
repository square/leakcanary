@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:JvmName("shark-android_Friendly")

package shark.internal.friendly

import shark.AndroidNativeSizeMapper
import shark.HeapGraph

internal inline fun HeapGraph.mapNativeSizes() =
  AndroidNativeSizeMapper.mapNativeSizes(this)
