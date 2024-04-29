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
package shark

import java.util.EnumSet
import shark.AndroidBuildMirror.Companion.applyIf
import shark.AndroidReferenceMatchers.Companion.appDefaults
import shark.ReferenceMatcher.Companion.ALWAYS
import shark.ReferencePattern.Companion.instanceField
import shark.ReferencePattern.Companion.javaLocal
import shark.ReferencePattern.Companion.nativeGlobalVariable
import shark.ReferencePattern.Companion.staticField
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.JavaLocalPattern
import shark.ReferencePattern.StaticFieldPattern

/**
 * [AndroidReferenceMatchers] values add [ReferenceMatcher] instances to a global list via their
 * [add] method. A [ReferenceMatcher] is either a [IgnoredReferenceMatcher] or
 * a [LibraryLeakReferenceMatcher].
 *
 * [AndroidReferenceMatchers] is used to build the list of known references that cannot ever create
 * leaks (via [IgnoredReferenceMatcher]) as well as the list of known leaks in the Android Framework
 * and in manufacturer specific Android implementations.
 *
 * This class is a work in progress. You can help by reporting leak traces that seem to be caused
 * by the Android SDK, here: https://github.com/square/leakcanary/issues/new
 *
 * We filter on SDK versions and Manufacturers because many of those leaks are specific to a given
 * manufacturer implementation, they usually share their builds across multiple models, and the
 * leaks eventually get fixed in newer versions.
 *
 * Most app developers should use [appDefaults]. However, you can also use a subset of
 * [AndroidReferenceMatchers] by creating an [EnumSet] that matches your needs and calling
 * [buildKnownReferences].
 */
enum class AndroidReferenceMatchers : ReferenceMatcher.ListBuilder {

  // ######## Android Framework known leaks ########

  PERMISSION_CONTROLLER_MANAGER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "android.permission.PermissionControllerManager", "mContext"
      ).leak(
        description = "On some devices PermissionControllerManager " +
          "may be initialized with Activity as its Context field. " +
          "Fix: you can \"fix\" this leak by calling getSystemService(\"permission_controller\") " +
          "on an application context. " +
          "Tracked here: https://issuetracker.google.com/issues/318415056",
        patternApplies = applyIf { sdkInt >= 29 }
      )
    }
  },

  IREQUEST_FINISH_CALLBACK {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.app.Activity\$1", "this\$0").leak(
        description = "Android Q added a new android.app.IRequestFinishCallback\$Stub " +
          "class. android.app.Activity creates an implementation of that interface as an " +
          "anonymous subclass. That anonymous subclass has a reference to the activity. " +
          "Another process is keeping the android.app.IRequestFinishCallback\$Stub " +
          "reference alive long after Activity.onDestroyed() has been called, " +
          "causing the activity to leak. " +
          "Fix: You can \"fix\" this leak by overriding Activity.onBackPressed() and calling " +
          "Activity.finishAfterTransition(); instead of super if the activity is task root and the " +
          "fragment stack is empty. " +
          "Tracked here: https://issuetracker.google.com/issues/139738913",
        patternApplies = applyIf { sdkInt == 29 }
      )
    }
  },

  /**
   * See AndroidReferenceReaders.ACTIVITY_THREAD__NEW_ACTIVITIES for more context
   */
  ACTIVITY_THREAD__M_NEW_ACTIVITIES {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.app.ActivityThread", "mNewActivities").leak(
        description = """
          New activities are leaked by ActivityThread until the main thread becomes idle.
          Tracked here: https://issuetracker.google.com/issues/258390457
        """.trimIndent(),
        patternApplies = applyIf { sdkInt >= 19 }
      )
    }
  },

  SPAN_CONTROLLER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      val description =
        ("Editor inserts a special span, which has a reference to the EditText. That span is a"
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
          + " reflection to access TextView.SavedState.mText and clear the NoCopySpan spans.")

      references += instanceField("android.widget.Editor\$SpanController", "this$0").leak(
        description = description,
        patternApplies = applyIf { sdkInt <= 19 }
      )

      references += instanceField("android.widget.Editor\$EasyEditSpanController", "this$0").leak(
        description = description,
        patternApplies = applyIf { sdkInt <= 19 }
      )
    }
  },

  MEDIA_SESSION_LEGACY_HELPER__SINSTANCE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references +=
        staticField("android.media.session.MediaSessionLegacyHelper", "sInstance").leak(
          description = "MediaSessionLegacyHelper is a static singleton that is lazily instantiated and"
            + " keeps a reference to the context it's given the first time"
            + " MediaSessionLegacyHelper.getHelper() is called."
            + " This leak was introduced in android-5.0.1_r1 and fixed in Android 5.1.0_r1 by"
            + " calling context.getApplicationContext()."
            + " Fix: https://github.com/android/platform_frameworks_base/commit"
            + "/9b5257c9c99c4cb541d8e8e78fb04f008b1a9091"
            + " To fix this, you could call MediaSessionLegacyHelper.getHelper() early"
            + " in Application.onCreate() and pass it the application context.",
          patternApplies = applyIf { sdkInt == 21 }
        )
    }
  },

  TEXT_LINE__SCACHED {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.text.TextLine", "sCached").leak(
        description = "TextLine.sCached is a pool of 3 TextLine instances. TextLine.recycle() has had"
          + " at least two bugs that created memory leaks by not correctly clearing the"
          + " recycled TextLine instances. The first was fixed in android-5.1.0_r1:"
          + " https://github.com/android/platform_frameworks_base/commit"
          + "/893d6fe48d37f71e683f722457bea646994a10"
          + " The second was fixed, not released yet:"
          + " https://github.com/android/platform_frameworks_base/commit"
          + "/b3a9bc038d3a218b1dbdf7b5668e3d6c12be5e"
          + " To fix this, you could access TextLine.sCached and clear the pool every now"
          + " and then (e.g. on activity destroy).",
        patternApplies = applyIf { sdkInt <= 22 }
      )
    }
  },

  BLOCKING_QUEUE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      val description = ("A thread waiting on a blocking queue will leak the last"
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
        + " This leak is fixed by AndroidLeakFixes.FLUSH_HANDLER_THREADS in plumber-android."
        + " Bug report: https://issuetracker.google.com/issues/146144484"
        + " Fixed in Android 12: https://cs.android.com/android/_/android/platform/frameworks/base"
        + "/+/d577e728e9bccbafc707af3060ea914caa73c14f")

      references += instanceField("android.os.Message", "obj")
        .leak(
          description = description,
          patternApplies = applyIf { sdkInt < 31 }
        )
    }
  },

  INPUT_METHOD_MANAGER_IS_TERRIBLE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      val description =
        ("When we detach a view that receives keyboard input, the InputMethodManager"
          + " leaks a reference to it until a new view asks for keyboard input."
          + " Tracked here: https://code.google.com/p/android/issues/detail?id=171190"
          + " Hack: https://gist.github.com/pyricau/4df64341cc978a7de414")

      references += instanceField(
        "android.view.inputmethod.InputMethodManager", "mNextServedView"
      ).leak(
        description = description,
        patternApplies = applyIf { sdkInt in 15..33 }
      )

      references += instanceField(
        "android.view.inputmethod.InputMethodManager", "mServedView"
      ).leak(
        description = description,
        patternApplies = applyIf { sdkInt in 15..28 }
      )

      references += instanceField(
        "android.view.inputmethod.InputMethodManager", "mServedInputConnection"
      ).leak(
        description = description,
        patternApplies = applyIf { sdkInt in 15..27 }
      )

      references += instanceField(
        "android.view.inputmethod.InputMethodManager", "mLastSrvView"
      ).leak(
        description = "HUAWEI added a mLastSrvView field to InputMethodManager" + " that leaks a reference to the last served view.",
        patternApplies = applyIf { manufacturer == HUAWEI && sdkInt in 23..28 }
      )

      references += instanceField(
        "android.view.inputmethod.InputMethodManager", "mCurRootView"
      ).leak(
        description = "The singleton InputMethodManager is holding a reference to mCurRootView long"
          + " after the activity has been destroyed."
          + " Observed on ICS MR1: https://github.com/square/leakcanary/issues/1"
          + "#issuecomment-100579429"
          + " Hack: https://gist.github.com/pyricau/4df64341cc978a7de414",
        patternApplies = applyIf { sdkInt in 15..28 }
      )

      references += instanceField(
        "android.view.inputmethod.InputMethodManager", "mImeInsetsConsumer"
      ).leak(
        description = """
              InputMethodManager.mImeInsetsConsumer isn't set to null when the activity is destroyed.
            """.trimIndent(),
        patternApplies = applyIf { sdkInt >= 30 }
      )
    }
  },

  INPUT_MANAGER__M_LATE_INIT_CONTEXT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.hardware.input.InputManager", "mLateInitContext").leak(
        description = "InputManager singleton leaks its init context which is an activity",
        patternApplies = applyIf { sdkInt == 33 }
      )
    }
  },

  LAYOUT_TRANSITION {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.animation.LayoutTransition$1", "val\$parent").leak(
        description = "LayoutTransition leaks parent ViewGroup through"
          + " ViewTreeObserver.OnPreDrawListener When triggered, this leaks stays until the"
          + " window is destroyed. Tracked here:"
          + " https://code.google.com/p/android/issues/detail?id=171830",
        patternApplies = applyIf { sdkInt in 14..22 }
      )
    }
  },

  SPELL_CHECKER_SESSION {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.view.textservice.SpellCheckerSession$1", "this$0").leak(
        description = "SpellCheckerSessionListenerImpl.mHandler is leaking destroyed Activity when the"
          + " SpellCheckerSession is closed before the service is connected."
          + " Tracked here: https://code.google.com/p/android/issues/detail?id=172542",
        patternApplies = applyIf { sdkInt in 16..24 }
      )
    }
  },

  SPELL_CHECKER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.widget.SpellChecker$1", "this$0").leak(
        description = "SpellChecker holds on to a detached view that points to a destroyed activity."
          + " mSpellRunnable is being enqueued, and that callback should be removed when "
          + " closeSession() is called. Maybe closeSession() wasn't called, or maybe it was "
          + " called after the view was detached.",
        patternApplies = applyIf { sdkInt == 22 }
      )
    }
  },

  ACTIVITY_CHOOSE_MODEL {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      val description = ("ActivityChooserModel holds a static reference to the last set"
        + " ActivityChooserModelPolicy which can be an activity context."
        + " Tracked here: https://code.google.com/p/android/issues/detail?id=172659"
        + " Hack: https://gist.github.com/andaag/b05ab66ed0f06167d6e0")


      references += instanceField(
        "android.support.v7.internal.widget.ActivityChooserModel",
        "mActivityChoserModelPolicy"
      ).leak(
        description = description,
        patternApplies = applyIf { sdkInt in 15..22 }
      )

      references += instanceField(
        "android.widget.ActivityChooserModel", "mActivityChoserModelPolicy"
      ).leak(
        description = description,
        patternApplies = ALWAYS
      )
    }
  },

  MEDIA_PROJECTION_CALLBACK {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField(
        "android.media.projection.MediaProjection\$MediaProjectionCallback",
        "this$0"
      ).leak(
        description = """
              MediaProjectionCallback is held by another process, and holds on to MediaProjection
              which has an activity as its context.
            """.trimIndent(),
        patternApplies = applyIf { sdkInt in 22..28 }
      )
    }
  },

  SPEECH_RECOGNIZER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "android.speech.SpeechRecognizer\$InternalListener", "this$0"
      ).leak(
        description = "Prior to Android 5, SpeechRecognizer.InternalListener was a non static inner"
          + " class and leaked the SpeechRecognizer which leaked an activity context."
          + " Fixed in AOSP: https://github.com/android/platform_frameworks_base/commit"
          + " /b37866db469e81aca534ff6186bdafd44352329b",
        patternApplies = applyIf { sdkInt < 21 }
      )
    }
  },

  ACCOUNT_MANAGER__AMS_TASK__RESPONSE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += nativeGlobalVariable("android.accounts.AccountManager\$AmsTask\$Response")
        .leak(
          description = """
          AccountManager.AmsTask.Response is a stub, and as all stubs it's held in memory by a
          native ref until the calling side gets GCed, which can happen long after the stub is no
          longer of use.
          https://issuetracker.google.com/issues/318303120
        """.trimIndent(),
          patternApplies = applyIf { sdkInt >= 5 }
        )
    }
  },

  MEDIA_SCANNER_CONNECTION {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.media.MediaScannerConnection", "mContext").leak(
        description = "The static method MediaScannerConnection.scanFile() takes an activity context"
          + " but the service might not disconnect after the activity has been destroyed."
          + " Tracked here: https://code.google.com/p/android/issues/detail?id=173788"
          + " Fix: Create an instance of MediaScannerConnection yourself and pass in the"
          + " application context. Call connect() and disconnect() manually.",
        patternApplies = applyIf { sdkInt <= 22 }
      )
    }
  },

  USER_MANAGER__SINSTANCE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.os.UserManager", "mContext").leak(
        description = "UserManager has a static sInstance field that creates an instance and caches it"
          + " the first time UserManager.get() is called. This instance is created with the"
          + " outer context (which is an activity base context)."
          + " Tracked here: https://code.google.com/p/android/issues/detail?id=173789"
          + " Introduced by: https://github.com/android/platform_frameworks_base/commit"
          + "/27db46850b708070452c0ce49daf5f79503fbde6"
          + " Fix: trigger a call to UserManager.get() in Application.onCreate(), so that the"
          + " UserManager instance gets cached with a reference to the application context.",
        patternApplies = applyIf { sdkInt in 18..25 }
      )
    }
  },

  APP_WIDGET_HOST_CALLBACKS {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.appwidget.AppWidgetHost\$Callbacks", "this$0").leak(
        description = "android.appwidget.AppWidgetHost\$Callbacks is a stub and is held in memory native"
          + " code. The reference to the `mContext` was not being cleared, which caused the"
          + " Callbacks instance to retain this reference"
          + " Fixed in AOSP: https://github.com/android/platform_frameworks_base/commit"
          + "/7a96f3c917e0001ee739b65da37b2fadec7d7765",
        patternApplies = applyIf { sdkInt < 22 }
      )
    }
  },

  AUDIO_MANAGER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.media.AudioManager$1", "this$0").leak(
        description = "Prior to Android M, VideoView required audio focus from AudioManager and"
          + " never abandoned it, which leaks the Activity context through the AudioManager."
          + " The root of the problem is that AudioManager uses whichever"
          + " context it receives, which in the case of the VideoView example is an Activity,"
          + " even though it only needs the application's context. The issue is fixed in"
          + " Android M, and the AudioManager now uses the application's context."
          + " Tracked here: https://code.google.com/p/android/issues/detail?id=152173"
          + " Fix: https://gist.github.com/jankovd/891d96f476f7a9ce24e2",
        patternApplies = applyIf { sdkInt <= 22 }
      )
    }
  },

  EDITTEXT_BLINK_MESSAGEQUEUE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.widget.Editor\$Blink", "this$0").leak(
        description = "The EditText Blink of the Cursor is implemented using a callback and Messages,"
          + " which trigger the display of the Cursor. If an AlertDialog or DialogFragment that"
          + " contains a blinking cursor is detached, a message is posted with a delay after the"
          + " dialog has been closed and as a result leaks the Activity."
          + " This can be fixed manually by calling TextView.setCursorVisible(false) in the"
          + " dismiss() method of the dialog."
          + " Tracked here: https://code.google.com/p/android/issues/detail?id=188551"
          + " Fixed in AOSP: https://android.googlesource.com/platform/frameworks/base/+"
          + "/5b734f2430e9f26c769d6af8ea5645e390fcf5af%5E%21/",
        patternApplies = applyIf { sdkInt <= 23 }
      )
    }
  },

  CONNECTIVITY_MANAGER__SINSTANCE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.net.ConnectivityManager", "sInstance").leak(
        description = "ConnectivityManager has a sInstance field that is set when the first"
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
          + "e0bef71662d81caaaa0d7214fb0bef5d39996a69",
        patternApplies = applyIf { sdkInt <= 23 }
      )
    }
  },

  ACCESSIBILITY_NODE_INFO__MORIGINALTEXT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "android.view.accessibility.AccessibilityNodeInfo", "mOriginalText"
      ).leak(
        description = "AccessibilityNodeInfo has a static sPool of AccessibilityNodeInfo. When"
          + " AccessibilityNodeInfo instances are released back in the pool,"
          + " AccessibilityNodeInfo.clear() does not clear the mOriginalText field, which"
          + " causes spans to leak which in turns causes TextView.ChangeWatcher to leak and the"
          + " whole view hierarchy. Introduced here: https://android.googlesource.com/platform/"
          + "frameworks/base/+/193520e3dff5248ddcf8435203bf99d2ba667219%5E%21/core/java/"
          + "android/view/accessibility/AccessibilityNodeInfo.java",
        patternApplies = applyIf { sdkInt in 26..27 }
      )
    }
  },

  ASSIST_STRUCTURE {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField("android.app.assist.AssistStructure\$ViewNodeText", "mText").leak(
        description = "AssistStructure (google assistant / autofill) holds on to text spannables" +
          " on the screen. TextView.ChangeWatcher and android.widget.Editor end up in spans and" +
          " typically hold on to the view hierarchy",
        patternApplies = applyIf { sdkInt >= 24 }
      )
    }
  },

  ACCESSIBILITY_ITERATORS {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField(
        "android.widget.AccessibilityIterators\$LineTextSegmentIterator", "mLayout"
      ).leak(
        description = "AccessibilityIterators holds on to text layouts which can hold on to spans" +
          " TextView.ChangeWatcher and android.widget.Editor end up in spans and" +
          " typically hold on to the view hierarchy",
        patternApplies = applyIf { sdkInt == 27 }
      )
    }
  },

  BIOMETRIC_PROMPT {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField(
        "android.hardware.biometrics.BiometricPrompt", "mFingerprintManager"
      ).leak(
        description = "BiometricPrompt holds on to a FingerprintManager which holds on to a " +
          "destroyed activity.",
        patternApplies = applyIf { sdkInt == 28 }
      )
    }
  },

  MAGNIFIER {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField(
        "android.widget.Magnifier\$InternalPopupWindow", "mCallback"
      ).leak(
        description = "android.widget.Magnifier.InternalPopupWindow registers a frame callback" +
          " on android.view.ThreadedRenderer.SimpleRenderer which holds it as a native" +
          " reference. android.widget.Editor\$InsertionHandleView registers an" +
          " OnOperationCompleteCallback on Magnifier.InternalPopupWindow. These references are" +
          " held after the activity has been destroyed.",
        patternApplies = applyIf { sdkInt == 28 }
      )
    }
  },

  BACKDROP_FRAME_RENDERER__MDECORVIEW {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "com.android.internal.policy.BackdropFrameRenderer", "mDecorView"
      ).leak(
        description = "When BackdropFrameRenderer.releaseRenderer() is called, there's an unknown case"
          + " where mRenderer becomes null but mChoreographer doesn't and the thread doesn't"
          + " stop and ends up leaking mDecorView which itself holds on to a destroyed"
          + " activity",
        patternApplies = applyIf { sdkInt in 24..26 }
      )
    }
  },

  VIEWLOCATIONHOLDER_ROOT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "android.view.ViewGroup\$ViewLocationHolder",
        "mRoot"
      ).leak(
        description = "In Android P, ViewLocationHolder has an mRoot field that is not cleared " +
          "in its clear() method. Introduced in https://github.com/aosp-mirror" +
          "/platform_frameworks_base/commit/86b326012813f09d8f1de7d6d26c986a909d Bug " +
          "report: https://issuetracker.google.com/issues/112792715",
        patternApplies = applyIf { sdkInt == 28 }
      )
    }
  },

  ACCESSIBILITY_NODE_ID_MANAGER {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField(
        "android.view.accessibility.AccessibilityNodeIdManager", "mIdsToViews"
      ).leak(
        description = """
              Android Q Beta added AccessibilityNodeIdManager which stores all views from their
              onAttachedToWindow() call, until detached. Unfortunately it's possible to trigger
              the view framework to call detach before attach (by having a view removing itself
              from its parent in onAttach, which then causes AccessibilityNodeIdManager to keep
              children view forever. Future releases of Q will hold weak references.
            """.trimIndent(),
        patternApplies = applyIf { sdkInt in 28..29 }
      )
    }
  },

  TEXT_TO_SPEECH {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      val description =
        ("TextToSpeech.shutdown() does not release its references to context objects." +
          " Furthermore, TextToSpeech instances cannot be garbage collected due to other process" +
          " keeping the references, resulting the context objects leaked." +
          " Developers might be able to mitigate the issue by passing application context" +
          " to TextToSpeech constructor." +
          " Tracked at: https://github.com/square/leakcanary/issues/1210 and" +
          " https://issuetracker.google.com/issues/129250419")
      references += instanceField("android.speech.tts.TextToSpeech", "mContext").leak(
        description = description,
        patternApplies = applyIf { sdkInt == 24 }
      )

      references += instanceField("android.speech.tts.TtsEngines", "mContext").leak(
        description = description,
        patternApplies = applyIf { sdkInt == 24 }
      )
    }
  },

  CONTROLLED_INPUT_CONNECTION_WRAPPER {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += nativeGlobalVariable(
        "android.view.inputmethod.InputMethodManager\$ControlledInputConnectionWrapper"
      )
        .leak(
          description = """
        ControlledInputConnectionWrapper is held by a global variable in native code.
      """.trimIndent(),
          patternApplies = ALWAYS
        )
    }
  },

  TOAST_TN {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += nativeGlobalVariable("android.widget.Toast\$TN")
        .leak(
          description = """
        Toast.TN is held by a global variable in native code due to an IPC call to show the toast.
      """.trimIndent(),
          patternApplies = ALWAYS
        )
    }
  },

  APPLICATION_PACKAGE_MANAGER__HAS_SYSTEM_FEATURE_QUERY {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField(
        "android.app.ApplicationPackageManager\$HasSystemFeatureQuery", "this\$0"
      ).leak(
        description = """
          In Android 11 DP 2 ApplicationPackageManager.HasSystemFeatureQuery was an inner class.
          Introduced in https://cs.android.com/android/_/android/platform/frameworks/base/+/89608118192580ffca026b5dacafa637a556d578
          Fixed in https://cs.android.com/android/_/android/platform/frameworks/base/+/1f771846c51148b7cb6283e6dc82a216ffaa5353
          Related blog: https://dev.to/pyricau/beware-packagemanager-leaks-223g
        """.trimIndent(),
        patternApplies = applyIf { sdkInt == 29 }
      )
    }
  },

  COMPANION_DEVICE_SERVICE__STUB {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField("android.companion.CompanionDeviceService\$Stub", "this\$0").leak(
        description = """
          Android 12 added android.companion.CompanionDeviceService, a bounded service extended by
          applications to which the system binds. CompanionDeviceService.Stub is an inner class
          that holds a reference to CompanionDeviceService, which itself holds a Stub instance
          that's not nullified after the service is destroyed.
          Introduced in https://android.googlesource.com/platform/frameworks/base/+/df69bbaf29e41d9df105612500c27be730feedfc
          Source code: https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/companion/CompanionDeviceService.java
        """.trimIndent(),
        patternApplies = applyIf { sdkInt == 31 }
      )
    }
  },

  RENDER_NODE_ANIMATOR {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += nativeGlobalVariable("android.graphics.animation.RenderNodeAnimator")
        .leak(
          description = """
          When a view is detached while a ripple animation is still playing on it, the native code
          doesn't properly end the RenderNodeAnimator, i.e. it doesn't call
          RenderNodeAnimator.callOnFinished and doesn't let go of the native ref, leading to a
          leak of the detached animated view.
          Tracked at: https://issuetracker.google.com/issues/229136453
        """.trimIndent(),
          patternApplies = applyIf { sdkInt in 31..32 }
        )
    }
  },

  PLAYER_BASE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += nativeGlobalVariable("android.media.PlayerBase\$1")
        .leak(
          description = """
          PlayerBase$1 implements IAppOpsCallback as an inner class and is held by a native
          ref, preventing subclasses of PlayerBase to be GC'd.
          Introduced in API 24: https://cs.android.com/android/_/android/platform/frameworks/base/+/3c86a343dfca1b9e2e28c240dc894f60709e392c
          Fixed in API 28: https://cs.android.com/android/_/android/platform/frameworks/base/+/aee6ee94675d56e71a42d52b16b8d8e5fa6ea3ff
        """.trimIndent(),
          patternApplies = applyIf { sdkInt in 24..27 }
        )
    }
  },

  WINDOW_ON_BACK_INVOKED_DISPATCHER__STUB {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += // Detected in Android 13 DP2, should be fixed in the next release.
        instanceField(
          "android.window.WindowOnBackInvokedDispatcher\$OnBackInvokedCallbackWrapper", "mCallback"
        ).leak(
          description = """
            WindowOnBackInvokedDispatcher.OnBackInvokedCallbackWrapper is an IPC stub that holds a
            reference to a callback which itself holds a view root. Another process is keeping the
            stub alive long after the view root has been detached.
            Tracked here: https://issuetracker.google.com/issues/229007483
          """.trimIndent(),
          patternApplies = applyIf { sdkInt == 32 && id == "TPP2.220218.008" }
        )
    }
  },

  CONNECTIVITY_MANAGER_CALLBACK_HANDLER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("ConnectivityManager\$CallbackHandler", "this\$0").leak(
        description = """
          ConnectivityManager.CallbackHandler instances can be held statically and hold
          a reference to ConnectivityManager instances created with a local context (e.g. activity).
          Filed: https://issuetracker.google.com/issues/258053962
          Fixed in API 34.
        """.trimIndent(),
        patternApplies = applyIf { sdkInt == 33 }
      )
    }
  },

  HOST_ADPU_SERVICE_MSG_HANDLER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "android.nfc.cardemulation.HostApduService\$MsgHandler", "this\$0"
      ).leak(
        description = """
          Destroyed HostApduService instances are held by a handler instance that lives longer
          than the service.
          Report: https://github.com/square/leakcanary/issues/2390
        """.trimIndent(),
        patternApplies = applyIf { sdkInt in 29..33 }
      )
    }
  },

  APP_OPS_MANAGER__CALLBACK_STUB {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += nativeGlobalVariable("android.app.AppOpsManager\$3")
        .leak(
          description = """
          Fix: Update androidx.core:core to 1.10.0-alpha01 or greater as it includes an Android 12
          fix for this leak on Android 12, see https://github.com/androidx/androidx/pull/435 .
          AppOpsManager\$3 implements IAppOpsActiveCallback.Stub and is held by a native ref long
          until the calling side gets GCed, which can happen long after the stub is no longer of
          use.
        """.trimIndent(),
          patternApplies = applyIf { sdkInt in 31..32 }
        )
    }
  },

  VIEW_GROUP__M_PRE_SORTED_CHILDREN {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.view.ViewGroup", "mPreSortedChildren").leak(
        description = """
          ViewGroup.mPreSortedChildren is used as a temporary list but not cleared after being
          used.
          Report: https://issuetracker.google.com/issues/178029590
          Fix: https://cs.android.com/android/_/android/platform/frameworks/base/+/73590c7751b9185137de962ba9ad9ff5a6e11e5d
        """.trimIndent(),
        patternApplies = applyIf { sdkInt == 30 }
      )
    }
  },

  VIEW_GROUP__M_CURRENT_DRAG_CHILD {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.view.ViewGroup", "mCurrentDragChild").leak(
        description = """
          ViewGroup.mCurrentDragChild keeps a reference to a view that was dragged after that view
          has been detached.
          Report: https://issuetracker.google.com/issues/170276524
        """.trimIndent(),
        patternApplies = applyIf { sdkInt in 29..30 }
      )
    }
  },

  VIEW_TOOLTIP_CALLBACK {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      // Note: the lambda order changes every release, so ideally we'd pull the dex code for
      // every release and look at the exact class name for the this::showHoverTooltip and the
      // this::hideTooltip lambda references in View.java . That's too much work, so we'll just
      // rely on reports from the field:
      // - API 33: android.view.View$$ExternalSyntheticLambda3.f$0
      references += instanceField("android.view.View\$\$ExternalSyntheticLambda3", "f\$0").leak(
        description = """
          When a View has tooltip text set, every hover event will fire a callback
          to hide the tooltip after a 15 second timeout. Since the callback holds
          a reference to the View, it will leak the View for that duration after
          the Activity is finished or the View is removed.
          https://cs.android.com/android/_/android/platform/frameworks/base/+/708dbe80902b963388c412f670c56ae00953273a
        """.trimIndent(),
        patternApplies = applyIf { sdkInt in 26..34 }
      )
    }
  },

  ACTIVITY_TRANSITION_STATE__M_EXITING_TO_VIEW {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.app.ActivityTransitionState", "mExitingToView").leak(
        description = """
          Shared element transition leak the view that was used in the transition.
          Report: https://issuetracker.google.com/issues/141132765
        """.trimIndent(),
        patternApplies = applyIf { sdkInt in 28..29 }
      )
    }
  },

  ANIMATION_HANDLER__ANIMATOR_REQUESTORS {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.animation.AnimationHandler", "mAnimatorRequestors").leak(
        description = """
          AnimationHandler is a singleton holding an activity ViewRootImpl requestor after the
          activity has been destroyed.
          Report: https://issuetracker.google.com/issues/258534826
        """.trimIndent(),
        patternApplies = applyIf { sdkInt == 33 }
      )
    }
  },

  FLIPPER__APPLICATION_DESCRIPTOR {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField(
        "com.facebook.flipper.plugins.inspector.descriptors.ApplicationDescriptor",
        "editedDelegates"
      ).leak(
        description = """
          Flipper's ApplicationDescriptor leaks root views after they've been detached.
          https://github.com/facebook/flipper/issues/4270
        """.trimIndent(),
        patternApplies = ALWAYS
      )
    }
  },

  AW_CONTENTS__A0 {
    override fun add(references: MutableList<ReferenceMatcher>) {
      staticField(
        "org.chromium.android_webview.AwContents",
        "A0"
      ).leak(
        description = """
          WindowAndroidWrapper has a strong ref to the context key so this breaks the WeakHashMap
          contracts and WeakHashMap is unable to perform its job of auto cleaning.
          https://github.com/square/leakcanary/issues/2538
        """.trimIndent(),
        patternApplies = ALWAYS
      )
    }
  },

  AW_CONTENTS_POSTED_CALLBACK {
    override fun add(references: MutableList<ReferenceMatcher>) {
      val description = "Android System WebView leak: " +
        "https://bugs.chromium.org/p/chromium/issues/detail?id=1499154"
      instanceField(
        "WV.R9",
        "e"
      ).leak(
        description = description,
        patternApplies = ALWAYS
      )
      instanceField(
        "WV.a6",
        "c"
      ).leak(
        description = description,
        patternApplies = ALWAYS
      )
      instanceField(
        "WV.H5",
        "c"
      ).leak(
        description = description,
        patternApplies = ALWAYS
      )
      instanceField(
        "WV.Y9",
        "e"
      ).leak(
        description = description,
        patternApplies = ALWAYS
      )
      instanceField(
        "WV.U4",
        "c"
      ).leak(
        description = description,
        patternApplies = ALWAYS
      )
    }
  },

  JOB_SERVICE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      nativeGlobalVariable(className = "android.app.job.JobService\$1")
        .leak(
          description = """
          JobService used to be leaked via a binder stub.
          Fix: https://cs.android.com/android/_/android/platform/frameworks/base/+/0796e9fb3dc2dd03fa5ff2053c63f14861cffa2f
        """.trimIndent(),
          patternApplies = applyIf { sdkInt < 24 }
        )
    }
  },

  DREAM_SERVICE {
    override fun add(references: MutableList<ReferenceMatcher>) {
      nativeGlobalVariable(className = "android.service.dreams.DreamService\$1")
        .leak(
          description = """
          DreamService leaks a binder stub.
          https://github.com/square/leakcanary/issues/2534
        """.trimIndent(),
          patternApplies = applyIf { sdkInt >= 33 }
        )
    }
  },

  UI_MODE_MANAGER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += nativeGlobalVariable("android.app.UiModeManager\$1")
        .leak(
          description = """
          UiModeManager$1 is an anonymous class of the IUiModeManagerCallback.Stub that is
          stored in memory native code. `this$0` is an instance of the UiModeManager that
          contains the `mContext` field, which is why retain this reference.
          Introduced in Android 14.0.0_r11: https://cs.android.com/android/_/android/platform/frameworks/base/+/cbbc772a41d20645ae434d74c482f3f4ad377e2c
          Fixed in Android 14.0.0_r16: https://cs.android.com/android/_/android/platform/frameworks/base/+/2bc364179327022d0f60224a1f2420349074c5d2
        """.trimIndent(),
          patternApplies = applyIf { sdkInt == 34 }
        )
    }
  },

  // ######## Manufacturer specific known leaks ########

  // SAMSUNG

  SPEN_GESTURE_MANAGER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField(
        "com.samsung.android.smartclip.SpenGestureManager", "mContext"
      ).leak(
        description = "SpenGestureManager has a static mContext field that leaks a reference to the" + " activity. Yes, a STATIC mContext field.",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 19 }
      )
    }
  },

  CLIPBOARD_UI_MANAGER__SINSTANCE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.sec.clipboard.ClipboardUIManager", "mContext").leak(
        description = "ClipboardUIManager is a static singleton that leaks an activity context."
          + " Fix: trigger a call to ClipboardUIManager.getInstance() in Application.onCreate()"
          + " , so that the ClipboardUIManager instance gets cached with a reference to the"
          + " application context. Example: https://gist.github.com/cypressious/"
          + "91c4fb1455470d803a602838dfcd5774",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt in 19..21 }
      )
    }
  },

  SEM_CLIPBOARD_MANAGER__MCONTEXT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      val description = """
         SemClipboardManager inner classes are held by native references due to IPC calls
      """.trimIndent()
      references += nativeGlobalVariable(
        "com.samsung.android.content.clipboard.SemClipboardManager$1"
      )
        .leak(
          description = description,
          patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt in 19..28 }
        )
      references += nativeGlobalVariable(
        "com.samsung.android.content.clipboard.SemClipboardManager$3"
      )
        .leak(
          description = description,
          patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt in 19..28 }
        )
    }
  },

  CLIPBOARD_EX_MANAGER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.sec.clipboard.ClipboardExManager", "mContext").leak(
        description = "android.sec.clipboard.ClipboardExManager\$IClipboardDataPasteEventImpl\$1" +
          " is a native callback that holds IClipboardDataPasteEventImpl which holds" +
          " ClipboardExManager which has a destroyed activity as mContext",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 23 }
      )
      references += instanceField(
        "android.sec.clipboard.ClipboardExManager", "mPersonaManager"
      ).leak(
        description = "android.sec.clipboard.ClipboardExManager\$IClipboardDataPasteEventImpl\$1" +
          " is a native callback that holds IClipboardDataPasteEventImpl which holds" +
          " ClipboardExManager which holds PersonaManager which has a destroyed activity as" +
          " mContext",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 23 }
      )
      references += instanceField(
        "android.widget.TextView\$IClipboardDataPasteEventImpl", "this\$0"
      ).leak(
        description = "TextView\$IClipboardDataPasteEventImpl\$1 is held by a native ref, and" +
          " IClipboardDataPasteEventImpl ends up leaking a detached textview",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 22 }
      )
    }
  },

  SEM_EMERGENCY_MANAGER__MCONTEXT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "com.samsung.android.emergencymode.SemEmergencyManager", "mContext"
      ).leak(
        description = "SemEmergencyManager is a static singleton that leaks a DecorContext." +
          " Fix: https://gist.github.com/jankovd/a210460b814c04d500eb12025902d60d",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt in 19..24 }
      )
    }
  },

  SEM_PERSONA_MANAGER {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField(
        "com.samsung.android.knox.SemPersonaManager", "mContext"
      ).leak(
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 24 }
      )
    }
  },

  SEM_APP_ICON_SOLUTION {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField(
        "android.app.SemAppIconSolution", "mContext"
      ).leak(
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt in 28..29 }
      )
    }
  },

  AW_RESOURCE__SRESOURCES {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      // AwResource#setResources() is called with resources that hold a reference to the
      // activity context (instead of the application context) and doesn't clear it.
      // Not sure what's going on there, input welcome.
      references += staticField(
        "com.android.org.chromium.android_webview.AwResource", "sResources"
      ).leak("", applyIf { manufacturer == SAMSUNG && sdkInt == 19 })
    }
  },

  TEXT_VIEW__MLAST_HOVERED_VIEW {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.widget.TextView", "mLastHoveredView").leak(
        description = "mLastHoveredView is a static field in TextView that leaks the last hovered view.",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt in 19..31 }
      )
    }
  },

  PERSONA_MANAGER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.os.PersonaManager", "mContext").leak(
        description = "android.app.LoadedApk.mResources has a reference to"
          + " android.content.res.Resources.mPersonaManager which has a reference to"
          + " android.os.PersonaManager.mContext which is an activity.",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 19 }
      )
    }
  },

  RESOURCES__MCONTEXT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.content.res.Resources", "mContext").leak(
        description = "In AOSP the Resources class does not have a context."
          + " Here we have ZygoteInit.mResources (static field) holding on to a Resources"
          + " instance that has a context that is the activity."
          + " Observed here: https://github.com/square/leakcanary/issues/1#issue-74450184",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 19 }
      )
    }
  },

  VIEW_CONFIGURATION__MCONTEXT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.view.ViewConfiguration", "mContext").leak(
        description = "In AOSP the ViewConfiguration class does not have a context."
          + " Here we have ViewConfiguration.sConfigurations (static field) holding on to a"
          + " ViewConfiguration instance that has a context that is the activity."
          + " Observed here: https://github.com/square/leakcanary/issues"
          + "/1#issuecomment-100324683",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 19 }
      )
    }
  },

  AUDIO_MANAGER__MCONTEXT_STATIC {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.media.AudioManager", "mContext_static").leak(
        description = "Samsung added a static mContext_static field to AudioManager, holds a reference"
          + " to the activity."
          + " Observed here: https://github.com/square/leakcanary/issues/32",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 19 }
      )
    }
  },

  ACTIVITY_MANAGER_MCONTEXT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.app.ActivityManager", "mContext").leak(
        description = "Samsung added a static mContext field to ActivityManager, holds a reference"
          + " to the activity."
          + " Observed here: https://github.com/square/leakcanary/issues/177 Fix in comment:"
          + " https://github.com/square/leakcanary/issues/177#issuecomment-222724283",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt in 22..23 }
      )
    }
  },

  STATIC_MTARGET_VIEW {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.widget.TextView", "mTargetView").leak(
        description = "Samsung added a static mTargetView field to TextView which holds on to detached views.",
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt == 27 }
      )
    }
  },

  MULTI_WINDOW_DECOR_SUPPORT__MWINDOW {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "com.android.internal.policy.MultiWindowDecorSupport", "mWindow"
      ).leak(
        description = """DecorView isn't leaking but its mDecorViewSupport field holds
            |a MultiWindowDecorSupport which has a mWindow field which holds a leaking PhoneWindow.
            |DecorView.mDecorViewSupport doesn't exist in AOSP.
            |Filed here: https://github.com/square/leakcanary/issues/1819
          """.trimMargin(),
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt in 26..29 }
      )
    }
  },

  IMM_CURRENT_INPUT_CONNECTION {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField(
        "android.view.inputmethod.InputMethodManager", "mCurrentInputConnection"
      ).leak(
        description = """
              InputMethodManager keeps its EditableInputConnection after the activity has been
              destroyed.
              Filed here: https://github.com/square/leakcanary/issues/2300
            """.trimIndent(),
        patternApplies = applyIf { manufacturer == SAMSUNG && sdkInt in 28..30 }
      )
    }
  },

  // OTHER MANUFACTURERS

  GESTURE_BOOST_MANAGER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.gestureboost.GestureBoostManager", "mContext").leak(
        description = "GestureBoostManager is a static singleton that leaks an activity context." +
          " Fix: https://github.com/square/leakcanary/issues/696#issuecomment-296420756",
        patternApplies = applyIf { manufacturer == HUAWEI && sdkInt in 24..25 }
      )
    }
  },

  BUBBLE_POPUP_HELPER__SHELPER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.widget.BubblePopupHelper", "sHelper").leak(
        description = "A static helper for EditText bubble popups leaks a reference to the latest" + " focused view.",
        patternApplies = applyIf { manufacturer == LG && sdkInt in 19..22 }
      )
    }
  },

  LGCONTEXT__MCONTEXT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("com.lge.systemservice.core.LGContext", "mContext").leak(
        description = "LGContext is a static singleton that leaks an activity context.",
        patternApplies = applyIf { manufacturer == LG && sdkInt == 21 }
      )
    }
  },

  SMART_COVER_MANAGER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("com.lge.systemservice.core.SmartCoverManager", "mContext").leak(
        description = "SmartCoverManager\$CallbackRegister is a callback held by a native ref," +
          " and SmartCoverManager ends up leaking an activity context.",
        patternApplies = applyIf { manufacturer == LG && sdkInt == 27 }
      )
    }
  },

  IMM_LAST_FOCUS_VIEW {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "android.view.inputmethod.InputMethodManager", "mLastFocusView"
      ).leak(
        description = """
          InputMethodManager has a mLastFocusView field that doesn't get cleared when the last
          focused view becomes detached.
        """.trimIndent(),
        patternApplies = applyIf { manufacturer == LG && sdkInt == 29 }
      )
    }
  },

  MAPPER_CLIENT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "com.nvidia.ControllerMapper.MapperClient\$ServiceClient", "this$0"
      ).leak(
        description = "Not sure exactly what ControllerMapper is about, but there is an anonymous"
          + " Handler in ControllerMapper.MapperClient.ServiceClient, which leaks"
          + " ControllerMapper.MapperClient which leaks the activity context.",
        patternApplies = applyIf { manufacturer == NVIDIA && sdkInt == 19 }
      )
    }
  },

  SYSTEM_SENSOR_MANAGER__MAPPCONTEXTIMPL {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.hardware.SystemSensorManager", "mAppContextImpl").leak(
        description = "SystemSensorManager stores a reference to context"
          + " in a static field in its constructor."
          + " Fix: use application context to get SensorManager",
        patternApplies = applyIf {
          (manufacturer == LENOVO && sdkInt == 19) ||
            (manufacturer == VIVO && sdkInt == 22)
        }
      )
    }
  },

  INSTRUMENTATION_RECOMMEND_ACTIVITY {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.app.Instrumentation", "mRecommendActivity").leak(
        description = "Instrumentation would leak com.android.internal.app.RecommendActivity (in"
          + " framework.jar) in Meizu FlymeOS 4.5 and above, which is based on Android 5.0 and "
          + " above",
        patternApplies = applyIf { manufacturer == MEIZU && sdkInt in 21..22 }
      )
    }
  },

  DEVICE_POLICY_MANAGER__SETTINGS_OBSERVER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField(
        "android.app.admin.DevicePolicyManager\$SettingsObserver", "this$0"
      ).leak(
        description = "DevicePolicyManager keeps a reference to the context it has been created with"
          + " instead of extracting the application context. In this Motorola build,"
          + " DevicePolicyManager has an inner SettingsObserver class that is a content"
          + " observer, which is held into memory by a binder transport object.",
        patternApplies = applyIf { manufacturer == MOTOROLA && sdkInt in 19..22 }
      )
    }
  },

  EXTENDED_STATUS_BAR_MANAGER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.app.ExtendedStatusBarManager", "mContext").leak(
        description = """
            ExtendedStatusBarManager has a mContext field which references a decor context which
            references a destroyed activity.
          """.trimIndent(),
        patternApplies = applyIf { manufacturer == SHARP && sdkInt >= 30 }
      )
    }
  },

  OEM_SCENE_CALL_BLOCKER {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("com.oneplus.util.OemSceneCallBlocker", "sContext").leak(
        description = """
            OemSceneCallBlocker has a sContext static field which holds on to an activity instance.
          """.trimIndent(),
        patternApplies = applyIf { manufacturer == ONE_PLUS && sdkInt == 28 }
      )
    }
  },

  PERF_MONITOR_LAST_CALLBACK {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += staticField("android.os.PerfMonitor", "mLastCallback").leak(
        description = """
            PerfMonitor has a mLastCallback static field which holds on to View.PerformClick.
          """.trimIndent(),
        patternApplies = applyIf { manufacturer == ONE_PLUS && sdkInt == 30 }
      )
    }
  },

  RAZER_TEXT_KEY_LISTENER__MCONTEXT {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += instanceField("android.text.method.TextKeyListener", "mContext").leak(
        description = """
            In AOSP, TextKeyListener instances are held in a TextKeyListener.sInstances static
            array. The Razer implementation added a mContext field, creating activity leaks.
          """.trimIndent(),
        patternApplies = applyIf { manufacturer == RAZER && sdkInt == 28 }
      )
    }
  },

  XIAMI__RESOURCES_IMPL {
    override fun add(references: MutableList<ReferenceMatcher>) {
      val copycatManufacturers = listOf(
        HMD_GLOBAL,
        INFINIX,
        LENOVO,
        XIAOMI,
        TES,
        REALME
      )
      references += staticField("android.content.res.ResourcesImpl", "mAppContext").leak(
        description = """
          A fork of Android added a static mAppContext field to the ResourcesImpl class
          and that field ends up referencing lower contexts (e.g. service). Several Android
          manufacturers seem to be using the same broken Android fork sources.
        """.trimIndent(),
        patternApplies = applyIf {
          copycatManufacturers.contains(manufacturer) &&
            sdkInt >= 30
        }
      )
    }
  },

  // ######## Ignored references (not leaks) ########

  REFERENCES {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references.addAll(
        ReferenceMatcher.fromListBuilders(EnumSet.of(JdkReferenceMatchers.REFERENCES))
      )
    }
  },

  FINALIZER_WATCHDOG_DAEMON {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references.addAll(
        ReferenceMatcher.fromListBuilders(
          EnumSet.of(JdkReferenceMatchers.FINALIZER_WATCHDOG_DAEMON)
        )
      )
    }
  },

  MAIN {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references.addAll(
        ReferenceMatcher.fromListBuilders(EnumSet.of(JdkReferenceMatchers.MAIN))
      )
    }
  },

  LEAK_CANARY_THREAD {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      references += javaLocal(LEAK_CANARY_THREAD_NAME).ignored(
        patternApplies = ALWAYS
      )
    }
  },

  LEAK_CANARY_HEAP_DUMPER {
    override fun add(references: MutableList<ReferenceMatcher>) {
      // Holds on to the resumed activity (which is never destroyed), so this will not cause leaks
      // but may surface on the path when a resumed activity holds on to destroyed objects.
      // Would have a path that doesn't include LeakCanary instead.
      references += instanceField(
        "leakcanary.internal.InternalLeakCanary", "resumedActivity"
      ).ignored(patternApplies = ALWAYS)
    }
  },

  LEAK_CANARY_INTERNAL {
    override fun add(references: MutableList<ReferenceMatcher>) {
      references += instanceField("leakcanary.internal.InternalLeakCanary", "application").ignored(
        patternApplies = ALWAYS
      )
    }
  },

  EVENT_RECEIVER__MMESSAGE_QUEUE {
    override fun add(
      references: MutableList<ReferenceMatcher>
    ) {
      //  DisplayEventReceiver keeps a reference message queue object so that it is not GC'd while
      // the native peer of the receiver is using them.
      // The main thread message queue is held on by the main Looper, but that might be a longer
      // path. Let's not confuse people with a shorter path that is less meaningful.
      references += instanceField(
        "android.view.Choreographer\$FrameDisplayEventReceiver", "mMessageQueue"
      ).ignored(patternApplies = ALWAYS)
    }
  },
  ;

  companion object {
    private const val LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump"
    const val SAMSUNG = "samsung"
    const val MOTOROLA = "motorola"
    const val LENOVO = "LENOVO"
    const val LG = "LGE"
    const val NVIDIA = "NVIDIA"
    const val MEIZU = "Meizu"
    const val ONE_PLUS = "OnePlus"
    const val HUAWEI = "HUAWEI"
    const val VIVO = "vivo"
    const val RAZER = "Razer"
    const val SHARP = "SHARP"
    const val XIAOMI = "Xiaomi"
    const val HMD_GLOBAL = "HMD Global"
    const val INFINIX = "INFINIX"
    const val TES = "TES"
    const val REALME = "realme"

    /**
     * Returns a list of [ReferenceMatcher] that only contains [IgnoredReferenceMatcher] and no
     * [LibraryLeakReferenceMatcher].
     */
    @JvmStatic
    val ignoredReferencesOnly: List<ReferenceMatcher>
      get() = ReferenceMatcher.fromListBuilders(
        EnumSet.of(
          REFERENCES,
          FINALIZER_WATCHDOG_DAEMON,
          MAIN,
          LEAK_CANARY_THREAD,
          EVENT_RECEIVER__MMESSAGE_QUEUE
        )
      )

    /**
     * @see [AndroidReferenceMatchers]
     */
    @JvmStatic
    val appDefaults: List<ReferenceMatcher>
      get() = ReferenceMatcher.fromListBuilders(EnumSet.allOf(AndroidReferenceMatchers::class.java))

    /**
     * Builds a list of [ReferenceMatcher] from the [referenceMatchers] set of
     * [AndroidReferenceMatchers].
     */
    @Deprecated(
      "Use ReferenceMatcher.fromListBuilders instead.",
      ReplaceWith("ReferenceMatcher.fromListBuilders")
    )
    @JvmStatic
    fun buildKnownReferences(referenceMatchers: Set<AndroidReferenceMatchers>): List<ReferenceMatcher> {
      return ReferenceMatcher.fromListBuilders(referenceMatchers)
    }

    /**
     * Creates a [LibraryLeakReferenceMatcher] that matches a [StaticFieldPattern].
     * [description] should convey what we know about this library leak.
     */
    @Deprecated(
      "Use ReferencePattern.staticField instead",
      ReplaceWith("ReferencePattern.staticField")
    )
    @JvmStatic
    fun staticFieldLeak(
      className: String,
      fieldName: String,
      description: String = "",
      patternApplies: AndroidBuildMirror.() -> Boolean = { true }
    ): LibraryLeakReferenceMatcher {
      return staticField(className, fieldName).leak(description, applyIf(patternApplies))
    }

    /**
     * Creates a [LibraryLeakReferenceMatcher] that matches a [InstanceFieldPattern].
     * [description] should convey what we know about this library leak.
     */
    @Deprecated(
      "Use ReferencePattern.instanceField instead",
      ReplaceWith("ReferencePattern.instanceField")
    )
    @JvmStatic
    fun instanceFieldLeak(
      className: String,
      fieldName: String,
      description: String = "",
      patternApplies: AndroidBuildMirror.() -> Boolean = { true }
    ): LibraryLeakReferenceMatcher {
      return instanceField(className, fieldName).leak(
        description = description, patternApplies = applyIf(patternApplies)
      )
    }

    @Deprecated(
      "Use ReferencePattern.nativeGlobalVariable instead",
      ReplaceWith("ReferencePattern.nativeGlobalVariable")
    )
    @JvmStatic
    fun nativeGlobalVariableLeak(
      className: String,
      description: String = "",
      patternApplies: AndroidBuildMirror.() -> Boolean = { true }
    ): LibraryLeakReferenceMatcher {
      return nativeGlobalVariable(className)
        .leak(description, applyIf(patternApplies))
    }

    /**
     * Creates a [IgnoredReferenceMatcher] that matches a [InstanceFieldPattern].
     */
    @Deprecated(
      "Use ReferencePattern.instanceField instead",
      ReplaceWith("ReferencePattern.instanceField")
    )
    @JvmStatic
    fun ignoredInstanceField(
      className: String,
      fieldName: String
    ): IgnoredReferenceMatcher {
      return instanceField(className, fieldName).ignored(patternApplies = ALWAYS)
    }

    /**
     * Creates a [IgnoredReferenceMatcher] that matches a [JavaLocalPattern].
     */
    @Deprecated(
      "Use ReferencePattern.javaLocal instead",
      ReplaceWith("ReferencePattern.javaLocal")
    )
    @JvmStatic
    fun ignoredJavaLocal(
      threadName: String
    ): IgnoredReferenceMatcher {
      return javaLocal(threadName).ignored(patternApplies = ALWAYS)
    }
  }
}

