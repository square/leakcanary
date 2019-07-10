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
enum class AndroidLeakTraceInspectors : LeakTraceInspector {

  VIEW {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.view.View") { instance ->
        // This skips edge cases like Toast$TN.mNextView holding on to an unattached and unparented
        // next toast view
        val mParentRef = instance["android.view.View", "mParent"]!!.value
        val mParentSet = mParentRef.isNonNullReference
        val viewDetached = instance["android.view.View", "mAttachInfo"]!!.value.isNullReference

        if (mParentSet) {
          if (viewDetached) {
            reportLeaking("View detached and has parent")
          } else {
            val viewParent = mParentRef.asObject!!.asInstance!!
            if (viewParent instanceOf "android.view.View" &&
                viewParent["android.view.View", "mAttachInfo"]!!.value.isNullReference) {
              reportLeaking("View attached but parent detached (attach disorder)")
            } else {
              reportNotLeaking("View attached")
            }
          }
        }

        if (mParentSet) {
          addLabel("View#mParent is set")
        } else {
          addLabel("View#mParent is null")
        }

        if (viewDetached) {
          addLabel("View#mAttachInfo is null (view detached)")
        } else {
          addLabel("View#mAttachInfo is not null (view attached)")
        }

        // TODO Add back support for view id labels, see https://github.com/square/leakcanary/issues/1297

        val mWindowAttachCount = instance["android.view.View", "mWindowAttachCount"]?.value?.asInt

        if (mWindowAttachCount != null) {
          addLabel("View.mWindowAttachCount=$mWindowAttachCount")
        }
      }
    }
  },

  ACTIVITY {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.app.Activity") { instance ->
        // Activity.mDestroyed was introduced in 17.
        // https://android.googlesource.com/platform/frameworks/base/+
        // /6d9dcbccec126d9b87ab6587e686e28b87e5a04d
        val field = instance["android.app.Activity", "mDestroyed"]

        if (field != null) {
          if (field.value.asBoolean!!) {
            reportLeaking(field describedWithValue "true")
          } else {
            reportNotLeaking(field describedWithValue "false")
          }
        }
      }
    }
  },

  CONTEXT_WRAPPER {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.content.ContextWrapper") { instance ->
        // Activity is already taken care of
        if (!(instance instanceOf "android.app.Activity")) {
          var context = instance

          val visitedInstances = mutableListOf<Long>()
          var keepUnwrapping = true
          while (keepUnwrapping) {
            visitedInstances += context.objectId
            keepUnwrapping = false
            val mBase = context["android.content.ContextWrapper", "mBase"]!!.value

            if (mBase.isNonNullReference) {
              context = mBase.asObject!!.asInstance!!
              if (context instanceOf "android.app.Activity") {
                val mDestroyed = instance["android.app.Activity", "mDestroyed"]
                if (mDestroyed != null) {
                  if (mDestroyed.value.asBoolean!!) {
                    reportLeaking(
                        "${instance.classSimpleName} wraps an Activity with Activity.mDestroyed true"
                    )
                  } else {
                    // We can't assume it's not leaking, because this context might have a shorter lifecycle
                    // than the activity. So we'll just add a label.
                    addLabel("${instance.classSimpleName} wraps an Activity with Activity.mDestroyed false")
                  }
                }
              } else if (context instanceOf "android.content.ContextWrapper" &&
                  // Avoids infinite loops
                  context.objectId !in visitedInstances
              ) {
                keepUnwrapping = true
              }
            }
          }
        }
      }
    }
  },

  DIALOG {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.app.Dialog") { instance ->
        val mDecor = instance["android.app.Dialog", "mDecor"]!!
        if (mDecor.value.isNullReference) {
          reportLeaking(mDecor describedWithValue "null")
        } else {
          reportNotLeaking(mDecor describedWithValue "not null")
        }
      }
    }
  },

  APPLICATION {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.app.Application") {
        reportNotLeaking("Application is a singleton")
      }
    }
  },

  INPUT_METHOD_MANAGER {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.view.inputmethod.InputMethodManager") {
        reportNotLeaking("InputMethodManager is a singleton")
      }
    }
  },

  CLASSLOADER {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf(ClassLoader::class) {
        reportNotLeaking("A ClassLoader is never leaking")
      }
    }
  },

  CLASS {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEach { reporter ->
        if (reporter.objectRecord is GraphClassRecord) {
          reporter.reportNotLeaking("a class is never leaking")
        }
      }
    }
  },

  FRAGMENT {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.app.Fragment") { instance ->
        val fragmentManager = instance["android.app.Fragment", "mFragmentManager"]!!
        if (fragmentManager.value.isNullReference) {
          reportLeaking(fragmentManager describedWithValue "null")
        } else {
          reportNotLeaking(fragmentManager describedWithValue "not null")
        }
        val mTag = instance["android.app.Fragment", "mTag"]?.value?.readAsJavaString()
        if (!mTag.isNullOrEmpty()) {
          addLabel("Fragment.mTag=$mTag")
        }
      }
    }
  },

  SUPPORT_FRAGMENT {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.support.v4.app.Fragment") { instance ->
        val fragmentManager = instance["android.support.v4.app.Fragment", "mFragmentManager"]!!
        if (fragmentManager.value.isNullReference) {
          reportLeaking(fragmentManager describedWithValue "null")
        } else {
          reportNotLeaking(fragmentManager describedWithValue "not null")
        }
        val mTag = instance["android.support.v4.app.Fragment", "mTag"]?.value?.readAsJavaString()
        if (!mTag.isNullOrEmpty()) {
          addLabel("Fragment.mTag=$mTag")
        }
      }
    }
  },

  ANDROIDX_FRAGMENT {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("androidx.fragment.app.Fragment") { instance ->
        val fragmentManager = instance["androidx.fragment.app.Fragment", "mFragmentManager"]!!
        if (fragmentManager.value.isNullReference) {
          reportLeaking(fragmentManager describedWithValue "null")
        } else {
          reportNotLeaking(fragmentManager describedWithValue "not null")
        }
        val mTag = instance["androidx.fragment.app.Fragment", "mTag"]?.value?.readAsJavaString()
        if (!mTag.isNullOrEmpty()) {
          addLabel("Fragment.mTag=$mTag")
        }
      }
    }
  },

  MESSAGE_QUEUE {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.os.MessageQueue") { instance ->
        val mQuitting = instance["android.os.MessageQueue", "mQuitting"]!!
        if (mQuitting.value.asBoolean!!) {
          reportLeaking(mQuitting describedWithValue "true")
        } else {
          reportNotLeaking(mQuitting describedWithValue "false")
        }
      }
    }
  },

  MORTAR_PRESENTER {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("mortar.Presenter") { instance ->
        // Bugs in view code tends to cause Mortar presenters to still have a view when they actually
        // should be unreachable, so in that case we don't know their reachability status. However,
        // when the view is null, we're pretty sure they  never leaking.
        val view = instance["mortar.Presenter", "view"]!!
        if (view.value.isNullReference) {
          reportLeaking(view describedWithValue "null")
        }
      }
    }
  },

  COORDINATOR {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("com.squareup.coordinators.Coordinator") { instance ->
        val attached = instance["com.squareup.coordinators.Coordinator", "attached"]
        if (attached!!.value.asBoolean!!) {
          reportNotLeaking(attached describedWithValue "true")
        } else {
          reportLeaking(attached describedWithValue "false")
        }
      }
    }
  },

  ANONYMOUS_CLASS {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEach { reporter ->
        if (reporter.objectRecord is GraphInstanceRecord) {
          val classRecord = reporter.objectRecord.instanceClass
          if (classRecord.name.matches(HeapAnalyzer.ANONYMOUS_CLASS_NAME_PATTERN_REGEX)) {
            val parentClassRecord = classRecord.superClass!!
            if (parentClassRecord.name == "java.lang.Object") {
              try {
                // This is an anonymous class implementing an interface. The API does not give access
                // to the interfaces implemented by the class. We check if it's in the class path and
                // use that instead.
                val actualClass = Class.forName(classRecord.name)
                val interfaces = actualClass.interfaces
                reporter.addLabel(
                    if (interfaces.isNotEmpty()) {
                      val implementedInterface = interfaces[0]
                      "Anonymous class implementing ${implementedInterface.name}"
                    } else {
                      "Anonymous subclass of java.lang.Object"
                    }
                )
              } catch (ignored: ClassNotFoundException) {
              }
            } else {
              // Makes it easier to figure out which anonymous class we're looking at.
              reporter.addLabel("Anonymous subclass of ${parentClassRecord.name}")
            }
          }
        }
      }
    }
  },

  THREAD {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf(Thread::class) { instance ->
        val threadName = instance[Thread::class, "name"]!!.value.readAsJavaString()
        if (threadName == "main") {
          reportNotLeaking("the main thread always runs")
        }
        addLabel("Thread name: '$threadName'")
      }
    }
  },

  WINDOW {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.view.Window") { instance ->
        val mDestroyed = instance["android.view.Window", "mDestroyed"]!!

        if (mDestroyed.value.asBoolean!!) {
          reportLeaking(mDestroyed describedWithValue "true")
        } else {
          reportNotLeaking(mDestroyed describedWithValue "false")
        }
      }
    }
  },

  TOAST {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.widget.Toast") { instance ->
        val tnInstance =
          instance["android.widget.Toast", "mTN"]!!.value.asObject!!.asInstance!!
        // mWM is set in android.widget.Toast.TN#handleShow and never unset, so this toast was never
        // shown, we don't know if it's leaking.
        if (tnInstance["android.widget.Toast\$TN", "mWM"]!!.value.isNonNullReference) {
          // mView is reset to null in android.widget.Toast.TN#handleHide
          if (tnInstance["android.widget.Toast\$TN", "mView"]!!.value.isNullReference) {
            reportLeaking(
                "This toast is done showing (Toast.mTN.mWM != null && Toast.mTN.mView == null)"
            )
          } else {
            reportNotLeaking(
                "This toast is showing (Toast.mTN.mWM != null && Toast.mTN.mView != null)"
            )
          }
        }
      }
    }
  },

  TOAST_TN {
    override fun inspect(
      graph: HprofGraph,
      leakTrace: List<LeakTraceElementReporter>
    ) {
      leakTrace.forEachInstanceOf("android.widget.Toast\$TN") {
        reportNotLeaking("Toast.TN (Transient Notification) is never leaking")
      }
    }
  };

  companion object {
    fun defaultInspectors(): List<LeakTraceInspector> {
      return values().toList()
    }
  }
}

private infix fun GraphField.describedWithValue(valueDescription: String): String {
  return "${classRecord.simpleName}#$name is $valueDescription"
}

inline fun List<LeakTraceElementReporter>.forEachInstanceOf(
  expectedClass: KClass<out Any>,
  action: LeakTraceElementReporter.(GraphInstanceRecord) -> Unit
) {
  forEachInstanceOf(expectedClass.java.name, action)
}

inline fun List<LeakTraceElementReporter>.forEachInstanceOf(
  className: String,
  action: LeakTraceElementReporter.(GraphInstanceRecord) -> Unit
) {
  for (reporter in this) {
    if (reporter.objectRecord is GraphInstanceRecord && reporter.objectRecord instanceOf className) {
      reporter.action(reporter.objectRecord)
    }
  }
}