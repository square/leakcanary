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

import android.app.Application
import leakcanary.internal.HeapAnalyzerService
import leakcanary.HeapDump.Listener

class ServiceHeapDumpListener(
  private val application: Application,
  private val listenerServiceClass: Class<out AbstractAnalysisResultService>
) : Listener {

  override fun analyze(heapDump: HeapDump) {
    HeapAnalyzerService.runAnalysis(application, heapDump, listenerServiceClass)
  }
}
