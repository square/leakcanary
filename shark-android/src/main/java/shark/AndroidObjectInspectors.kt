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
package shark

import shark.AndroidObjectInspectors.Companion.appDefaults
import shark.HeapObject.HeapInstance

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

  VIEW {
    override fun inspect(
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
        labels += if (activityContext == null) {
          "mContext instance of ${mContext.instanceClassName}, not wrapping activity"
        } else {
          val activityDescription =
            "with mDestroyed = " + (activityContext["android.app.Activity", "mDestroyed"]?.value?.asBoolean?.toString()
                ?: "UNKNOWN")
          if (activityContext == mContext) {
            "mContext instance of ${activityContext.instanceClassName} $activityDescription"
          } else {
            "mContext instance of ${mContext.instanceClassName}, wrapping activity ${activityContext.instanceClassName} $activityDescription"
          }
        }
        if (activityContext != null && activityContext["android.app.Activity", "mDestroyed"]?.value?.asBoolean == true) {
          leakingReasons += "View.mContext references a destroyed activity"
        } else {
          if (mParentSet && mWindowAttachCount > 0) {
            if (viewDetached) {
              likelyLeakingReasons += "View detached and has parent"
            } else {
              val viewParent = mParentRef.asObject!!.asInstance!!
              if (viewParent instanceOf "android.view.View") {
                if (viewParent["android.view.View", "mAttachInfo"]!!.value.isNullReference) {
                  likelyLeakingReasons += "View attached but parent ${viewParent.instanceClassName} detached (attach disorder)"
                } else {
                  notLeakingReasons += "View attached"
                  labels += "View.parent ${viewParent.instanceClassName} attached as well"
                }
              } else {
                notLeakingReasons += "View attached"
                labels += "Parent ${viewParent.instanceClassName} not a android.view.View"
              }
            }
          }
        }

        if (mParentSet) {
          labels += "View#mParent is set"
        } else {
          labels += "View#mParent is null"
        }

        if (viewDetached) {
          labels += "View#mAttachInfo is null (view detached)"
        } else {
          labels += "View#mAttachInfo is not null (view attached)"
        }

        // TODO Add back support for view id labels, see https://github.com/square/leakcanary/issues/1297

        labels += "View.mWindowAttachCount = $mWindowAttachCount"
      }
    }
  },

  EDITOR {
    override fun inspect(reporter: ObjectReporter) {
      reporter.whenInstanceOf("android.widget.Editor") { instance ->
        applyFromField(VIEW, instance["android.widget.Editor", "mTextView"])
      }
    }
  },

  ACTIVITY {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.app.Activity") { instance ->
        // Activity.mDestroyed was introduced in 17.
        // https://android.googlesource.com/platform/frameworks/base/+
        // /6d9dcbccec126d9b87ab6587e686e28b87e5a04d
        val field = instance["android.app.Activity", "mDestroyed"]

        if (field != null) {
          if (field.value.asBoolean!!) {
            leakingReasons += field describedWithValue "true"
          } else {
            notLeakingReasons += field describedWithValue "false"
          }
        }
      }
    }
  },

  CONTEXT_WRAPPER {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.content.ContextWrapper") { instance ->
        // Activity is already taken care of
        if (!(instance instanceOf "android.app.Activity")) {
          val activityContext = instance.unwrapActivityContext()
          if (activityContext != null) {
            val mDestroyed = activityContext["android.app.Activity", "mDestroyed"]
            if (mDestroyed != null) {
              if (mDestroyed.value.asBoolean!!) {
                leakingReasons += "${instance.instanceClassSimpleName} wraps an Activity with Activity.mDestroyed true"
              } else {
                // We can't assume it's not leaking, because this context might have a shorter lifecycle
                // than the activity. So we'll just add a label.
                labels += "${instance.instanceClassSimpleName} wraps an Activity with Activity.mDestroyed false"
              }
            }
          } else {
            labels += "${instance.instanceClassSimpleName} does not wrap an activity context"
          }
        }
      }
    }
  },

  DIALOG {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.app.Dialog") { instance ->
        val mDecor = instance["android.app.Dialog", "mDecor"]!!
        if (mDecor.value.isNullReference) {
          leakingReasons += mDecor describedWithValue "null"
        } else {
          notLeakingReasons += mDecor describedWithValue "not null"
        }
      }
    }
  },

  APPLICATION {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.app.Application") {
        notLeakingReasons += "Application is a singleton"
      }
    }
  },

  INPUT_METHOD_MANAGER {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.view.inputmethod.InputMethodManager") {
        notLeakingReasons += "InputMethodManager is a singleton"
      }
    }
  },

  FRAGMENT {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.app.Fragment") { instance ->
        val fragmentManager = instance["android.app.Fragment", "mFragmentManager"]!!
        if (fragmentManager.value.isNullReference) {
          leakingReasons += fragmentManager describedWithValue "null"
        } else {
          notLeakingReasons += fragmentManager describedWithValue "not null"
        }
        val mTag = instance["android.app.Fragment", "mTag"]?.value?.readAsJavaString()
        if (!mTag.isNullOrEmpty()) {
          labels += "Fragment.mTag=$mTag"
        }
      }
    }
  },

  SUPPORT_FRAGMENT {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.support.v4.app.Fragment") { instance ->
        val fragmentManager = instance["android.support.v4.app.Fragment", "mFragmentManager"]!!
        if (fragmentManager.value.isNullReference) {
          leakingReasons += fragmentManager describedWithValue "null"
        } else {
          notLeakingReasons += fragmentManager describedWithValue "not null"
        }
        val mTag = instance["android.support.v4.app.Fragment", "mTag"]?.value?.readAsJavaString()
        if (!mTag.isNullOrEmpty()) {
          labels += "Fragment.mTag=$mTag"
        }
      }
    }
  },

  ANDROIDX_FRAGMENT {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("androidx.fragment.app.Fragment") { instance ->
        val fragmentManager = instance["androidx.fragment.app.Fragment", "mFragmentManager"]!!
        if (fragmentManager.value.isNullReference) {
          leakingReasons += fragmentManager describedWithValue "null"
        } else {
          notLeakingReasons += fragmentManager describedWithValue "not null"
        }
        val mTag = instance["androidx.fragment.app.Fragment", "mTag"]?.value?.readAsJavaString()
        if (!mTag.isNullOrEmpty()) {
          labels += "Fragment.mTag=$mTag"
        }
      }
    }
  },

  MESSAGE_QUEUE {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.os.MessageQueue") { instance ->
        // mQuiting had a typo and was renamed to mQuitting
        // https://android.googlesource.com/platform/frameworks/base/+/013cf847bcfd2828d34dced60adf2d3dd98021dc
        val mQuitting = instance["android.os.MessageQueue", "mQuitting"]
            ?: instance["android.os.MessageQueue", "mQuiting"]!!
        if (mQuitting.value.asBoolean!!) {
          leakingReasons += mQuitting describedWithValue "true"
        } else {
          notLeakingReasons += mQuitting describedWithValue "false"
        }
      }
    }
  },

  MORTAR_PRESENTER {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("mortar.Presenter") { instance ->
        // Bugs in view code tends to cause Mortar presenters to still have a view when they actually
        // should be unreachable, so in that case we don't know their reachability status. However,
        // when the view is null, we're pretty sure they  never leaking.
        val view = instance["mortar.Presenter", "view"]!!
        if (view.value.isNullReference) {
          leakingReasons += view describedWithValue "null"
        } else {
          labels += view describedWithValue "set"
        }
      }
    }
  },

  MORTAR_SCOPE {
    override fun inspect(reporter: ObjectReporter) {
      reporter.whenInstanceOf("mortar.MortarScope") { instance ->
        val dead = instance["mortar.MortarScope", "dead"]!!.value.asBoolean!!
        val scopeName = instance["mortar.MortarScope", "name"]!!.value.readAsJavaString()
        if (dead) {
          leakingReasons += "mortar.MortarScope.dead is true for scope $scopeName"
        } else {
          notLeakingReasons += "mortar.MortarScope.dead is false for scope $scopeName"
        }
      }
    }
  },

  COORDINATOR {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("com.squareup.coordinators.Coordinator") { instance ->
        val attached = instance["com.squareup.coordinators.Coordinator", "attached"]
        if (attached!!.value.asBoolean!!) {
          notLeakingReasons += attached describedWithValue "true"
        } else {
          leakingReasons += attached describedWithValue "false"
        }
      }
    }
  },

  MAIN_THREAD {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf(Thread::class) { instance ->
        val threadName = instance[Thread::class, "name"]!!.value.readAsJavaString()
        if (threadName == "main") {
          notLeakingReasons += "the main thread always runs"
        }
      }
    }
  },

  VIEW_ROOT_IMPL {
    override fun inspect(reporter: ObjectReporter) {
      reporter.whenInstanceOf("android.view.ViewRootImpl") { instance ->
        val mViewField = instance["android.view.ViewRootImpl", "mView"]!!
        if (mViewField.value.isNullReference) {
          leakingReasons += mViewField describedWithValue "null"
        } else {
          notLeakingReasons += mViewField describedWithValue "not null"
        }
      }
    }
  },

  WINDOW {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.view.Window") { instance ->
        val mDestroyed = instance["android.view.Window", "mDestroyed"]!!

        if (mDestroyed.value.asBoolean!!) {
          leakingReasons += mDestroyed describedWithValue "true"
        } else {
          notLeakingReasons += mDestroyed describedWithValue "false"
        }
      }
    }
  },

  TOAST {
    override fun inspect(
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
            leakingReasons += "This toast is done showing (Toast.mTN.mWM != null && Toast.mTN.mView == null)"
          } else {
            notLeakingReasons += "This toast is showing (Toast.mTN.mWM != null && Toast.mTN.mView != null)"
          }
        }
      }
    }
  };

  companion object {
    /** @see AndroidObjectInspectors */
    val appDefaults: List<ObjectInspector>
      get() {
        return values().toList() + ObjectInspectors.jdkDefaults
      }
  }
}

private infix fun HeapField.describedWithValue(valueDescription: String): String {
  return "${declaringClass.simpleName}#$name is $valueDescription"
}

private fun ObjectReporter.applyFromField(
  inspector: ObjectInspector,
  field: HeapField?
) {
  if (field == null) {
    return
  }
  if (field.value.isNullReference) {
    return
  }
  val heapObject = field.value.asObject!!
  val delegateReporter = ObjectReporter(heapObject)
  inspector.inspect(delegateReporter)
  val prefix = "${field.declaringClass.simpleName}#${field.name}:"

  labels += delegateReporter.labels.map { "$prefix $it" }
  leakingReasons += delegateReporter.leakingReasons.map { "$prefix $it" }
  likelyLeakingReasons += delegateReporter.likelyLeakingReasons.map { "$prefix $it" }
  notLeakingReasons += delegateReporter.notLeakingReasons.map { "$prefix $it" }
}

/**
 * Recursively unwraps `this` [HeapInstance] as a ContextWrapper until an Activity is found in which case it is
 * returned. Returns null if no activity was found.
 */
@Suppress("NestedBlockDepth")
fun HeapInstance.unwrapActivityContext(): HeapInstance? {
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
        var parentContext = context
        context = mBase.asObject!!.asInstance!!
        if (context instanceOf "android.app.Activity") {
          return context
        } else {
          if (parentContext instanceOf "com.android.internal.policy.DecorContext") {
            // mBase isn't an activity, let's unwrap DecorContext.mPhoneWindow.mContext instead
            val mPhoneWindowField =
              parentContext["com.android.internal.policy.DecorContext", "mPhoneWindow"]
            if (mPhoneWindowField != null) {
              val phoneWindow = mPhoneWindowField.valueAsInstance!!
              context = phoneWindow["android.view.Window", "mContext"]!!.valueAsInstance!!
              if (context instanceOf "android.app.Activity") {
                return context
              }
            }
          }
          if (context instanceOf "android.content.ContextWrapper" &&
              // Avoids infinite loops
              context.objectId !in visitedInstances
          ) {
            keepUnwrapping = true
          }
        }
      }
    }
  }
  return null
}