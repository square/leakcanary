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

import android.support.annotation.NonNull;
import java.lang.ref.PhantomReference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.EnumSet;

import static android.os.Build.MANUFACTURER;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH;
import static android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1;
import static android.os.Build.VERSION_CODES.JELLY_BEAN;
import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR2;
import static android.os.Build.VERSION_CODES.KITKAT;
import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.os.Build.VERSION_CODES.LOLLIPOP_MR1;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.N_MR1;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.O_MR1;
import static android.os.Build.VERSION_CODES.P;
import static com.squareup.leakcanary.AndroidWatchExecutor.LEAK_CANARY_THREAD_NAME;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.HUAWEI;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.LENOVO;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.LG;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.MEIZU;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.MOTOROLA;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.NVIDIA;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.SAMSUNG;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.VIVO;

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
@SuppressWarnings({ "unused", "WeakerAccess" }) // Public API.
public enum AndroidExcludedRefs {

  // ######## Android SDK Excluded refs ########

  ACTIVITY_CLIENT_RECORD__NEXT_IDLE(SDK_INT >= KITKAT && SDK_INT <= LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.app.ActivityThread$ActivityClientRecord", "nextIdle")
          .reason("Android AOSP sometimes keeps a reference to a destroyed activity as a"
              + " nextIdle client record in the android.app.ActivityThread.mActivities map."
              + " Not sure what's going on there, input welcome.");
    }
  },

  SPAN_CONTROLLER(SDK_INT <= KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      String reason =
          "Editor inserts a special span, which has a reference to the EditText. That span is a"
              + " NoCopySpan, which makes sure it gets dropped when creating a new"
              + " SpannableStringBuilder from a given CharSequence."
              + " TextView.onSaveInstanceState() does a copy of its mText before saving it in the"
              + " bundle. Prior to KitKat, that copy was done using the SpannableString"
              + " constructor, instead of SpannableStringBuilder. The SpannableString constructor"
              + " does not drop NoCopySpan spans. So we end up with a saved state that holds a"
              + " reference to the textview and therefore the entire view hierarchy & activity"
              + " context. Fix: https://github.com/android/platform_frameworks_base/commit"
              + "/af7dcdf35a37d7a7dbaad7d9869c1c91bce2272b ."
              + " To fix this, you could override TextView.onSaveInstanceState(), and then use"
              + " reflection to access TextView.SavedState.mText and clear the NoCopySpan spans.";
      excluded.instanceField("android.widget.Editor$EasyEditSpanController", "this$0")
          .reason(reason);
      excluded.instanceField("android.widget.Editor$SpanController", "this$0").reason(reason);
    }
  },

  MEDIA_SESSION_LEGACY_HELPER__SINSTANCE(SDK_INT == LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("android.media.session.MediaSessionLegacyHelper", "sInstance")
          .reason("MediaSessionLegacyHelper is a static singleton that is lazily instantiated and"
              + " keeps a reference to the context it's given the first time"
              + " MediaSessionLegacyHelper.getHelper() is called."
              + " This leak was introduced in android-5.0.1_r1 and fixed in Android 5.1.0_r1 by"
              + " calling context.getApplicationContext()."
              + " Fix: https://github.com/android/platform_frameworks_base/commit"
              + "/9b5257c9c99c4cb541d8e8e78fb04f008b1a9091"
              + " To fix this, you could call MediaSessionLegacyHelper.getHelper() early"
              + " in Application.onCreate() and pass it the application context.");
    }
  },

  TEXT_LINE__SCACHED(SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("android.text.TextLine", "sCached")
          .reason("TextLine.sCached is a pool of 3 TextLine instances. TextLine.recycle() has had"
              + " at least two bugs that created memory leaks by not correctly clearing the"
              + " recycled TextLine instances. The first was fixed in android-5.1.0_r1:"
              + " https://github.com/android/platform_frameworks_base/commit"
              + "/893d6fe48d37f71e683f722457bea646994a10"
              + " The second was fixed, not released yet:"
              + " https://github.com/android/platform_frameworks_base/commit"
              + "/b3a9bc038d3a218b1dbdf7b5668e3d6c12be5e"
              + " To fix this, you could access TextLine.sCached and clear the pool every now"
              + " and then (e.g. on activity destroy).");
    }
  },

  BLOCKING_QUEUE() {
    @Override void add(ExcludedRefs.Builder excluded) {
      String reason = "A thread waiting on a blocking queue will leak the last"
          + " dequeued object as a stack local reference. So when a HandlerThread becomes idle, it"
          + " keeps a local reference to the last message it received. That message then gets"
          + " recycled and can be used again. As long as all messages are recycled after being"
          + " used, this won't be a problem, because these references are cleared when being"
          + " recycled. However, dialogs create template Message instances to be copied when a"
          + " message needs to be sent. These Message templates holds references to the dialog"
          + " listeners, which most likely leads to holding a reference onto the activity in some"
          + " way. Dialogs never recycle their template Message, assuming these Message instances"
          + " will get GCed when the dialog is GCed."
          + " The combination of these two things creates a high potential for memory leaks as soon"
          + " as you use dialogs. These memory leaks might be temporary, but some handler threads"
          + " sleep for a long time."
          + " To fix this, you could post empty messages to the idle handler threads from time to"
          + " time. This won't be easy because you cannot access all handler threads, but a library"
          + " that is widely used should consider doing this for its own handler threads. This leaks"
          + " has been shown to happen in both Dalvik and ART.";
      excluded.instanceField("android.os.Message", "obj").reason(reason);
      excluded.instanceField("android.os.Message", "next").reason(reason);
      excluded.instanceField("android.os.Message", "target").reason(reason);
    }
  },

  INPUT_METHOD_MANAGER__SERVED_VIEW(SDK_INT >= ICE_CREAM_SANDWICH_MR1 && SDK_INT <= O_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      String reason = "When we detach a view that receives keyboard input, the InputMethodManager"
          + " leaks a reference to it until a new view asks for keyboard input."
          + " Tracked here: https://code.google.com/p/android/issues/detail?id=171190"
          + " Hack: https://gist.github.com/pyricau/4df64341cc978a7de414";
      excluded.instanceField("android.view.inputmethod.InputMethodManager", "mNextServedView")
          .reason(reason);
      excluded.instanceField("android.view.inputmethod.InputMethodManager", "mServedView")
          .reason(reason);
      excluded.instanceField("android.view.inputmethod.InputMethodManager",
          "mServedInputConnection").reason(reason);
    }
  },

  INPUT_METHOD_MANAGER__ROOT_VIEW(SDK_INT >= ICE_CREAM_SANDWICH_MR1 && SDK_INT <= O_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.view.inputmethod.InputMethodManager", "mCurRootView")
          .reason("The singleton InputMethodManager is holding a reference to mCurRootView long"
              + " after the activity has been destroyed."
              + " Observed on ICS MR1: https://github.com/square/leakcanary/issues/1"
              + "#issuecomment-100579429"
              + " Hack: https://gist.github.com/pyricau/4df64341cc978a7de414");
    }
  },

  LAYOUT_TRANSITION(SDK_INT >= ICE_CREAM_SANDWICH && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.animation.LayoutTransition$1", "val$parent")
          .reason("LayoutTransition leaks parent ViewGroup through"
              + " ViewTreeObserver.OnPreDrawListener When triggered, this leaks stays until the"
              + " window is destroyed. Tracked here:"
              + " https://code.google.com/p/android/issues/detail?id=171830");
    }
  },

  SPELL_CHECKER_SESSION(SDK_INT >= JELLY_BEAN && SDK_INT <= N) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.view.textservice.SpellCheckerSession$1", "this$0")
          .reason("SpellCheckerSessionListenerImpl.mHandler is leaking destroyed Activity when the"
              + " SpellCheckerSession is closed before the service is connected."
              + " Tracked here: https://code.google.com/p/android/issues/detail?id=172542");
    }
  },

  SPELL_CHECKER(SDK_INT == LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.widget.SpellChecker$1", "this$0")
          .reason("SpellChecker holds on to a detached view that points to a destroyed activity."
              + " mSpellRunnable is being enqueued, and that callback should be removed when "
              + " closeSession() is called. Maybe closeSession() wasn't called, or maybe it was "
              + " called after the view was detached.");
    }
  },

  ACTIVITY_CHOOSE_MODEL(SDK_INT > ICE_CREAM_SANDWICH && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      String reason = "ActivityChooserModel holds a static reference to the last set"
          + " ActivityChooserModelPolicy which can be an activity context."
          + " Tracked here: https://code.google.com/p/android/issues/detail?id=172659"
          + " Hack: https://gist.github.com/andaag/b05ab66ed0f06167d6e0";
      excluded.instanceField("android.support.v7.internal.widget.ActivityChooserModel",
          "mActivityChoserModelPolicy").reason(reason);
      excluded.instanceField("android.widget.ActivityChooserModel", "mActivityChoserModelPolicy")
          .reason(reason);
    }
  },

  SPEECH_RECOGNIZER(SDK_INT < LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.speech.SpeechRecognizer$InternalListener", "this$0")
          .reason("Prior to Android 5, SpeechRecognizer.InternalListener was a non static inner"
              + " class and leaked the SpeechRecognizer which leaked an activity context."
              + " Fixed in AOSP: https://github.com/android/platform_frameworks_base/commit"
              + " /b37866db469e81aca534ff6186bdafd44352329b");
    }
  },

  ACCOUNT_MANAGER(SDK_INT <= O_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.accounts.AccountManager$AmsTask$Response", "this$1")
          .reason("AccountManager$AmsTask$Response is a stub and is held in memory by native code,"
              + " probably because the reference to the response in the other process hasn't been"
              + " cleared."
              + " AccountManager$AmsTask is holding on to the activity reference to use for"
              + " launching a new sub- Activity."
              + " Tracked here: https://code.google.com/p/android/issues/detail?id=173689"
              + " Fix: Pass a null activity reference to the AccountManager methods and then deal"
              + " with the returned future to to get the result and correctly start an activity"
              + " when it's available.");
    }
  },

  MEDIA_SCANNER_CONNECTION(SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.media.MediaScannerConnection", "mContext")
          .reason("The static method MediaScannerConnection.scanFile() takes an activity context"
              + " but the service might not disconnect after the activity has been destroyed."
              + " Tracked here: https://code.google.com/p/android/issues/detail?id=173788"
              + " Fix: Create an instance of MediaScannerConnection yourself and pass in the"
              + " application context. Call connect() and disconnect() manually.");
    }
  },

  USER_MANAGER__SINSTANCE(SDK_INT >= JELLY_BEAN_MR2 && SDK_INT < O) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.os.UserManager", "mContext")
          .reason("UserManager has a static sInstance field that creates an instance and caches it"
              + " the first time UserManager.get() is called. This instance is created with the"
              + " outer context (which is an activity base context)."
              + " Tracked here: https://code.google.com/p/android/issues/detail?id=173789"
              + " Introduced by: https://github.com/android/platform_frameworks_base/commit"
              + "/27db46850b708070452c0ce49daf5f79503fbde6"
              + " Fix: trigger a call to UserManager.get() in Application.onCreate(), so that the"
              + " UserManager instance gets cached with a reference to the application context.");
    }
  },

  APP_WIDGET_HOST_CALLBACKS(SDK_INT < LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.appwidget.AppWidgetHost$Callbacks", "this$0")
          .reason("android.appwidget.AppWidgetHost$Callbacks is a stub and is held in memory native"
              + " code. The reference to the `mContext` was not being cleared, which caused the"
              + " Callbacks instance to retain this reference"
              + " Fixed in AOSP: https://github.com/android/platform_frameworks_base/commit"
              + "/7a96f3c917e0001ee739b65da37b2fadec7d7765");
    }
  },

  AUDIO_MANAGER(SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.media.AudioManager$1", "this$0")
          .reason("Prior to Android M, VideoView required audio focus from AudioManager and"
              + " never abandoned it, which leaks the Activity context through the AudioManager."
              + " The root of the problem is that AudioManager uses whichever"
              + " context it receives, which in the case of the VideoView example is an Activity,"
              + " even though it only needs the application's context. The issue is fixed in"
              + " Android M, and the AudioManager now uses the application's context."
              + " Tracked here: https://code.google.com/p/android/issues/detail?id=152173"
              + " Fix: https://gist.github.com/jankovd/891d96f476f7a9ce24e2");
    }
  },

  EDITTEXT_BLINK_MESSAGEQUEUE(SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.widget.Editor$Blink", "this$0")
          .reason("The EditText Blink of the Cursor is implemented using a callback and Messages,"
              + " which trigger the display of the Cursor. If an AlertDialog or DialogFragment that"
              + " contains a blinking cursor is detached, a message is posted with a delay after the"
              + " dialog has been closed and as a result leaks the Activity."
              + " This can be fixed manually by calling TextView.setCursorVisible(false) in the"
              + " dismiss() method of the dialog."
              + " Tracked here: https://code.google.com/p/android/issues/detail?id=188551"
              + " Fixed in AOSP: https://android.googlesource.com/platform/frameworks/base/+"
              + "/5b734f2430e9f26c769d6af8ea5645e390fcf5af%5E%21/");
    }
  },

  CONNECTIVITY_MANAGER__SINSTANCE(SDK_INT <= M) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.net.ConnectivityManager", "sInstance")
          .reason("ConnectivityManager has a sInstance field that is set when the first"
              + " ConnectivityManager instance is created. ConnectivityManager has a mContext field."
              + " When calling activity.getSystemService(Context.CONNECTIVITY_SERVICE) , the first"
              + " ConnectivityManager instance is created with the activity context and stored in"
              + " sInstance. That activity context then leaks forever."
              + " Until this is fixed, app developers can prevent this leak by making sure the"
              + " ConnectivityManager is first created with an App Context. E.g. in some static"
              + " init do: context.getApplicationContext()"
              + ".getSystemService(Context.CONNECTIVITY_SERVICE)"
              + " Tracked here: https://code.google.com/p/android/issues/detail?id=198852"
              + " Introduced here: https://github.com/android/platform_frameworks_base/commit/"
              + "e0bef71662d81caaaa0d7214fb0bef5d39996a69");
    }
  },

  ACCESSIBILITY_NODE_INFO__MORIGINALTEXT(SDK_INT >= O && SDK_INT <= O_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.view.accessibility.AccessibilityNodeInfo", "mOriginalText")
          .reason("AccessibilityNodeInfo has a static sPool of AccessibilityNodeInfo. When"
              + " AccessibilityNodeInfo instances are released back in the pool,"
              + " AccessibilityNodeInfo.clear() does not clear the mOriginalText field, which"
              + " causes spans to leak which in turns causes TextView.ChangeWatcher to leak and the"
              + " whole view hierarchy. Introduced here: https://android.googlesource.com/platform/"
              + "frameworks/base/+/193520e3dff5248ddcf8435203bf99d2ba667219%5E%21/core/java/"
              + "android/view/accessibility/AccessibilityNodeInfo.java");
    }
  },

  BACKDROP_FRAME_RENDERER__MDECORVIEW(SDK_INT >= N && SDK_INT <= O) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("com.android.internal.policy.BackdropFrameRenderer", "mDecorView")
          .reason("When BackdropFrameRenderer.releaseRenderer() is called, there's an unknown case"
              + " where mRenderer becomes null but mChoreographer doesn't and the thread doesn't"
              + " stop and ends up leaking mDecorView which itself holds on to a destroyed"
              + " activity");
    }
  },

  // ######## Manufacturer specific Excluded refs ########

  INSTRUMENTATION_RECOMMEND_ACTIVITY(
      MEIZU.equals(MANUFACTURER) && SDK_INT >= LOLLIPOP && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("android.app.Instrumentation", "mRecommendActivity")
          .reason("Instrumentation would leak com.android.internal.app.RecommendActivity (in"
              + " framework.jar) in Meizu FlymeOS 4.5 and above, which is based on Android 5.0 and "
              + " above");
    }
  },

  DEVICE_POLICY_MANAGER__SETTINGS_OBSERVER(
      MOTOROLA.equals(MANUFACTURER) && SDK_INT >= KITKAT && SDK_INT <= LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      if (MOTOROLA.equals(MANUFACTURER) && SDK_INT == KITKAT) {
        excluded.instanceField("android.app.admin.DevicePolicyManager$SettingsObserver", "this$0")
            .reason("DevicePolicyManager keeps a reference to the context it has been created with"
                + " instead of extracting the application context. In this Motorola build,"
                + " DevicePolicyManager has an inner SettingsObserver class that is a content"
                + " observer, which is held into memory by a binder transport object.");
      }
    }
  },

  SPEN_GESTURE_MANAGER(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("com.samsung.android.smartclip.SpenGestureManager", "mContext")
          .reason("SpenGestureManager has a static mContext field that leaks a reference to the"
              + " activity. Yes, a STATIC mContext field.");
    }
  },

  GESTURE_BOOST_MANAGER(HUAWEI.equals(MANUFACTURER) && SDK_INT >= N && SDK_INT <= N_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("android.gestureboost.GestureBoostManager", "mContext")
          .reason("GestureBoostManager is a static singleton that leaks an activity context."
              + " Fix: https://github.com/square/leakcanary/issues/696#issuecomment-296420756");
    }
  },

  INPUT_METHOD_MANAGER__LAST_SERVED_VIEW(
      HUAWEI.equals(MANUFACTURER) && SDK_INT >= M && SDK_INT <= O_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      String reason = "HUAWEI added a mLastSrvView field to InputMethodManager"
          + " that leaks a reference to the last served view.";
      excluded.instanceField("android.view.inputmethod.InputMethodManager", "mLastSrvView")
          .reason(reason);
    }
  },

  CLIPBOARD_UI_MANAGER__SINSTANCE(
      SAMSUNG.equals(MANUFACTURER) && SDK_INT >= KITKAT && SDK_INT <= LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.sec.clipboard.ClipboardUIManager", "mContext")
          .reason("ClipboardUIManager is a static singleton that leaks an activity context."
              + " Fix: trigger a call to ClipboardUIManager.getInstance() in Application.onCreate()"
              + " , so that the ClipboardUIManager instance gets cached with a reference to the"
              + " application context. Example: https://gist.github.com/cypressious/"
              + "91c4fb1455470d803a602838dfcd5774");
    }
  },

  SEM_CLIPBOARD_MANAGER__MCONTEXT(
      SAMSUNG.equals(MANUFACTURER) && SDK_INT >= KITKAT && SDK_INT <= N) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("com.samsung.android.content.clipboard.SemClipboardManager",
          "mContext")
          .reason("SemClipboardManager is held in memory by an anonymous inner class"
              + " implementation of android.os.Binder, thereby leaking an activity context.");
    }
  },

  SEM_EMERGENCY_MANAGER__MCONTEXT(
      SAMSUNG.equals(MANUFACTURER) && SDK_INT >= KITKAT && SDK_INT <= N) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("com.samsung.android.emergencymode.SemEmergencyManager", "mContext")
          .reason("SemEmergencyManager is a static singleton that leaks a DecorContext."
              + " Fix: https://gist.github.com/jankovd/a210460b814c04d500eb12025902d60d");
    }
  },

  BUBBLE_POPUP_HELPER__SHELPER(
      LG.equals(MANUFACTURER) && SDK_INT >= KITKAT && SDK_INT <= LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("android.widget.BubblePopupHelper", "sHelper")
          .reason("A static helper for EditText bubble popups leaks a reference to the latest"
              + " focused view.");
    }
  },

  LGCONTEXT__MCONTEXT(LG.equals(MANUFACTURER) && SDK_INT == LOLLIPOP) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("com.lge.systemservice.core.LGContext", "mContext")
          .reason("LGContext is a static singleton that leaks an activity context.");
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
      excluded.instanceField("com.nvidia.ControllerMapper.MapperClient$ServiceClient", "this$0")
          .reason("Not sure exactly what ControllerMapper is about, but there is an anonymous"
              + " Handler in ControllerMapper.MapperClient.ServiceClient, which leaks"
              + " ControllerMapper.MapperClient which leaks the activity context.");
    }
  },

  TEXT_VIEW__MLAST_HOVERED_VIEW(
      SAMSUNG.equals(MANUFACTURER) && SDK_INT >= KITKAT && SDK_INT <= O) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("android.widget.TextView", "mLastHoveredView")
          .reason("mLastHoveredView is a static field in TextView that leaks the last hovered"
              + " view.");
    }
  },

  PERSONA_MANAGER(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.os.PersonaManager", "mContext")
          .reason("android.app.LoadedApk.mResources has a reference to"
              + " android.content.res.Resources.mPersonaManager which has a reference to"
              + " android.os.PersonaManager.mContext which is an activity.");
    }
  },

  RESOURCES__MCONTEXT(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.content.res.Resources", "mContext")
          .reason("In AOSP the Resources class does not have a context."
              + " Here we have ZygoteInit.mResources (static field) holding on to a Resources"
              + " instance that has a context that is the activity."
              + " Observed here: https://github.com/square/leakcanary/issues/1#issue-74450184");
    }
  },

  VIEW_CONFIGURATION__MCONTEXT(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.instanceField("android.view.ViewConfiguration", "mContext")
          .reason("In AOSP the ViewConfiguration class does not have a context."
              + " Here we have ViewConfiguration.sConfigurations (static field) holding on to a"
              + " ViewConfiguration instance that has a context that is the activity."
              + " Observed here: https://github.com/square/leakcanary/issues"
              + "/1#issuecomment-100324683");
    }
  },

  SYSTEM_SENSOR_MANAGER__MAPPCONTEXTIMPL((LENOVO.equals(MANUFACTURER) && SDK_INT == KITKAT) //
      || (VIVO.equals(MANUFACTURER) && SDK_INT == LOLLIPOP_MR1)) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("android.hardware.SystemSensorManager", "mAppContextImpl")
          .reason("SystemSensorManager stores a reference to context"
              + " in a static field in its constructor."
              + " Fix: use application context to get SensorManager");
    }
  },

  AUDIO_MANAGER__MCONTEXT_STATIC(SAMSUNG.equals(MANUFACTURER) && SDK_INT == KITKAT) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("android.media.AudioManager", "mContext_static")
          .reason("Samsung added a static mContext_static field to AudioManager, holds a reference"
              + " to the activity."
              + " Observed here: https://github.com/square/leakcanary/issues/32");
    }
  },

  ACTIVITY_MANAGER_MCONTEXT(SAMSUNG.equals(MANUFACTURER) && SDK_INT == LOLLIPOP_MR1) {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.staticField("android.app.ActivityManager", "mContext")
          .reason("Samsung added a static mContext field to ActivityManager, holds a reference"
              + " to the activity."
              + " Observed here: https://github.com/square/leakcanary/issues/177 Fix in comment:"
              + " https://github.com/square/leakcanary/issues/177#issuecomment-222724283");
    }
  },

  // ######## General Excluded refs ########

  SOFT_REFERENCES {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.clazz(WeakReference.class.getName()).alwaysExclude();
      excluded.clazz(SoftReference.class.getName()).alwaysExclude();
      excluded.clazz(PhantomReference.class.getName()).alwaysExclude();
      excluded.clazz("java.lang.ref.Finalizer").alwaysExclude();
      excluded.clazz("java.lang.ref.FinalizerReference").alwaysExclude();
    }
  },

  FINALIZER_WATCHDOG_DAEMON {
    @Override void add(ExcludedRefs.Builder excluded) {
      // If the FinalizerWatchdogDaemon thread is on the shortest path, then there was no other
      // reference to the object and it was about to be GCed.
      excluded.thread("FinalizerWatchdogDaemon").alwaysExclude();
    }
  },

  MAIN {
    @Override void add(ExcludedRefs.Builder excluded) {
      // The main thread stack is ever changing so local variables aren't likely to hold references
      // for long. If this is on the shortest path, it's probably that there's a longer path with
      // a real leak.
      excluded.thread("main").alwaysExclude();
    }
  },

  LEAK_CANARY_THREAD {
    @Override void add(ExcludedRefs.Builder excluded) {
      excluded.thread(LEAK_CANARY_THREAD_NAME).alwaysExclude();
    }
  },

  EVENT_RECEIVER__MMESSAGE_QUEUE {
    @Override void add(ExcludedRefs.Builder excluded) {
      //  DisplayEventReceiver keeps a reference message queue object so that it is not GC'd while
      // the native peer of the receiver is using them.
      // The main thread message queue is held on by the main Looper, but that might be a longer
      // path. Let's not confuse people with a shorter path that is less meaningful.
      excluded.instanceField("android.view.Choreographer$FrameDisplayEventReceiver",
          "mMessageQueue").alwaysExclude();
    }
  },

  VIEWLOCATIONHOLDER_ROOT(SDK_INT == P) {
    @Override void add(ExcludedRefs.Builder excluded) {
      //  In Android P, ViewLocationHolder has an mRoot field that is not cleared in its clear()
      // method.
      // Introduced in https://github.com/aosp-mirror/platform_frameworks_base/commit/86b326012813f09d8f1de7d6d26c986a909de894
      // Bug report: https://issuetracker.google.com/issues/112792715
      excluded.instanceField("android.view.ViewGroup$ViewLocationHolder",
          "mRoot");
    }
  };

  /**
   * This returns the references in the leak path that should be ignored by all on Android.
   */
  public static @NonNull ExcludedRefs.Builder createAndroidDefaults() {
    return createBuilder(
        EnumSet.of(SOFT_REFERENCES, FINALIZER_WATCHDOG_DAEMON, MAIN, LEAK_CANARY_THREAD,
            EVENT_RECEIVER__MMESSAGE_QUEUE));
  }

  /**
   * This returns the references in the leak path that can be ignored for app developers. This
   * doesn't mean there is no memory leak, to the contrary. However, some leaks are caused by bugs
   * in AOSP or manufacturer forks of AOSP. In such cases, there is very little we can do as app
   * developers except by resorting to serious hacks, so we remove the noise caused by those leaks.
   */
  public static @NonNull ExcludedRefs.Builder createAppDefaults() {
    return createBuilder(EnumSet.allOf(AndroidExcludedRefs.class));
  }

  public static @NonNull ExcludedRefs.Builder createBuilder(EnumSet<AndroidExcludedRefs> refs) {
    ExcludedRefs.Builder excluded = ExcludedRefs.builder();
    for (AndroidExcludedRefs ref : refs) {
      if (ref.applies) {
        ref.add(excluded);
        ((ExcludedRefs.BuilderWithParams) excluded).named(ref.name());
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
