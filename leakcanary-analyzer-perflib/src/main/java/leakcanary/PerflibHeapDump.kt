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
package leakcanary

import java.io.File
import java.io.Serializable

/** Data structure holding information about a heap dump.  */
class PerflibHeapDump internal constructor(builder: Builder) : Serializable {

  /** The heap dump file, which you might want to upload somewhere.  */
  val heapDumpFile: File

  val gcDurationMs: Long
  val heapDumpDurationMs: Long
  val computeRetainedHeapSize: Boolean

  init {
    this.heapDumpFile = builder.heapDumpFile
    this.computeRetainedHeapSize = builder.computeRetainedHeapSize
    this.gcDurationMs = builder.gcDurationMs
    this.heapDumpDurationMs = builder.heapDumpDurationMs
  }

  fun buildUpon(): Builder {
    return Builder(this)
  }

  class Builder(
    internal var heapDumpFile: File
  ) {
    internal var gcDurationMs: Long = 0
    internal var heapDumpDurationMs: Long = 0
    internal var computeRetainedHeapSize: Boolean = false

    internal constructor(heapDump: PerflibHeapDump) : this(heapDump.heapDumpFile) {
      this.heapDumpFile = heapDump.heapDumpFile
      this.computeRetainedHeapSize = heapDump.computeRetainedHeapSize
      this.gcDurationMs = heapDump.gcDurationMs
      this.heapDumpDurationMs = heapDump.heapDumpDurationMs
    }

    fun heapDumpFile(heapDumpFile: File): Builder {
      this.heapDumpFile = heapDumpFile
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

    fun build(): PerflibHeapDump {
      return PerflibHeapDump(this)
    }
  }

  companion object {
    fun builder(heapDumpFile: File): Builder {
      return Builder(heapDumpFile)
    }
  }
}
