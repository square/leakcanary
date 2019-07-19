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
import leakcanary.internal.KeyedWeakReferenceMirror
import kotlin.reflect.KClass

/**
 * A set of default [ObjectInspector]s that knows about common AOSP and library
 * classes.
 *
 * These are heuristics based on our experience and knowledge of AOSP and various library
 * internals. We only make a decision if we're reasonably sure the state of an object is
 * unlikely to be the result of a programmer mistake.
 *
 * For example, no matter how many mistakes we make in our code, the value of Activity.mDestroy
 * will not be influenced by those mistakes.
 *
 * Most developers should use the entire set of default [ObjectInspector] by calling [appDefaults],
 * unless there's a bug and you temporarily want to remove an inspector.
 */
enum class AndroidObjectInspectors : ObjectInspector {

  KEYED_WEAK_REFERENCE {
    override fun inspect(
      graph: HprofGraph,
      reporter: ObjectReporter
    ) {
      val references: List<KeyedWeakReferenceMirror> =
        graph.context.getOrPut(KEYED_WEAK_REFERENCE.name) {
          val keyedWeakReferenceClass = graph.findClassByClassName("leakcanary.KeyedWeakReference")

          val heapDumpUptimeMillis = if (keyedWeakReferenceClass == null) {
            null
          } else {
            keyedWeakReferenceClass["heapDumpUptimeMillis"]?.value?.asLong
          }

          if (heapDumpUptimeMillis == null) {
            CanaryLog.d(
                "${KeyedWeakReference::class.java.name}.heapDumpUptimeMillis field not found, " +
                    "this must be a heap dump from an older version of LeakCanary."
            )
          }

          val addedToContext: List<KeyedWeakReferenceMirror> = graph.instances
              .filter { instance ->
                val className = instance.className
                className == "leakcanary.KeyedWeakReference" || className == "com.squareup.leakcanary.KeyedWeakReference"
              }
              .map { KeyedWeakReferenceMirror.fromInstance(it, heapDumpUptimeMillis) }
              .filter { it.hasReferent }
              .toList()
          graph.context[KEYED_WEAK_REFERENCE.name] = addedToContext
          addedToContext
        }

      val objectId = reporter.objectRecord.objectId
      references.forEach { ref ->
        if (ref.referent.value == objectId) {
          reporter.reportLeaking("ObjectWatcher was watching this")
          reporter.addLabel("key = ${ref.key}")
          if (ref.name.isNotEmpty()) {
            reporter.addLabel("name = ${ref.name}")
          }
          if (ref.watchDurationMillis != null) {
            reporter.addLabel("watchDurationMillis = ${ref.watchDurationMillis}")
          }
          if (ref.retainedDurationMillis != null) {
            reporter.addLabel("retainedDurationMillis = ${ref.retainedDurationMillis}")
          }
        }
      }
    }
  },

  VIEW {
    override fun inspect(
      graph: HprofGraph,
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.view.View") { instance ->
        // This skips edge cases like Toast$TN.mNextView holding on to an unattached and unparented
        // next toast view
        val mParentRef = instance["android.view.View", "mParent"]!!.value
        val mParentSet = mParentRef.isNonNullReference
        val mWindowAttachCount =
          instance["android.view.View", "mWindowAttachCount"]?.value!!.asInt!!
        val viewDetached = instance["android.view.View", "mAttachInfo"]!!.value.isNullReference
        val mContext = instance["android.view.View", "mContext"]!!.value.asObject!!.asInstance!!

        val activityContext = mContext.unwrapActivityContext()
        if (activityContext == null) {
          addLabel("mContext instance of ${mContext.className}, not wrapping activity")
        } else {
          val activityDescription =
            " with mDestroyed = " + (activityContext["android.app.Activity", "mDestroyed"]?.value?.asBoolean?.toString()
                ?: "UNKNOWN")
          if (activityContext == mContext) {
            addLabel("mContext instance of ${activityContext.className} $activityDescription")
          } else {
            addLabel(
                "mContext instance of ${mContext.className}, wrapping activity ${activityContext.className} $activityDescription"
            )
          }
        }

        addLabel("mContext = ${mContext.className}")

        if (activityContext != null && activityContext["android.app.Activity", "mDestroyed"]?.value?.asBoolean == true) {
          reportLeaking("View.mContext references a destroyed activity")
        } else {
          if (mParentSet && mWindowAttachCount > 0) {
            if (viewDetached) {
              reportLikelyLeaking("View detached and has parent")
            } else {
              val viewParent = mParentRef.asObject!!.asInstance!!
              if (viewParent instanceOf "android.view.View" &&
                  viewParent["android.view.View", "mAttachInfo"]!!.value.isNullReference
              ) {
                reportLikelyLeaking("View attached but parent detached (attach disorder)")
              } else {
                reportNotLeaking("View attached")
              }
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

        addLabel("View.mWindowAttachCount = $mWindowAttachCount")
      }
    }
  },

  ACTIVITY {
    override fun inspect(
      graph: HprofGraph,
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.app.Activity") { instance ->
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.content.ContextWrapper") { instance ->
        // Activity is already taken care of
        if (!(instance instanceOf "android.app.Activity")) {
          val activityContext = instance.unwrapActivityContext()
          if (activityContext != null) {
            val mDestroyed = instance["android.app.Activity", "mDestroyed"]
            if (mDestroyed != null) {
              if (mDestroyed.value.asBoolean!!) {
                reportLeaking(
                    "${instance.classSimpleName} wraps an Activity with Activity.mDestroyed true"
                )
              } else {
                // We can't assume it's not leaking, because this context might have a shorter lifecycle
                // than the activity. So we'll just add a label.
                addLabel(
                    "${instance.classSimpleName} wraps an Activity with Activity.mDestroyed false"
                )
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.app.Dialog") { instance ->
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.app.Application") {
        reportNotLeaking("Application is a singleton")
      }
    }
  },

  INPUT_METHOD_MANAGER {
    override fun inspect(
      graph: HprofGraph,
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.view.inputmethod.InputMethodManager") {
        reportNotLeaking("InputMethodManager is a singleton")
      }
    }
  },

  CLASSLOADER {
    override fun inspect(
      graph: HprofGraph,
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf(ClassLoader::class) {
        reportNotLeaking("A ClassLoader is never leaking")
      }
    }
  },

  CLASS {
    override fun inspect(
      graph: HprofGraph,
      reporter: ObjectReporter
    ) {
      if (reporter.objectRecord is GraphClassRecord) {
        reporter.reportNotLeaking("a class is never leaking")
      }
    }
  },

  FRAGMENT {
    override fun inspect(
      graph: HprofGraph,
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.app.Fragment") { instance ->
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.support.v4.app.Fragment") { instance ->
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("androidx.fragment.app.Fragment") { instance ->
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.os.MessageQueue") { instance ->
        // mQuiting had a typo and was renamed to mQuitting
        // https://android.googlesource.com/platform/frameworks/base/+/013cf847bcfd2828d34dced60adf2d3dd98021dc
        val mQuitting = instance["android.os.MessageQueue", "mQuitting"]
            ?: instance["android.os.MessageQueue", "mQuiting"]!!
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("mortar.Presenter") { instance ->
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("com.squareup.coordinators.Coordinator") { instance ->
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
      reporter: ObjectReporter
    ) {
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
  },

  THREAD {
    override fun inspect(
      graph: HprofGraph,
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf(Thread::class) { instance ->
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.view.Window") { instance ->
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
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.widget.Toast") { instance ->
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
  };

  companion object {
    /** @see AndroidObjectInspectors */
    val appDefaults: List<ObjectInspector>
      get() {
        return values().toList()
      }
  }
}

private infix fun GraphField.describedWithValue(valueDescription: String): String {
  return "${classRecord.simpleName}#$name is $valueDescription"
}

/**
 * Runs [block] if [ObjectReporter.objectRecord] is an instance of [expectedClass].
 */
inline fun ObjectReporter.whenInstanceOf(
  expectedClass: KClass<out Any>,
  action: ObjectReporter.(GraphInstanceRecord) -> Unit
) {
  whenInstanceOf(expectedClass.java.name, action)
}

/**
 * Runs [block] if [ObjectReporter.objectRecord] is an instance of [expectedClassName].
 */
inline fun ObjectReporter.whenInstanceOf(
  expectedClassName: String,
  block: ObjectReporter.(GraphInstanceRecord) -> Unit
) {
  if (objectRecord is GraphInstanceRecord && objectRecord instanceOf expectedClassName) {
    block(objectRecord)
  }
}

/**
 * Recursively unwraps `this` [GraphInstanceRecord] as a ContextWrapper until an Activity is found in which case it is
 * returned. Returns null if no activity was found.
 */
fun GraphInstanceRecord.unwrapActivityContext(): GraphInstanceRecord? {
  if (this instanceOf "android.app.Activity") {
    return this
  }
  if (this instanceOf "android.content.ContextWrapper") {
    var context = this
    val visitedInstances = mutableListOf<Long>()
    var keepUnwrapping = true
    while (keepUnwrapping) {
      visitedInstances += context.objectId
      keepUnwrapping = false
      val mBase = context["android.content.ContextWrapper", "mBase"]!!.value

      if (mBase.isNonNullReference) {
        context = mBase.asObject!!.asInstance!!
        if (context instanceOf "android.app.Activity") {
          return context
        } else if (context instanceOf "android.content.ContextWrapper" &&
            // Avoids infinite loops
            context.objectId !in visitedInstances
        ) {
          keepUnwrapping = true
        }
      }
    }
  }
  return null
}