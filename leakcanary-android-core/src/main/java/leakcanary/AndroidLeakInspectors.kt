/*
 * Copyright (C) 2018 Square, Inc.
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

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Fragment
import android.os.MessageQueue
import android.view.View
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import kotlin.reflect.KClass

/**
 * A set of default [LeakInspector]s that knows about common AOSP and library
 * classes.
 *
 * These are heuristics based on our experience and knownledge of AOSP and various library
 * internals. We only make a reachability decision if we're reasonably sure such reachability is
 * unlikely to be the result of a programmer mistake.
 *
 * For example, no matter how many mistakes we make in our code, the value of Activity.mDestroy
 * will not be influenced by those mistakes.
 */
enum class AndroidLeakInspectors : LeakInspector {

  VIEW {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason =
      (parser to node).instanceOfOrUnknown(View::class) { instance ->
        // This skips edge cases like Toast$TN.mNextView holding on to an unattached and unparented
        // next toast view
        if (instance["mParent"].reference == null) {
          LeakNodeStatus.unknown()
        } else if (!instance.hasField("mAttachInfo")) {
          LeakNodeStatus.unknown()
        } else if (instance["mAttachInfo"].reference == null) {
          LeakNodeStatus.leaking("View detached and has parent")
        } else {
          LeakNodeStatus.notLeaking("View attached")
        }
      }
  },

  ACTIVITY {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason =
      (parser to node).instanceOfOrUnknown(Activity::class) { instance ->
        instance.leakingWhenTrue("mDestroyed")
      }
  },

  DIALOG {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason =
      (parser to node).instanceOfOrUnknown(Dialog::class) { instance ->
        instance.leakingWhenNull("mDecor")
      }
  },

  APPLICATION {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason = with(parser) {
      return if (node.instance.objectRecord.isInstanceOf(Application::class)) {
        LeakNodeStatus.notLeaking("Application is a singleton")
      } else LeakNodeStatus.unknown()
    }
  },

  CLASSLOADER {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason = with(parser) {
      return if (node.instance.objectRecord.isInstanceOf(ClassLoader::class)) {
        LeakNodeStatus.notLeaking("Classloader never leaking")
      } else LeakNodeStatus.unknown()
    }
  },

  CLASS {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason = with(parser) {
      return if (node.instance.objectRecord is ClassDumpRecord) {
        LeakNodeStatus.notLeaking("a class is never leaking")
      } else LeakNodeStatus.unknown()
    }
  },

  @Suppress("DEPRECATION")
  FRAGMENT {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason =
      (parser to node).instanceOfOrUnknown(Fragment::class) { instance ->
        instance.leakingWhenNull("mFragmentManager")
      }
  },

  SUPPORT_FRAGMENT {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason =
      (parser to node).instanceOfOrUnknown("android.support.v4.app.Fragment") { instance ->
        instance.leakingWhenNull("mFragmentManager")
      }
  },

  MESSAGE_QUEUE {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason =
      (parser to node).instanceOfOrUnknown(MessageQueue::class) { instance ->
        // If the queue is not quitting, maybe it should actually have been, we don't know.
        // However, if it's quitting, it is very likely that's not a bug.
        when (instance["mQuitting"].boolean) {
          true -> LeakNodeStatus.leaking("MessageQueue#mQuitting is true")
          else -> LeakNodeStatus.unknown()
        }
      }
  },

  MORTAR_PRESENTER {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason =
      (parser to node).instanceOfOrUnknown("mortar.Presenter") { instance ->
        // Bugs in view code tends to cause Mortar presenters to still have a view when they actually
        // should be unreachable, so in that case we don't know their reachability status. However,
        // when the view is null, we're pretty sure they  never leaking.
        if (instance["view"].isNullReference) {
          LeakNodeStatus.leaking("Presenter#view is null")
        } else {
          LeakNodeStatus.unknown()
        }
      }
  },

  MAIN_THEAD {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason =
      (parser to node).instanceOfOrUnknown(Thread::class) { instance ->
        if (instance["name"].reference.stringOrNull == "main") {
          LeakNodeStatus.notLeaking("the main thread always runs")
        } else {
          LeakNodeStatus.unknown()
        }
      }
  },

  WINDOW {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason =
      (parser to node).instanceOfOrUnknown("android.view.Window") { instance ->
        instance.leakingWhenTrue("mDestroyed")
      }
  },

  TOAST_TN {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): LeakNodeStatusAndReason = with(parser) {
      return if (node.instance.objectRecord.isInstanceOf("android.widget.Toast\$TN")) {
        LeakNodeStatus.notLeaking("Toast.TN (Transient Notification) is never leaking")
      } else LeakNodeStatus.unknown()
    }
  };

  companion object {

    fun defaultAndroidInspectors(): List<LeakInspector> {
      val inspectors = mutableListOf<LeakInspector>()
      for (enumValue in values()) {
        inspectors.add(enumValue)
      }
      return inspectors
    }
  }
}

inline fun Pair<HprofParser, LeakNode>.instanceOfOrUnknown(
  expectedClass: KClass<out Any>,
  block: HprofParser.(HydratedInstance) -> LeakNodeStatusAndReason
): LeakNodeStatusAndReason = instanceOfOrUnknown(expectedClass.java.name, block)

inline fun Pair<HprofParser, LeakNode>.instanceOfOrUnknown(
  className: String,
  block: HprofParser.(HydratedInstance) -> LeakNodeStatusAndReason
): LeakNodeStatusAndReason =
  this.instanceOf(className)?.let { first.block(it) } ?: LeakNodeStatus.unknown()

fun Pair<HprofParser, LeakNode>.instanceOf(className: String): HydratedInstance? = with(first) {
  val record = second.instance.objectRecord
  if (!record.isInstanceOf(className)) {
    null
  } else {
    hydrateInstance(record as InstanceDumpRecord)
  }
}

fun HydratedInstance.leakingWhenNull(
  fieldName: String
): LeakNodeStatusAndReason {
  val className = classHierarchy[0].simpleClassName
  if (!hasField(fieldName)) {
    return LeakNodeStatus.unknown()
  }
  val value = this[fieldName].reference

  return if (value == null) {
    LeakNodeStatus.leaking(
        "$className#$fieldName is null"
    )
  } else {
    LeakNodeStatus.notLeaking(
        "$className#$fieldName is not null"
    )
  }
}

fun HydratedInstance.leakingWhenTrue(
  fieldName: String
): LeakNodeStatusAndReason {
  val className = classHierarchy[0].simpleClassName

  return when (this[fieldName].boolean) {
    null -> LeakNodeStatus.unknown()
    true -> LeakNodeStatus.leaking(
        "$className#$fieldName is true"
    )
    false -> LeakNodeStatus.notLeaking(
        "$className#$fieldName is false"
    )
  }
}