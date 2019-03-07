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
package com.squareup.leakcanary;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Fragment;
import android.os.MessageQueue;
import android.support.annotation.NonNull;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * A set of default {@link Reachability.Inspector}s that knows about common AOSP and library
 * classes.
 *
 * These are heuristics based on our experience and knownledge of AOSP and various library
 * internals. We only make a reachability decision if we're reasonably sure such reachability is
 * unlikely to be the result of a programmer mistake.
 *
 * For example, no matter how many mistakes we make in our code, the value of Activity.mDestroy
 * will not be influenced by those mistakes.
 */
public enum AndroidReachabilityInspectors {

  VIEW(ViewInspector.class),

  ACTIVITY(ActivityInspector.class),

  DIALOG(DialogInspector.class),

  APPLICATION(ApplicationInspector.class),

  FRAGMENT(FragmentInspector.class),

  SUPPORT_FRAGMENT(SupportFragmentInspector.class),

  MESSAGE_QUEUE(MessageQueueInspector.class),

  MORTAR_PRESENTER(MortarPresenterInspector.class),

  VIEW_ROOT_IMPL(ViewImplInspector.class),

  MAIN_THEAD(MainThreadInspector.class),

  WINDOW(WindowInspector.class),

  //
  ;

  private final Class<? extends Reachability.Inspector> inspectorClass;

  AndroidReachabilityInspectors(Class<? extends Reachability.Inspector> inspectorClass) {
    this.inspectorClass = inspectorClass;
  }

  public static @NonNull List<Class<? extends Reachability.Inspector>> defaultAndroidInspectors() {
    List<Class<? extends Reachability.Inspector>> inspectorClasses = new ArrayList<>();
    for (AndroidReachabilityInspectors enumValue : AndroidReachabilityInspectors.values()) {
      inspectorClasses.add(enumValue.inspectorClass);
    }
    return inspectorClasses;
  }

  public static class ViewInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf(View.class)) {
        return Reachability.unknown();
      }
      String mAttachInfo = element.getFieldReferenceValue("mAttachInfo");
      if (mAttachInfo == null) {
        return Reachability.unknown();
      }
      return unreachableWhen(element, View.class.getName(), "mAttachInfo", "null");
    }
  }

  public static class ActivityInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      return unreachableWhen(element, Activity.class.getName(), "mDestroyed", "true");
    }
  }

  public static class DialogInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      return unreachableWhen(element, Dialog.class.getName(), "mDecor", "null");
    }
  }

  public static class ApplicationInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (element.isInstanceOf(Application.class)) {
        return Reachability.reachable("the application class is a singleton");
      }
      return Reachability.unknown();
    }
  }

  public static class FragmentInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      return unreachableWhen(element, Fragment.class.getName(), "mDetached", "true");
    }
  }

  public static class SupportFragmentInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      return unreachableWhen(element, "android.support.v4.app.Fragment", "mDetached", "true");
    }
  }

  public static class MessageQueueInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf(MessageQueue.class)) {
        return Reachability.unknown();
      }
      String mQuitting = element.getFieldReferenceValue("mQuitting");
      // If the queue is not quitting, maybe it should actually have been, we don't know.
      // However, if it's quitting, it is very likely that's not a bug.
      if ("true".equals(mQuitting)) {
        return Reachability.unreachable("MessageQueue#mQuitting is true");
      }
      return Reachability.unknown();
    }
  }

  public static class MortarPresenterInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf("mortar.Presenter")) {
        return Reachability.unknown();
      }
      String view = element.getFieldReferenceValue("view");

      // Bugs in view code tends to cause Mortar presenters to still have a view when they actually
      // should be a unreachable, so in that case we don't know their reachability status. However,
      // when the view is null, we're pretty sure they should be unreachable.
      if ("null".equals(view)) {
        return Reachability.unreachable("Presenter#view is null");
      }
      return Reachability.unknown();
    }
  }

  public static class ViewImplInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      return unreachableWhen(element, "android.view.ViewRootImpl", "mView", "null");
    }
  }

  public static class MainThreadInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf(Thread.class)) {
        return Reachability.unknown();
      }
      String name = element.getFieldReferenceValue("name");
      if ("main".equals(name)) {
        return Reachability.reachable("the main thread always runs");
      }
      return Reachability.unknown();
    }
  }

  public static class WindowInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      return unreachableWhen(element, "android.view.Window", "mDestroyed", "true");
    }
  }

  private static Reachability unreachableWhen(LeakTraceElement element, String className,
      String fieldName,
      String unreachableValue) {
    if (!element.isInstanceOf(className)) {
      return Reachability.unknown();
    }
    String fieldValue = element.getFieldReferenceValue(fieldName);
    if (fieldValue == null) {
      return Reachability.unknown();
    }
    if (fieldValue.equals(unreachableValue)) {
      return Reachability.unreachable(
          simpleClassName(className) + "#" + fieldName + " is " + unreachableValue);
    } else {
      return Reachability.reachable(
          simpleClassName(className) + "#" + fieldName + " is not " + unreachableValue);
    }
  }

  private static String simpleClassName(String className) {
    int separator = className.lastIndexOf('.');
    if (separator == -1) {
      return className;
    } else {
      return className.substring(separator + 1);
    }
  }

}
