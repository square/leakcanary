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
import shark.FilteringLeakingObjectFinder.LeakingObjectFilter
import shark.HeapObject.HeapInstance
import java.util.EnumSet
import kotlin.math.absoluteValue

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
    override val leakingObjectFilter = { heapObject: HeapObject ->
      if (heapObject is HeapInstance && heapObject instanceOf "android.view.View") {
        val mContext = heapObject["android.view.View", "mContext"]!!.value.asObject!!.asInstance!!
        val activityContext = mContext.unwrapActivityContext()
        val mContextIsDestroyedActivity = (activityContext != null &&
            activityContext["android.app.Activity", "mDestroyed"]?.value?.asBoolean == true)
        if (mContextIsDestroyedActivity) {
          true
        } else {
          val viewDetached = heapObject["android.view.View", "mAttachInfo"]!!.value.isNullReference
          if (viewDetached) {
            val mWindowAttachCount =
              heapObject["android.view.View", "mWindowAttachCount"]?.value!!.asInt!!
            if (mWindowAttachCount > 0) {
              var rootParent = heapObject["android.view.View", "mParent"]!!.valueAsInstance
              var rootView: HeapInstance? = null
              while (rootParent != null && rootParent instanceOf "android.view.View") {
                rootView = rootParent
                rootParent = rootParent["android.view.View", "mParent"]!!.valueAsInstance
              }
              val partOfWindowHierarchy = rootParent != null || (rootView != null &&
                  rootView.instanceClassName == "com.android.internal.policy.DecorView")
              partOfWindowHierarchy
            } else false
          } else false
        }
      } else false
    }

    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("android.view.View") { instance ->
        // This skips edge cases like Toast$TN.mNextView holding on to an unattached and unparented
        // next toast view
        var rootParent = instance["android.view.View", "mParent"]!!.valueAsInstance
        var rootView: HeapInstance? = null
        while (rootParent != null && rootParent instanceOf "android.view.View") {
          rootView = rootParent
          rootParent = rootParent["android.view.View", "mParent"]!!.valueAsInstance
        }

        val partOfWindowHierarchy = rootParent != null || (rootView != null &&
            rootView.instanceClassName == "com.android.internal.policy.DecorView")

        val mWindowAttachCount =
          instance["android.view.View", "mWindowAttachCount"]?.value!!.asInt!!
        val viewDetached = instance["android.view.View", "mAttachInfo"]!!.value.isNullReference
        val mContext = instance["android.view.View", "mContext"]!!.value.asObject!!.asInstance!!

        val activityContext = mContext.unwrapActivityContext()
        if (activityContext != null && activityContext["android.app.Activity", "mDestroyed"]?.value?.asBoolean == true) {
          leakingReasons += "View.mContext references a destroyed activity"
        } else {
          if (partOfWindowHierarchy && mWindowAttachCount > 0) {
            if (viewDetached) {
              leakingReasons += "View detached yet still part of window view hierarchy"
            } else {
              if (rootView != null && rootView["android.view.View", "mAttachInfo"]!!.value.isNullReference) {
                leakingReasons += "View attached but root view ${rootView.instanceClassName} detached (attach disorder)"
              } else {
                notLeakingReasons += "View attached"
              }
            }
          }
        }

        labels += if (partOfWindowHierarchy) {
          "View is part of a window view hierarchy"
        } else {
          "View not part of a window view hierarchy"
        }

        labels += if (viewDetached) {
          "View.mAttachInfo is null (view detached)"
        } else {
          "View.mAttachInfo is not null (view attached)"
        }

        AndroidResourceIdNames.readFromHeap(instance.graph)
            ?.let { resIds ->
              val mID = instance["android.view.View", "mID"]!!.value.asInt!!
              val noViewId = -1
              if (mID != noViewId) {
                val resourceName = resIds[mID]
                labels += "View.mID = R.id.$resourceName"
              }
            }
        labels += "View.mWindowAttachCount = $mWindowAttachCount"
      }
    }
  },

  EDITOR {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "android.widget.Editor" &&
          heapObject["android.widget.Editor", "mTextView"]?.value?.asObject?.let { textView ->
            VIEW.leakingObjectFilter!!(textView)
          } ?: false
    }

    override fun inspect(reporter: ObjectReporter) {
      reporter.whenInstanceOf("android.widget.Editor") { instance ->
        applyFromField(VIEW, instance["android.widget.Editor", "mTextView"])
      }
    }
  },

  ACTIVITY {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "android.app.Activity" &&
          heapObject["android.app.Activity", "mDestroyed"]?.value?.asBoolean == true
    }

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

  CONTEXT_FIELD {
    override fun inspect(reporter: ObjectReporter) {
      val instance = reporter.heapObject
      if (instance !is HeapInstance) {
        return
      }
      instance.readFields().forEach { field ->
        val fieldInstance = field.valueAsInstance
        if (fieldInstance != null && fieldInstance instanceOf "android.content.Context") {
          reporter.run {
            val componentContext = fieldInstance.unwrapComponentContext()
            labels += if (componentContext == null) {
              "${field.name} instance of ${fieldInstance.instanceClassName}"
            } else if (componentContext instanceOf "android.app.Activity") {
              val activityDescription =
                "with mDestroyed = " + (componentContext["android.app.Activity", "mDestroyed"]?.value?.asBoolean?.toString()
                    ?: "UNKNOWN")
              if (componentContext == fieldInstance) {
                "${field.name} instance of ${fieldInstance.instanceClassName} $activityDescription"
              } else {
                "${field.name} instance of ${fieldInstance.instanceClassName}, " +
                    "wrapping activity ${componentContext.instanceClassName} $activityDescription"
              }
            } else if (componentContext == fieldInstance) {
              // No need to add "instance of Application / Service", devs know their own classes.
              "${field.name} instance of ${fieldInstance.instanceClassName}"
            } else {
              "${field.name} instance of ${fieldInstance.instanceClassName}, wrapping ${componentContext.instanceClassName}"
            }
          }
        }
      }
    }
  },

  CONTEXT_WRAPPER {

    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject.unwrapActivityContext()
              ?.get("android.app.Activity", "mDestroyed")?.value?.asBoolean == true
    }

    override fun inspect(
      reporter: ObjectReporter
    ) {
      val instance = reporter.heapObject
      if (instance !is HeapInstance) {
        return
      }

      // We're looking for ContextWrapper instances that are not Activity, Application or Service.
      // So we stop whenever we find any of those 4 classes, and then only keep ContextWrapper.
      val matchingClassName = instance.instanceClass.classHierarchy.map { it.name }
          .firstOrNull {
            when (it) {
              "android.content.ContextWrapper",
              "android.app.Activity",
              "android.app.Application",
              "android.app.Service"
              -> true
              else -> false
            }
          }

      if (matchingClassName == "android.content.ContextWrapper") {
        reporter.run {
          val componentContext = instance.unwrapComponentContext()
          if (componentContext != null) {
            if (componentContext instanceOf "android.app.Activity") {
              val mDestroyed = componentContext["android.app.Activity", "mDestroyed"]
              if (mDestroyed != null) {
                if (mDestroyed.value.asBoolean!!) {
                  leakingReasons += "${instance.instanceClassSimpleName} wraps an Activity with Activity.mDestroyed true"
                } else {
                  // We can't assume it's not leaking, because this context might have a shorter lifecycle
                  // than the activity. So we'll just add a label.
                  labels += "${instance.instanceClassSimpleName} wraps an Activity with Activity.mDestroyed false"
                }
              }
            } else if (componentContext instanceOf "android.app.Application") {
              labels += "${instance.instanceClassSimpleName} wraps an Application context"
            } else {
              labels += "${instance.instanceClassSimpleName} wraps a Service context"
            }
          } else {
            labels += "${instance.instanceClassSimpleName} does not wrap a known Android context"
          }
        }
      }
    }
  },

  APPLICATION_PACKAGE_MANAGER {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "android.app.ApplicationContextManager" &&
          heapObject["android.app.ApplicationContextManager", "mContext"]!!
              .valueAsInstance!!.outerContextIsLeaking()
    }

    override fun inspect(reporter: ObjectReporter) {
      reporter.whenInstanceOf("android.app.ApplicationContextManager") { instance ->
        val outerContext = instance["android.app.ApplicationContextManager", "mContext"]!!
            .valueAsInstance!!["android.app.ContextImpl", "mOuterContext"]!!
            .valueAsInstance!!
        inspectContextImplOuterContext(outerContext, instance, "ApplicationContextManager.mContext")
      }
    }
  },

  CONTEXT_IMPL {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "android.app.ContextImpl" &&
          heapObject.outerContextIsLeaking()
    }

    override fun inspect(reporter: ObjectReporter) {
      reporter.whenInstanceOf("android.app.ContextImpl") { instance ->
        val outerContext = instance["android.app.ContextImpl", "mOuterContext"]!!
            .valueAsInstance!!
        inspectContextImplOuterContext(outerContext, instance)
      }
    }
  },

  DIALOG {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "android.app.Dialog" &&
          heapObject["android.app.Dialog", "mDecor"]!!.value.isNullReference
    }

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
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "android.app.Fragment" &&
          heapObject["android.app.Fragment", "mFragmentManager"]!!.value.isNullReference
    }

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

    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf ANDROID_SUPPORT_FRAGMENT_CLASS_NAME &&
          heapObject.getOrThrow(
              ANDROID_SUPPORT_FRAGMENT_CLASS_NAME, "mFragmentManager"
          ).value.isNullReference
    }

    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf(ANDROID_SUPPORT_FRAGMENT_CLASS_NAME) { instance ->
        val fragmentManager =
          instance.getOrThrow(ANDROID_SUPPORT_FRAGMENT_CLASS_NAME, "mFragmentManager")
        if (fragmentManager.value.isNullReference) {
          leakingReasons += fragmentManager describedWithValue "null"
        } else {
          notLeakingReasons += fragmentManager describedWithValue "not null"
        }
        val mTag = instance[ANDROID_SUPPORT_FRAGMENT_CLASS_NAME, "mTag"]?.value?.readAsJavaString()
        if (!mTag.isNullOrEmpty()) {
          labels += "Fragment.mTag=$mTag"
        }
      }
    }
  },

  ANDROIDX_FRAGMENT {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "androidx.fragment.app.Fragment" &&
          heapObject.getOrThrow(
              "androidx.fragment.app.Fragment", "mFragmentManager"
          ).value.isNullReference
    }

    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("androidx.fragment.app.Fragment") { instance ->
        val fragmentManager =
          instance.getOrThrow("androidx.fragment.app.Fragment", "mFragmentManager")
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
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "android.os.MessageQueue" &&
          (heapObject["android.os.MessageQueue", "mQuitting"]
              ?: heapObject["android.os.MessageQueue", "mQuiting"]!!).value.asBoolean!!
    }

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

        val queueHead = instance["android.os.MessageQueue", "mMessages"]!!.valueAsInstance
        if (queueHead != null) {
          val targetHandler = queueHead["android.os.Message", "target"]!!.valueAsInstance
          if (targetHandler != null) {
            val looper = targetHandler["android.os.Handler", "mLooper"]!!.valueAsInstance
            if (looper != null) {
              val thread = looper["android.os.Looper", "mThread"]!!.valueAsInstance!!
              val threadName = thread[Thread::class, "name"]!!.value.readAsJavaString()
              labels += "HandlerThread: \"$threadName\""
            }
          }
        }
      }
    }
  },

  MORTAR_PRESENTER {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "mortar.Presenter" &&
          heapObject.getOrThrow("mortar.Presenter", "view").value.isNullReference
    }

    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("mortar.Presenter") { instance ->
        // Bugs in view code tends to cause Mortar presenters to still have a view when they actually
        // should be unreachable, so in that case we don't know their reachability status. However,
        // when the view is null, we're pretty sure they  never leaking.
        val view = instance.getOrThrow("mortar.Presenter", "view")
        if (view.value.isNullReference) {
          leakingReasons += view describedWithValue "null"
        } else {
          labels += view describedWithValue "set"
        }
      }
    }
  },

  MORTAR_SCOPE {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "mortar.MortarScope" &&
          heapObject.getOrThrow("mortar.MortarScope", "dead").value.asBoolean!!
    }

    override fun inspect(reporter: ObjectReporter) {
      reporter.whenInstanceOf("mortar.MortarScope") { instance ->
        val dead = instance.getOrThrow("mortar.MortarScope", "dead").value.asBoolean!!
        val scopeName = instance.getOrThrow("mortar.MortarScope", "name").value.readAsJavaString()
        if (dead) {
          leakingReasons += "mortar.MortarScope.dead is true for scope $scopeName"
        } else {
          notLeakingReasons += "mortar.MortarScope.dead is false for scope $scopeName"
        }
      }
    }
  },

  COORDINATOR {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "com.squareup.coordinators.Coordinator" &&
          !heapObject.getOrThrow(
              "com.squareup.coordinators.Coordinator", "attached"
          ).value.asBoolean!!
    }

    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf("com.squareup.coordinators.Coordinator") { instance ->
        val attached = instance.getOrThrow("com.squareup.coordinators.Coordinator", "attached")
        if (attached.value.asBoolean!!) {
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
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "android.view.ViewRootImpl" &&
          heapObject["android.view.ViewRootImpl", "mView"]!!.value.isNullReference
    }

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
    override val leakingObjectFilter = { heapObject: HeapObject ->
      heapObject is HeapInstance &&
          heapObject instanceOf "android.view.Window" &&
          heapObject["android.view.Window", "mDestroyed"]!!.value.asBoolean!!
    }

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

  MESSAGE {
    override fun inspect(reporter: ObjectReporter) {
      reporter.whenInstanceOf("android.os.Message") { instance ->
        labels += "Message.what = ${instance["android.os.Message", "what"]!!.value.asInt}"

        val heapDumpUptimeMillis = KeyedWeakReferenceFinder.heapDumpUptimeMillis(instance.graph)
        val whenUptimeMillis = instance["android.os.Message", "when"]!!.value.asLong!!

        labels += if (heapDumpUptimeMillis != null) {
          val diffMs = whenUptimeMillis - heapDumpUptimeMillis
          if (diffMs > 0) {
            "Message.when = $whenUptimeMillis ($diffMs ms after heap dump)"
          } else {
            "Message.when = $whenUptimeMillis (${diffMs.absoluteValue} ms before heap dump)"
          }
        } else {
          "Message.when = $whenUptimeMillis"
        }

        labels += "Message.obj = ${instance["android.os.Message", "obj"]!!.value.asObject}"
        labels += "Message.callback = ${instance["android.os.Message", "callback"]!!.value.asObject}"
      }
    }
  },

  TOAST {
    override val leakingObjectFilter = { heapObject: HeapObject ->
      if (heapObject is HeapInstance && heapObject instanceOf "android.widget.Toast") {
        val tnInstance =
          heapObject["android.widget.Toast", "mTN"]!!.value.asObject!!.asInstance!!
        (tnInstance["android.widget.Toast\$TN", "mWM"]!!.value.isNonNullReference &&
            tnInstance["android.widget.Toast\$TN", "mView"]!!.value.isNullReference)
      } else false
    }

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

  internal open val leakingObjectFilter: ((heapObject: HeapObject) -> Boolean)? = null

  companion object {
    /** @see AndroidObjectInspectors */
    val appDefaults: List<ObjectInspector>
      get() = ObjectInspectors.jdkDefaults + values()

    /**
     * Returns a list of [LeakingObjectFilter] suitable for apps.
     */
    val appLeakingObjectFilters: List<LeakingObjectFilter> =
      ObjectInspectors.jdkLeakingObjectFilters +
          createLeakingObjectFilters(EnumSet.allOf(AndroidObjectInspectors::class.java))

    /**
     * Creates a list of [LeakingObjectFilter] based on the passed in [AndroidObjectInspectors].
     */
    fun createLeakingObjectFilters(inspectors: Set<AndroidObjectInspectors>): List<LeakingObjectFilter> =
      inspectors.mapNotNull { it.leakingObjectFilter }
          .map { filter ->
            object : LeakingObjectFilter {
              override fun isLeakingObject(heapObject: HeapObject) = filter(heapObject)
            }
          }
  }

  // Using a string builder to prevent Jetifier from changing this string to Android X Fragment
  @Suppress("VariableNaming", "PropertyName")
  internal val ANDROID_SUPPORT_FRAGMENT_CLASS_NAME =
    StringBuilder("android.").append("support.v4.app.Fragment")
        .toString()
}

private fun HeapInstance.outerContextIsLeaking() =
  this["android.app.ContextImpl", "mOuterContext"]!!
      .valueAsInstance!!
      .run {
        this instanceOf "android.app.Activity" &&
            this["android.app.Activity", "mDestroyed"]?.value?.asBoolean == true
      }

private fun ObjectReporter.inspectContextImplOuterContext(
  outerContext: HeapInstance,
  contextImpl: HeapInstance,
  prefix: String = "ContextImpl"
) {
  if (outerContext instanceOf "android.app.Activity") {
    val mDestroyed = outerContext["android.app.Activity", "mDestroyed"]?.value?.asBoolean
    if (mDestroyed != null) {
      if (mDestroyed) {
        leakingReasons += "$prefix.mOuterContext is an instance of" +
            " ${outerContext.instanceClassName} with Activity.mDestroyed true"
      } else {
        notLeakingReasons += "$prefix.mOuterContext is an instance of " +
            "${outerContext.instanceClassName} with Activity.mDestroyed false"
      }
    } else {
      labels += "$prefix.mOuterContext is an instance of ${outerContext.instanceClassName}"
    }
  } else if (outerContext instanceOf "android.app.Application") {
    notLeakingReasons += "$prefix.mOuterContext is an instance of" +
        " ${outerContext.instanceClassName} which extends android.app.Application"
  } else if (outerContext.objectId == contextImpl.objectId) {
    labels += "$prefix.mOuterContext == ContextImpl.this: not tied to any particular lifecycle"
  } else {
    labels += "$prefix.mOuterContext is an instance of ${outerContext.instanceClassName}"
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
  notLeakingReasons += delegateReporter.notLeakingReasons.map { "$prefix $it" }
}

/**
 * Recursively unwraps `this` [HeapInstance] as a ContextWrapper until an Activity is found in which case it is
 * returned. Returns null if no activity was found.
 */
internal fun HeapInstance.unwrapActivityContext(): HeapInstance? {
  return unwrapComponentContext().let { context ->
    if (context != null && context instanceOf "android.app.Activity") {
      context
    } else {
      null
    }
  }
}

/**
 * Recursively unwraps `this` [HeapInstance] as a ContextWrapper until an known Android component
 * context is found in which case it is returned. Returns null if no activity was found.
 */
@Suppress("NestedBlockDepth", "ReturnCount")
internal fun HeapInstance.unwrapComponentContext(): HeapInstance? {
  val matchingClassName = instanceClass.classHierarchy.map { it.name }
      .firstOrNull {
        when (it) {
          "android.content.ContextWrapper",
          "android.app.Activity",
          "android.app.Application",
          "android.app.Service"
          -> true
          else -> false
        }
      }
      ?: return null

  if (matchingClassName != "android.content.ContextWrapper") {
    return this
  }

  var context = this
  val visitedInstances = mutableListOf<Long>()
  var keepUnwrapping = true
  while (keepUnwrapping) {
    visitedInstances += context.objectId
    keepUnwrapping = false
    val mBase = context["android.content.ContextWrapper", "mBase"]!!.value

    if (mBase.isNonNullReference) {
      val wrapperContext = context
      context = mBase.asObject!!.asInstance!!

      val contextMatchingClassName = context.instanceClass.classHierarchy.map { it.name }
          .firstOrNull {
            when (it) {
              "android.content.ContextWrapper",
              "android.app.Activity",
              "android.app.Application",
              "android.app.Service"
              -> true
              else -> false
            }
          }

      var isContextWrapper = contextMatchingClassName == "android.content.ContextWrapper"

      if (contextMatchingClassName == "android.app.Activity") {
        return context
      } else {
        if (wrapperContext instanceOf "com.android.internal.policy.DecorContext") {
          // mBase isn't an activity, let's unwrap DecorContext.mPhoneWindow.mContext instead
          val mPhoneWindowField =
            wrapperContext["com.android.internal.policy.DecorContext", "mPhoneWindow"]
          if (mPhoneWindowField != null) {
            val phoneWindow = mPhoneWindowField.valueAsInstance!!
            context = phoneWindow["android.view.Window", "mContext"]!!.valueAsInstance!!
            if (context instanceOf "android.app.Activity") {
              return context
            }
            isContextWrapper = context instanceOf "android.content.ContextWrapper"
          }
        }
        if (contextMatchingClassName == "android.app.Service" ||
            contextMatchingClassName == "android.app.Application"
        ) {
          return context
        }
        if (isContextWrapper &&
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

/**
 * Same as [HeapInstance.readField] but throws if the field doesnt exist
 */
internal fun HeapInstance.getOrThrow(
  declaringClassName: String,
  fieldName: String
): HeapField {
  return this[declaringClassName, fieldName] ?: throw IllegalStateException(
      """
$instanceClassName is expected to have a $declaringClassName.$fieldName field which cannot be found. 
This might be due to the app code being obfuscated. If that's the case, then the heap analysis 
is unable to proceed without a mapping file to deobfuscate class names. 
You can run LeakCanary on obfuscated builds by following the instructions at 
https://square.github.io/leakcanary/recipes/#using-leakcanary-with-obfuscated-apps
      """
  )
}