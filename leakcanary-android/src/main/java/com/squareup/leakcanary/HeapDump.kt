/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary

import java.io.File
import java.io.Serializable
import java.util.ArrayList
import java.util.Collections.unmodifiableList

/** Data structure holding information about a heap dump.  */
class HeapDump internal constructor(builder: Builder) : Serializable {

  /** The heap dump file, which you might want to upload somewhere.  */
  val heapDumpFile: File

  /** References that should be ignored when analyzing this heap dump.  */
  val excludedRefs: ExcludedRefs

  val gcDurationMs: Long
  val heapDumpDurationMs: Long
  val computeRetainedHeapSize: Boolean
  val reachabilityInspectorClasses: List<Class<out Reachability.Inspector>>

  /** Receives a heap dump to analyze.  */
  interface Listener {

    fun analyze(heapDump: HeapDump)

    companion object {
      val NONE: Listener = object : Listener {
        override fun analyze(heapDump: HeapDump) {}
      }
    }
  }

  init {
    this.heapDumpFile = builder.heapDumpFile!!
    this.excludedRefs = builder.excludedRefs!!
    this.computeRetainedHeapSize = builder.computeRetainedHeapSize
    this.gcDurationMs = builder.gcDurationMs
    this.heapDumpDurationMs = builder.heapDumpDurationMs
    this.reachabilityInspectorClasses = builder.reachabilityInspectorClasses!!
  }

  fun buildUpon(): Builder {
    return Builder(this)
  }

  class Builder {
    internal var heapDumpFile: File? = null
    internal var excludedRefs: ExcludedRefs? = null
    internal var gcDurationMs: Long = 0
    internal var heapDumpDurationMs: Long = 0
    internal var computeRetainedHeapSize: Boolean = false
    internal var reachabilityInspectorClasses: List<Class<out Reachability.Inspector>>? = null

    internal constructor() {
      this.heapDumpFile = null
      excludedRefs = null
      gcDurationMs = 0
      heapDumpDurationMs = 0
      computeRetainedHeapSize = false
      reachabilityInspectorClasses = null
    }

    internal constructor(heapDump: HeapDump) {
      this.heapDumpFile = heapDump.heapDumpFile
      this.excludedRefs = heapDump.excludedRefs
      this.computeRetainedHeapSize = heapDump.computeRetainedHeapSize
      this.gcDurationMs = heapDump.gcDurationMs
      this.heapDumpDurationMs = heapDump.heapDumpDurationMs
      this.reachabilityInspectorClasses = heapDump.reachabilityInspectorClasses
    }

    fun heapDumpFile(heapDumpFile: File): Builder {
      this.heapDumpFile = heapDumpFile
      return this
    }

    fun excludedRefs(excludedRefs: ExcludedRefs): Builder {
      this.excludedRefs = excludedRefs
      return this
    }

    fun gcDurationMs(gcDurationMs: Long): Builder {
      this.gcDurationMs = gcDurationMs
      return this
    }

    fun heapDumpDurationMs(heapDumpDurationMs: Long): Builder {
      this.heapDumpDurationMs = heapDumpDurationMs
      return this
    }

    fun computeRetainedHeapSize(computeRetainedHeapSize: Boolean): Builder {
      this.computeRetainedHeapSize = computeRetainedHeapSize
      return this
    }

    fun reachabilityInspectorClasses(
      reachabilityInspectorClasses: List<Class<out Reachability.Inspector>>
    ): Builder {
      this.reachabilityInspectorClasses = unmodifiableList<Class<out Reachability.Inspector>>(
          ArrayList<Class<out Reachability.Inspector>>(reachabilityInspectorClasses)
      )
      return this
    }

    fun build(): HeapDump {
      return HeapDump(this)
    }
  }

  companion object {
    fun builder(): Builder {
      return Builder()
    }
  }
}
