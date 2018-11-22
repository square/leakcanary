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
        return Reachability.UNKNOWN;
      }
      String mAttachInfo = element.getFieldReferenceValue("mAttachInfo");
      if (mAttachInfo == null) {
        return Reachability.UNKNOWN;
      }
      return mAttachInfo.equals("null") ? Reachability.UNREACHABLE : Reachability.REACHABLE;
    }
  }

  public static class ActivityInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf(Activity.class)) {
        return Reachability.UNKNOWN;
      }
      String mDestroyed = element.getFieldReferenceValue("mDestroyed");
      if (mDestroyed == null) {
        return Reachability.UNKNOWN;
      }
      return mDestroyed.equals("true") ? Reachability.UNREACHABLE : Reachability.REACHABLE;
    }
  }

  public static class DialogInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf(Dialog.class)) {
        return Reachability.UNKNOWN;
      }
      String mDecor = element.getFieldReferenceValue("mDecor");
      if (mDecor == null) {
        return Reachability.UNKNOWN;
      }
      return mDecor.equals("null") ? Reachability.UNREACHABLE : Reachability.REACHABLE;
    }
  }

  public static class ApplicationInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (element.isInstanceOf(Application.class)) {
        return Reachability.REACHABLE;
      }
      return Reachability.UNKNOWN;
    }
  }

  public static class FragmentInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf(Fragment.class)) {
        return Reachability.UNKNOWN;
      }
      String mDetached = element.getFieldReferenceValue("mDetached");
      if (mDetached == null) {
        return Reachability.UNKNOWN;
      }
      return mDetached.equals("true") ? Reachability.UNREACHABLE : Reachability.REACHABLE;
    }
  }

  public static class SupportFragmentInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf("android.support.v4.app.Fragment")) {
        return Reachability.UNKNOWN;
      }
      String mDetached = element.getFieldReferenceValue("mDetached");
      if (mDetached == null) {
        return Reachability.UNKNOWN;
      }
      return mDetached.equals("true") ? Reachability.UNREACHABLE : Reachability.REACHABLE;
    }
  }

  public static class MessageQueueInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf(MessageQueue.class)) {
        return Reachability.UNKNOWN;
      }
      String mQuitting = element.getFieldReferenceValue("mQuitting");
      // If the queue is not quitting, maybe it should actually have been, we don't know.
      // However, if it's quitting, it is very likely that's not a bug.
      if ("true".equals(mQuitting)) {
        return Reachability.UNREACHABLE;
      }
      return Reachability.UNKNOWN;
    }
  }

  public static class MortarPresenterInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf("mortar.Presenter")) {
        return Reachability.UNKNOWN;
      }
      String view = element.getFieldReferenceValue("view");

      // Bugs in view code tends to cause Mortar presenters to still have a view when they actually
      // should be a unreachable, so in that case we don't know their reachability status. However,
      // when the view is null, we're pretty sure they should be unreachable.
      if ("null".equals(view)) {
        return Reachability.UNREACHABLE;
      }
      return Reachability.UNKNOWN;
    }
  }

  public static class ViewImplInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf("android.view.ViewRootImpl")) {
        return Reachability.UNKNOWN;
      }
      String mView = element.getFieldReferenceValue("mView");
      if (mView == null) {
        return Reachability.UNKNOWN;
      }
      return mView.equals("null") ? Reachability.UNREACHABLE : Reachability.REACHABLE;
    }
  }

  public static class MainThreadInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf(Thread.class)) {
        return Reachability.UNKNOWN;
      }
      String name = element.getFieldReferenceValue("name");
      if ("main".equals(name)) {
        return Reachability.REACHABLE;
      }
      return Reachability.UNKNOWN;
    }
  }

  public static class WindowInspector implements Reachability.Inspector {
    @Override public @NonNull Reachability expectedReachability(@NonNull LeakTraceElement element) {
      if (!element.isInstanceOf("android.view.Window")) {
        return Reachability.UNKNOWN;
      }
      String mDestroyed = element.getFieldReferenceValue("mDestroyed");
      if (mDestroyed == null) {
        return Reachability.UNKNOWN;
      }
      return mDestroyed.equals("true") ? Reachability.UNREACHABLE : Reachability.REACHABLE;
    }
  }

}
