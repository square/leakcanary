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
package leakcanary.internal

import java.io.File

/** Dumps the heap into a file.  */
internal fun interface HeapDumper {

  /**
   * @return a [File] referencing the dumped heap, or [.RETRY_LATER] if the heap could
   * not be dumped.
   */
  fun dumpHeap(): DumpHeapResult
}

/** Dump heap result holding the file and the dump heap duration */
internal sealed class DumpHeapResult

internal data class HeapDump(
  val file: File,
  val durationMillis: Long
) : DumpHeapResult()

internal object NoHeapDump : DumpHeapResult()

