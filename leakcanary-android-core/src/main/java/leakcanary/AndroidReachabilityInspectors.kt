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
 * A set of default [Reachability.Inspector]s that knows about common AOSP and library
 * classes.
 *
 * These are heuristics based on our experience and knownledge of AOSP and various library
 * internals. We only make a reachability decision if we're reasonably sure such reachability is
 * unlikely to be the result of a programmer mistake.
 *
 * For example, no matter how many mistakes we make in our code, the value of Activity.mDestroy
 * will not be influenced by those mistakes.
 */
enum class AndroidReachabilityInspectors : Reachability.Inspector {

  VIEW {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = (parser to node).instanceOfOrUnknown(View::class) { instance ->
      // This skips edge cases like Toast$TN.mNextView holding on to an unattached and unparented
      // next toast view
      if (instance["mParent"].reference == null) {
        Reachability.unknown()
      } else if (!instance.hasField("mAttachInfo")) {
        Reachability.unknown()
      } else if (instance["mAttachInfo"].reference == null) {
        Reachability.unreachable("View#mAttachInfo is null")
      } else {
        Reachability.reachable("View#mAttachInfo is not null")
      }
    }
  },

  ACTIVITY {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = (parser to node).instanceOfOrUnknown(Activity::class) { instance ->
      instance.unreachableWhenTrue("mDestroyed")
    }
  },

  DIALOG {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = (parser to node).instanceOfOrUnknown(Dialog::class) { instance ->
      instance.unreachableWhenNull("mDecor")
    }
  },

  APPLICATION {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = with(parser) {
      return if (node.instance.objectRecord.isInstanceOf(Application::class)) {
        Reachability.reachable("Application is a singleton")
      } else Reachability.unknown()
    }
  },

  CLASSLOADER {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = with(parser) {
      return if (node.instance.objectRecord.isInstanceOf(ClassLoader::class)) {
        Reachability.reachable("Classloader always reachable")
      } else Reachability.unknown()
    }
  },

  CLASS {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = with(parser) {
      return if (node.instance.objectRecord is ClassDumpRecord) {
        Reachability.reachable("a class is always reachable")
      } else Reachability.unknown()
    }
  },

  @Suppress("DEPRECATION")
  FRAGMENT {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = (parser to node).instanceOfOrUnknown(Fragment::class) { instance ->
      instance.unreachableWhenNull("mFragmentManager")
    }
  },

  SUPPORT_FRAGMENT {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability =
      (parser to node).instanceOfOrUnknown("android.support.v4.app.Fragment") { instance ->
        instance.unreachableWhenNull("mFragmentManager")
      }
  },

  MESSAGE_QUEUE {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = (parser to node).instanceOfOrUnknown(MessageQueue::class) { instance ->
      // If the queue is not quitting, maybe it should actually have been, we don't know.
      // However, if it's quitting, it is very likely that's not a bug.
      when (instance["mQuitting"].boolean) {
        true -> Reachability.unreachable("MessageQueue#mQuitting is true")
        else -> Reachability.unknown()
      }
    }
  },

  MORTAR_PRESENTER {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = (parser to node).instanceOfOrUnknown("mortar.Presenter") { instance ->
      // Bugs in view code tends to cause Mortar presenters to still have a view when they actually
      // should be a unreachable, so in that case we don't know their reachability status. However,
      // when the view is null, we're pretty sure they should be unreachable.
      if (instance["view"].isNullReference) {
        Reachability.unreachable("Presenter#view is null")
      } else {
        Reachability.unknown()
      }
    }
  },

  MAIN_THEAD {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = (parser to node).instanceOfOrUnknown(Thread::class) { instance ->
      if (instance["name"].reference.stringOrNull == "main") {
        Reachability.reachable("the main thread always runs")
      } else {
        Reachability.unknown()
      }
    }
  },

  WINDOW {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = (parser to node).instanceOfOrUnknown("android.view.Window") { instance ->
      instance.unreachableWhenTrue("mDestroyed")
    }
  },

  TOAST_TN {
    override fun expectedReachability(
      parser: HprofParser,
      node: LeakNode
    ): Reachability = with(parser) {
      return if (node.instance.objectRecord.isInstanceOf("android.widget.Toast\$TN")) {
        Reachability.reachable("Toast.TN (Transient Notification) is always reachable")
      } else Reachability.unknown()
    }
  };

  companion object {

    fun defaultAndroidInspectors(): List<Reachability.Inspector> {
      val inspectorClasses = mutableListOf<Reachability.Inspector>()
      for (enumValue in values()) {
        inspectorClasses.add(enumValue)
      }
      return inspectorClasses
    }
  }
}

inline fun Pair<HprofParser, LeakNode>.instanceOfOrUnknown(
  expectedClass: KClass<out Any>,
  block: HprofParser.(HydratedInstance) -> Reachability
): Reachability = instanceOfOrUnknown(expectedClass.java.name, block)

inline fun Pair<HprofParser, LeakNode>.instanceOfOrUnknown(
  className: String,
  block: HprofParser.(HydratedInstance) -> Reachability
): Reachability = this.instanceOf(className)?.let { first.block(it) } ?: Reachability.unknown()

fun Pair<HprofParser, LeakNode>.instanceOf(className: String): HydratedInstance? = with(first) {
  val record = second.instance.objectRecord
  if (!record.isInstanceOf(className)) {
    null
  } else {
    hydrateInstance(record as InstanceDumpRecord)
  }
}

fun HydratedInstance.unreachableWhenNull(
  fieldName: String
): Reachability {
  val className = classHierarchy[0].simpleClassName
  if (!hasField(fieldName)) {
    return Reachability.unknown()
  }
  val value = this[fieldName].reference

  return if (value == null) {
    Reachability.unreachable(
        "$className#$fieldName is null"
    )
  } else {
    Reachability.reachable(
        "$className#$fieldName is not null"
    )
  }
}

fun HydratedInstance.unreachableWhenTrue(
  fieldName: String
): Reachability {
  val className = classHierarchy[0].simpleClassName

  return when (this[fieldName].boolean) {
    null -> Reachability.unknown()
    true -> Reachability.unreachable(
        "$className#$fieldName is true"
    )
    false -> Reachability.reachable(
        "$className#$fieldName is false"
    )
  }
}