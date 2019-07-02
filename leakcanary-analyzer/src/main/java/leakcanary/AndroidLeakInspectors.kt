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

import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import kotlin.reflect.KClass

/**
 * A set of default [LeakInspector]s that knows about common AOSP and library
 * classes.
 *
 * These are heuristics based on our experience and knowledge of AOSP and various library
 * internals. We only make a decision if we're reasonably sure the state of an object is
 * unlikely to be the result of a programmer mistake.
 *
 * For example, no matter how many mistakes we make in our code, the value of Activity.mDestroy
 * will not be influenced by those mistakes.
 */
enum class AndroidLeakInspectors : LeakInspector {
  //GraphObjectRecord
  VIEW {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason =
      record.instanceOfOrUnknown(
          "android.view.View"
      ) { instance ->
        when {
          // This skips edge cases like Toast$TN.mNextView holding on to an unattached and unparented
          // next toast view
          instance["mParent"]!!.value.isNullReference -> LeakNodeStatus.unknown()
          instance["mAttachInfo"]!!.value.isNullReference -> LeakNodeStatus.leaking(
              "View detached and has parent"
          )
          else -> LeakNodeStatus.notLeaking("View attached")
        }
      }
  },

  ACTIVITY {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason =
      record.instanceOfOrUnknown(
          "android.app.Activity"
      ) { instance ->
        val field = instance["mDestroyed"]
        // Activity.mDestroyed was introduced in 17.
        // https://android.googlesource.com/platform/frameworks/base/+
        // /6d9dcbccec126d9b87ab6587e686e28b87e5a04d
        if (field == null) {
          return@instanceOfOrUnknown LeakNodeStatus.unknown()
        }

        if (field.value.asBoolean!!) {
          LeakNodeStatus.leaking(field describedWithValue "true")
        } else {
          LeakNodeStatus.notLeaking(field describedWithValue "false")
        }
      }
  },

  DIALOG {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason =
      record.instanceOfOrUnknown(
          "android.app.Dialog"
      ) { instance ->
        val field = instance["mDecor"]!!
        if (field.value.isNullReference) {
          LeakNodeStatus.leaking(field describedWithValue "null")
        } else {
          LeakNodeStatus.notLeaking(field describedWithValue "not null")
        }
      }
  },

  APPLICATION {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason {
      return if (record.asInstance?.instanceOf("android.app.Application") == true) {
        LeakNodeStatus.notLeaking("Application is a singleton")
      } else {
        LeakNodeStatus.unknown()
      }
    }
  },

  CLASSLOADER {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason {
      return if (record.asInstance?.instanceOf(ClassLoader::class) == true) {
        LeakNodeStatus.notLeaking("A ClassLoader is never leaking")
      } else {
        LeakNodeStatus.unknown()
      }
    }
  },

  CLASS {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason {
      return if (record is GraphClassRecord) {
        LeakNodeStatus.notLeaking("a class is never leaking")
      } else LeakNodeStatus.unknown()
    }
  },

  @Suppress("DEPRECATION")
  FRAGMENT {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason =
      record.instanceOfOrUnknown(
          "android.app.Fragment"
      ) { instance ->
        val field = instance["mFragmentManager"]!!
        if (field.value.isNullReference) {
          LeakNodeStatus.leaking(field describedWithValue "null")
        } else {
          LeakNodeStatus.notLeaking(field describedWithValue "not null")
        }
      }
  },

  SUPPORT_FRAGMENT {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason =
      record.instanceOfOrUnknown(
          "android.support.v4.app.Fragment"
      ) { instance ->
        val field = instance["mFragmentManager"]!!
        if (field.value.isNullReference) {
          LeakNodeStatus.leaking(field describedWithValue "null")
        } else {
          LeakNodeStatus.notLeaking(field describedWithValue "not null")
        }
      }
  },

  MESSAGE_QUEUE {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason =
      record.instanceOfOrUnknown(
          "android.os.MessageQueue"
      ) { instance ->
        // If the queue is not quitting, maybe it should actually have been, we don't know.
        // However, if it's quitting, it is very likely that's not a bug.
        val field = instance["mQuitting"]!!
        if (field.value.asBoolean == true) {
          LeakNodeStatus.leaking(field describedWithValue "true")
        } else {
          LeakNodeStatus.unknown()
        }
      }
  },

  MORTAR_PRESENTER {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason =
      record.instanceOfOrUnknown(
          "mortar.Presenter"
      ) { instance ->
        // Bugs in view code tends to cause Mortar presenters to still have a view when they actually
        // should be unreachable, so in that case we don't know their reachability status. However,
        // when the view is null, we're pretty sure they  never leaking.
        val field = instance["view"]!!
        if (field.value.isNullReference) {
          LeakNodeStatus.leaking(field describedWithValue "null")
        } else {
          LeakNodeStatus.unknown()
        }
      }
  },

  MAIN_THEAD {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason =
      record.instanceOfOrUnknown(
          Thread::class
      ) { instance ->
        if (instance["name"]!!.value.readAsJavaString() == "main") {
          LeakNodeStatus.notLeaking("the main thread always runs")
        } else {
          LeakNodeStatus.unknown()
        }
      }
  },

  WINDOW {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason =
      record.instanceOfOrUnknown(
          "android.view.Window"
      ) { instance ->
        val field = instance["mDestroyed"]!!

        if (field.value.asBoolean!!) {
          LeakNodeStatus.leaking(field describedWithValue "true")
        } else {
          LeakNodeStatus.notLeaking(field describedWithValue "false")
        }
      }
  },

  TOAST_TN {
    override fun invoke(
      record: GraphObjectRecord
    ): LeakNodeStatusAndReason {
      return if (record.asInstance?.instanceOf(
              "android.widget.Toast\$TN"
          ) == true
      ) {
        LeakNodeStatus.notLeaking(
            "Toast.TN (Transient Notification) is never leaking"
        )
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

fun GraphObjectRecord.instanceOfOrUnknown(
  expectedClass: KClass<out Any>,
  block: (GraphInstanceRecord) -> LeakNodeStatusAndReason
): LeakNodeStatusAndReason = instanceOfOrUnknown(expectedClass.java.name, block)

fun GraphObjectRecord.instanceOfOrUnknown(
  expectedClass: String,
  block: (GraphInstanceRecord) -> LeakNodeStatusAndReason
): LeakNodeStatusAndReason {
  val record = this.asInstance
  return if (record != null && record instanceOf expectedClass) {
    block(record)
  } else LeakNodeStatus.unknown()
}

private infix fun GraphField.describedWithValue(valueDescription: String): String {
  return "${classRecord.simpleName}#$name is $valueDescription"
}