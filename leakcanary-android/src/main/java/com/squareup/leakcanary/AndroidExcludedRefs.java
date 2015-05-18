/*
 * Copyright (C) 2015 Square, Inc.
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

import java.util.EnumSet;

import static android.os.Build.MANUFACTURER;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.ECLAIR;
import static android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH;
import static android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1;
import static android.os.Build.VERSION_CODES.JELLY_BEAN;
import static android.os.Build.VERSION_CODES.KITKAT;
import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static com.squareup.leakcanary.AndroidWatchExecutor.LEAK_CANARY_THREAD_NAME;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.LG;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.LOLLIPOP_MR1;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.MOTOROLA;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.NVIDIA;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.SAMSUNG;

/**
 * This class is a work in progress. You can help by reporting leak traces that seem to be caused
 * by the Android SDK, here: https://github.com/square/leakcanary/issues/new
 *
 * We filter on SDK versions and Manufacturers because many of those leaks are specific to a given
 * manufacturer implementation, they usually share their builds across multiple models, and the
 * leaks eventually get fixed in newer versions.
 *
 * Most app developers should use {@link #createAppDefaults()}. However, you can also pick the
 * leaks you want to ignore by creating an {@link EnumSet} that matches your needs and calling
 * {@link #createBuilder(EnumSet)}
 */
public enum AndroidExcludedRefs {

  ACTIVITY_CLIENT_RECORD__NEXT_IDLE(SDK_INT >= KITKAT && SDK_INT <= LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // Android AOSP sometimes keeps a reference to a destroyed activity as a "nextIdle" client
      // record in the android.app.ActivityThread.mActivities map.
      // Not sure what's going on there, input welcome.
      excluded.instanceField("android.app.ActivityThread$ActivityClientRecord", "nextIdle");
    }
  },

  SPAN_CONTROLLER(SDK_INT <= KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // Editor inserts a special span, which has a reference to the EditText. That span is a
      // NoCopySpan, which makes sure it gets dropped when creating a new SpannableStringBuilder
      // from a given CharSequence.
      // TextView.onSaveInstanceState() does a copy of its mText before saving it in the bundle.
      // Prior to KitKat, that copy was done using the SpannableString constructor, instead of
      // SpannableStringBuilder. The SpannableString constructor does not drop NoCopySpan spans.
      // So we end up with a saved state that holds a reference to the textview and therefore the
      // entire view hierarchy & activity context.
      // Fix: https://github.com/android/platform_frameworks_base/commit
      // /af7dcdf35a37d7a7dbaad7d9869c1c91bce2272b

      // Hack: to fix this, you could override TextView.onSaveInstanceState(), and then use
      // reflection to access TextView.SavedState.mText and clear the NoCopySpan spans.
      excluded.instanceField("android.widget.Editor$EasyEditSpanController", "this$0");
      excluded.instanceField("android.widget.Editor$SpanController", "this$0");
    }
  },

  MEDIA_SESSION_LEGACY_HELPER__SINSTANCE(SDK_INT == LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // MediaSessionLegacyHelper is a static singleton that is lazily instantiated and keeps a
      // reference to the context it's given the first time MediaSessionLegacyHelper.getHelper()
      // is called.
      // This leak was introduced in android-5.0.1_r1 and fixed in Android 5.1.0_r1 by calling
      // context.getApplicationContext().
      // Fix: https://github.com/android/platform_frameworks_base/commit
      // /9b5257c9c99c4cb541d8e8e78fb04f008b1a9091

      // Hack: to fix this, you could call MediaSessionLegacyHelper.getHelper() early in
      // Application.onCreate() and pass it the application context.
      excluded.staticField("android.media.session.MediaSessionLegacyHelper", "sInstance");
    }
  },

  TEXT_LINE__SCACHED(SDK_INT < LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // TextLine.sCached is a pool of 3 TextLine instances. TextLine.recycle() has had at least two
      // bugs that created memory leaks by not correctly clearing the recycled TextLine instances.
      // The first was fixed in android-5.1.0_r1:
      // https://github.com/android/platform_frameworks_base/commit
      // /893d6fe48d37f71e683f722457bea646994a10bf

      // The second was fixed, not released yet:
      // https://github.com/android/platform_frameworks_base/commit
      // /b3a9bc038d3a218b1dbdf7b5668e3d6c12be5ee4

      // Hack: to fix this, you could access TextLine.sCached and clear the pool every now and then
      // (e.g. on activity destroy).
      excluded.staticField("android.text.TextLine", "sCached");
    }
  },

  BLOCKING_QUEUE(SDK_INT < LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // Prior to ART, a thread waiting on a blocking queue will leak the last dequeued object
      // as a stack local reference.
      // So when a HandlerThread becomes idle, it keeps a local reference to the last message it
      // received. That message then gets recycled and can be used again.
      // As long as all messages are recycled after being used, this won't be a problem, because
      // there references are cleared when being recycled.
      // However, dialogs create template Message instances to be copied when a message needs to be
      // sent. These Message templates holds references to the dialog listeners, which most likely
      // leads to holding a reference onto the activity in some way. Dialogs never recycle their
      // template Message, assuming these Message instances will get GCed when the dialog is GCed.
      // The combination of these two things creates a high potential for memory leaks as soon
      // as you use dialogs. These memory leaks might be temporary, but some handler threads sleep
      // for a long time.

      // Hack: to fix this, you could post empty messages to the idle handler threads from time to
      // time. This won't be easy because you cannot access all handler threads, but a library
      // that is widely used should consider doing this for its own handler threads.
      excluded.instanceField("android.os.Message", "obj");
      excluded.instanceField("android.os.Message", "next");
      excluded.instanceField("android.os.Message", "target");
    }
  },

  INPUT_METHOD_MANAGER__SERVED_VIEW(SDK_INT >= JELLY_BEAN && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // When we detach a view that receives keyboard input, the InputMethodManager leaks a
      // reference to it until a new view asks for keyboard input.
      // Tracked here: https://code.google.com/p/android/issues/detail?id=171190
      // Hack: https://gist.github.com/pyricau/4df64341cc978a7de414
      excluded.instanceField("android.view.inputmethod.InputMethodManager", "mNextServedView");
      excluded.instanceField("android.view.inputmethod.InputMethodManager", "mServedView");
      excluded.instanceField("android.view.inputmethod.InputMethodManager",
          "mServedInputConnection");
    }
  },

  INPUT_METHOD_MANAGER__ROOT_VIEW(SDK_INT >= ICE_CREAM_SANDWICH_MR1 && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // The singleton InputMethodManager is holding a reference to mCurRootView long after the
      // activity has been destroyed.
      // Observed on ICS MR1: https://github.com/square/leakcanary/issues/1#issuecomment-100579429
      // Hack: https://gist.github.com/pyricau/4df64341cc978a7de414
      excluded.instanceField("android.view.inputmethod.InputMethodManager", "mCurRootView");
    }
  },

  LAYOUT_TRANSITION(SDK_INT >= ICE_CREAM_SANDWICH && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // LayoutTransition leaks parent ViewGroup through ViewTreeObserver.OnPreDrawListener
      // When triggered, this leaks stays until the window is destroyed.
      // Tracked here: https://code.google.com/p/android/issues/detail?id=171830
      excluded.instanceField("android.animation.LayoutTransition$1", "val$parent");
    }
  },

  SPELL_CHECKER_SESSION(SDK_INT >= JELLY_BEAN || SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // SpellCheckerSessionListenerImpl.mHandler is leaking destroyed Activity when the
      // SpellCheckerSession is closed before the service is connected.
      // Tracked here: https://code.google.com/p/android/issues/detail?id=172542
      excluded.instanceField("android.view.textservice.SpellCheckerSession$1", "this$0");
    }
  },

  ACTIVITY_CHOOSE_MODEL(SDK_INT > ICE_CREAM_SANDWICH && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // ActivityChooserModel holds a static reference to the last set ActivityChooserModelPolicy
      // which can be an activity context.
      // Tracked here : https://code.google.com/p/android/issues/detail?id=172659
      // Hack : https://gist.github.com/andaag/b05ab66ed0f06167d6e0
      excluded.staticField("android.support.v7.internal.widget.ActivityChooserModel",
          "mActivityChoserModelPolicy");
      excluded.staticField("android.widget.ActivityChooserModel", "mActivityChoserModelPolicy");
    }
  },

  SPEECH_RECOGNIZER(SDK_INT < LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // Prior to Android 5, SpeechRecognizer.InternalListener was a non static inner class and
      // leaked the SpeechRecognizer which leaked an activity context.
      // Fixed in AOSP: https://github.com/android/platform_frameworks_base/commit
      // /b37866db469e81aca534ff6186bdafd44352329b
      excluded.instanceField("android.speech.SpeechRecognizer$InternalListener", "this$0");
    }
  },

  ACCOUNT_MANAGER(SDK_INT > ECLAIR && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // AccountManager$AmsTask$Response is a stub and is held in memory by native code, probably
      // because the reference to the response in the other process hasn't been cleared.
      // AccountManager$AmsTask is holding on to the activity reference to use for launching a new
      // sub- Activity.
      // Tracked here: https://code.google.com/p/android/issues/detail?id=173689
      // Fix: Pass a null activity reference to the AccountManager methods and then deal with the
      // returned future to to get the result and correctly start an activity when it's available.
      excluded.instanceField("android.accounts.AccountManager$AmsTask$Response", "this$1");
    }
  },

  MEDIA_SCANNER_CONNECTION(SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // The static method MediaScannerConnection.scanFile() takes an activity context but the
      // service might not disconnect after the activity has been destroyed.
      // Tracked here: https://code.google.com/p/android/issues/detail?id=173788
      // Fix: Create an instance of MediaScannerConnection yourself and pass in the application
      // context. Call connect() and disconnect() manually.
      excluded.instanceField("android.media.MediaScannerConnection", "mContext");
    }
  },

  USER_MANAGER__SINSTANCE(SDK_INT >= JELLY_BEAN && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // UserManager has a static sInstance field that creates an instance and caches it the first
      // time UserManager.get() is called. This instance is created with the outer context (which
      // is an activity base context).
      // Tracked here: https://code.google.com/p/android/issues/detail?id=173789
      // Introduced by: https://github.com/android/platform_frameworks_base/commit
      // /27db46850b708070452c0ce49daf5f79503fbde6
      // Fix: trigger a call to UserManager.get() in Application.onCreate(), so that the
      // UserManager instance gets cached with a reference to the application context.
      excluded.instanceField("android.os.UserManager", "mContext");
    }
  },

  DEVICE_POLICY_MANAGER__SETTINGS_OBSERVER(MOTOROLA.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      if (MOTOROLA.equals(MANUFACTURER) && SDK_INT == KITKAT) {
        // DevicePolicyManager keeps a reference to the context it has been created with instead of
        // extracting the application context. In this Motorola build, DevicePolicyManager has an
        // inner SettingsObserver class that is a content observer, which is held into memory
        // by a binder transport object.
        excluded.instanceField("android.app.admin.DevicePolicyManager$SettingsObserver", "this$0");
      }
    }
  },

  SPEN_GESTURE_MANAGER(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // SpenGestureManager has a static mContext field that leaks a reference to the activity.
      // Yes, a STATIC "mContext" field.
      excluded.staticField("com.samsung.android.smartclip.SpenGestureManager", "mContext");
    }
  },

  CLIPBOARD_UI_MANAGER__SINSTANCE(
      SAMSUNG.equals(MANUFACTURER) && SDK_INT >= KITKAT && SDK_INT <= LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // ClipboardUIManager is a static singleton that leaks an activity context.
      excluded.staticField("android.sec.clipboard.ClipboardUIManager", "sInstance");
    }
  },

  BUBBLE_POPUP_HELPER__SHELPER(
      LG.equals(MANUFACTURER) && SDK_INT >= KITKAT && SDK_INT <= LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // A static helper for EditText "bubble popups" leaks a reference to the latest focused view.
      excluded.staticField("android.widget.BubblePopupHelper", "sHelper");
    }
  },

  AW_RESOURCE__SRESOURCES(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // AwResource#setResources() is called with resources that hold a reference to the
      // activity context (instead of the application context) and doesn't clear it.
      // Not sure what's going on there, input welcome.
      excluded.staticField("com.android.org.chromium.android_webview.AwResource", "sResources");
    }
  },

  MAPPER_CLIENT(NVIDIA.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // Not sure exactly what ControllerMapper is about, but there is an anonymous Handler in
      // ControllerMapper.MapperClient.ServiceClient, which leaks ControllerMapper.MapperClient
      // which leaks the activity context.
      excluded.instanceField("com.nvidia.ControllerMapper.MapperClient$ServiceClient", "this$0");
    }
  },

  TEXT_VIEW__MLAST_HOVERED_VIEW(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // mLastHoveredView is a static field in TextView that leaks the last hovered view.
      excluded.staticField("android.widget.TextView", "mLastHoveredView");
    }
  },

  PERSONA_MANAGER(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // android.app.LoadedApk.mResources has a reference to
      // android.content.res.Resources.mPersonaManager which has a reference to
      // android.os.PersonaManager.mContext which is an activity.
      excluded.instanceField("android.os.PersonaManager", "mContext");
    }
  },

  RESOURCES__MCONTEXT(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // In AOSP the Resources class does not have a context.
      // Here we have ZygoteInit.mResources (static field) holding on to a Resources instance that
      // has a context that is the activity.
      // Observed here: https://github.com/square/leakcanary/issues/1#issue-74450184
      excluded.instanceField("android.content.res.Resources", "mContext");
    }
  },

  VIEW_CONFIGURATION__MCONTEXT(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // In AOSP the ViewConfiguration class does not have a context.
      // Here we have ViewConfiguration.sConfigurations (static field) holding on to a
      // ViewConfiguration instance that has a context that is the activity.
      // Observed here: https://github.com/square/leakcanary/issues/1#issuecomment-100324683
      excluded.instanceField("android.view.ViewConfiguration", "mContext");
    }
  },

  AUDIO_MANAGER__MCONTEXT_STATIC(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      // Samsung added a static mContext_static field to AudioManager, holds a reference to the
      // activity.
      // Observed here: https://github.com/square/leakcanary/issues/32
      excluded.staticField("android.media.AudioManager", "mContext_static");
    }
  },

  FINALIZER_WATCHDOG_DAEMON {
    @Override void add(ExcludedRefs.Builder excluded) {
      // If the FinalizerWatchdogDaemon thread is on the shortest path, then there was no other
      // reference to the object and it was about to be GCed.
      excluded.thread("FinalizerWatchdogDaemon");
    }
  },

  MAIN {
    @Override void add(ExcludedRefs.Builder excluded) {
      // The main thread stack is ever changing so local variables aren't likely to hold references
      // for long. If this is on the shortest path, it's probably that there's a longer path with
      // a real leak.
      excluded.thread("main");
    }
  },

  LEAK_CANARY_THREAD {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.thread(LEAK_CANARY_THREAD_NAME);
    }
  },

  EVENT_RECEIVER__MMESSAGE_QUEUE {
    @Override void add(ExcludedRefs.Builder excluded) {
      //  DisplayEventReceiver keeps a reference message queue object so that it is not GC'd while
      // the native peer of the receiver is using them.
      // The main thread message queue is held on by the main Looper, but that might be a longer
      // path. Let's not confuse people with a shorter path that is less meaningful.
      excluded.instanceField("android.view.Choreographer$FrameDisplayEventReceiver",
          "mMessageQueue");
    }
  };

  /**
   * This returns the references in the leak path that should be ignored by all on Android.
   */
  public static ExcludedRefs.Builder createAndroidDefaults() {
    return createBuilder(EnumSet.of(FINALIZER_WATCHDOG_DAEMON, MAIN, LEAK_CANARY_THREAD,
        EVENT_RECEIVER__MMESSAGE_QUEUE));
  }

  /**
   * This returns the references in the leak path that can be ignored for app developers. This
   * doesn't mean there is no memory leak, to the contrary. However, some leaks are caused by bugs
   * in AOSP or manufacturer forks of AOSP. In such cases, there is very little we can do as app
   * developers except by resorting to serious hacks, so we remove the noise caused by those leaks.
   */
  public static ExcludedRefs.Builder createAppDefaults() {
    return createBuilder(EnumSet.allOf(AndroidExcludedRefs.class));
  }

  public static ExcludedRefs.Builder createBuilder(EnumSet<AndroidExcludedRefs> refs) {
    ExcludedRefs.Builder excluded = new ExcludedRefs.Builder();
    for (AndroidExcludedRefs ref : refs) {
      if (ref.applies) {
        ref.add(excluded);
      }
    }
    return excluded;
  }

  final boolean applies;

  AndroidExcludedRefs() {
    this(true);
  }

  AndroidExcludedRefs(boolean applies) {
    this.applies = applies;
  }

  abstract void add(ExcludedRefs.Builder excluded);
}
