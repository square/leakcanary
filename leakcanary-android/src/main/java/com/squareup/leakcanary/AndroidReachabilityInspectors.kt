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
package com.squareup.leakcanary

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Fragment
import android.os.MessageQueue
import android.view.View
import java.util.ArrayList

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
enum class AndroidReachabilityInspectors(private val inspectorClass: Class<out Reachability.Inspector>) {

  VIEW(ViewInspector::class.java),

  ACTIVITY(ActivityInspector::class.java),

  DIALOG(DialogInspector::class.java),

  APPLICATION(ApplicationInspector::class.java),

  FRAGMENT(FragmentInspector::class.java),

  SUPPORT_FRAGMENT(SupportFragmentInspector::class.java),

  MESSAGE_QUEUE(MessageQueueInspector::class.java),

  MORTAR_PRESENTER(MortarPresenterInspector::class.java),

  VIEW_ROOT_IMPL(ViewImplInspector::class.java),

  MAIN_THEAD(MainThreadInspector::class.java),

  WINDOW(WindowInspector::class.java);

  class ViewInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      if (!element.isInstanceOf(View::class.java)) {
        return Reachability.unknown()
      }
      return unreachableWhen(element, View::class.java.name, "mAttachInfo", "null")
    }
  }

  class ActivityInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      return unreachableWhen(element, Activity::class.java.name, "mDestroyed", "true")
    }
  }

  class DialogInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      return unreachableWhen(element, Dialog::class.java.name, "mDecor", "null")
    }
  }

  class ApplicationInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      return if (element.isInstanceOf(Application::class.java)) {
        Reachability.reachable("the application class is a singleton")
      } else Reachability.unknown()
    }
  }

  class FragmentInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      return unreachableWhen(element, Fragment::class.java.name, "mDetached", "true")
    }
  }

  class SupportFragmentInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      return unreachableWhen(element, "android.support.v4.app.Fragment", "mDetached", "true")
    }
  }

  class MessageQueueInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      if (!element.isInstanceOf(MessageQueue::class.java)) {
        return Reachability.unknown()
      }
      val mQuitting = element.getFieldReferenceValue("mQuitting")
      // If the queue is not quitting, maybe it should actually have been, we don't know.
      // However, if it's quitting, it is very likely that's not a bug.
      return if ("true" == mQuitting) {
        Reachability.unreachable("MessageQueue#mQuitting is true")
      } else Reachability.unknown()
    }
  }

  class MortarPresenterInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      if (!element.isInstanceOf("mortar.Presenter")) {
        return Reachability.unknown()
      }
      val view = element.getFieldReferenceValue("view")

      // Bugs in view code tends to cause Mortar presenters to still have a view when they actually
      // should be a unreachable, so in that case we don't know their reachability status. However,
      // when the view is null, we're pretty sure they should be unreachable.
      return if ("null" == view) {
        Reachability.unreachable("Presenter#view is null")
      } else Reachability.unknown()
    }
  }

  class ViewImplInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      return unreachableWhen(element, "android.view.ViewRootImpl", "mView", "null")
    }
  }

  class MainThreadInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      if (!element.isInstanceOf(Thread::class.java)) {
        return Reachability.unknown()
      }
      val name = element.getFieldReferenceValue("name")
      return if ("main" == name) {
        Reachability.reachable("the main thread always runs")
      } else Reachability.unknown()
    }
  }

  class WindowInspector : Reachability.Inspector {
    override fun expectedReachability(element: LeakTraceElement): Reachability {
      return unreachableWhen(element, "android.view.Window", "mDestroyed", "true")
    }
  }

  companion object {

    fun defaultAndroidInspectors(): List<Class<out Reachability.Inspector>> {
      val inspectorClasses = ArrayList<Class<out Reachability.Inspector>>()
      for (enumValue in AndroidReachabilityInspectors.values()) {
        inspectorClasses.add(enumValue.inspectorClass)
      }
      return inspectorClasses
    }

    private fun unreachableWhen(
      element: LeakTraceElement,
      className: String,
      fieldName: String,
      unreachableValue: String
    ): Reachability {
      if (!element.isInstanceOf(className)) {
        return Reachability.unknown()
      }
      val fieldValue = element.getFieldReferenceValue(fieldName) ?: return Reachability.unknown()
      return if (fieldValue == unreachableValue) {
        Reachability.unreachable(
            simpleClassName(className) + "#" + fieldName + " is " + unreachableValue
        )
      } else {
        Reachability.reachable(
            simpleClassName(className) + "#" + fieldName + " is not " + unreachableValue
        )
      }
    }

    private fun simpleClassName(className: String): String {
      val separator = className.lastIndexOf('.')
      return if (separator == -1) {
        className
      } else {
        className.substring(separator + 1)
      }
    }
  }

}
