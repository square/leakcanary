/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary

/**
 * A [GcTrigger] that attempts to perform a GC by invoking the corresponding JDK API then waiting
 * and then running finalization. Based on FinalizationTest in AOSP.
 */
object FinalizingInProcessGcTrigger : GcTrigger {
  override fun runGc() {
    // Code taken from AOSP FinalizationTest:
    // https://android.googlesource.com/platform/libcore/+/master/support/src/test/java/libcore/
    // java/lang/ref/FinalizationTester.java
    System.gc()
    enqueueReferences()
    System.runFinalization()
    System.gc()
    // The last GC hands the references it cleared to ART's ReferenceQueueDaemon, which enqueues
    // them on its own thread. Callers count retained objects by polling a reference queue as soon
    // as this returns, so without waiting they count the objects that GC just collected as still
    // retained, and dump a heap with nothing in it to report. Objects only get collected by the
    // last GC when the first one couldn't, e.g. because a Reference.get() during the first GC's
    // concurrent marking kept them alive.
    // On ART, runFinalization() waits for the daemon: it puts a sentinel on the daemon's list,
    // behind the references already there, and returns once that sentinel is finalized. It doesn't
    // run another GC, which it only does when a System.gc() call before it deferred one. On the
    // JVM, it doesn't wait for the Reference Handler thread that does the daemon's job there.
    System.runFinalization()
  }

  private fun enqueueReferences() {
    // Hack. We don't have a programmatic way to wait for the reference queue daemon to move
    // references to the appropriate queues.
    try {
      Thread.sleep(100)
    } catch (e: InterruptedException) {
      throw AssertionError()
    }
  }
}

fun GcTrigger.Companion.inProcess() = FinalizingInProcessGcTrigger
