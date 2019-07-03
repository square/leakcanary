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
package leakcanary

import leakcanary.Exclusion.ExclusionType
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.Exclusion.ExclusionType.JavaLocalExclusion
import leakcanary.Exclusion.ExclusionType.StaticFieldExclusion
import leakcanary.Exclusion.Status
import leakcanary.Exclusion.Status.NEVER_REACHABLE
import leakcanary.Exclusion.Status.WEAKLY_REACHABLE
import leakcanary.Exclusion.Status.WONT_FIX_LEAK
import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.util.EnumSet

/**
 * The first parameter is the value of android.os.Build.MANUFACTURER and the second
 * parameter is the value of android.os.Build.VERSION.SDK_INT. Returns true if the known
 * reference should be applied on the provided manufacturer / sdk int.
 */
typealias BuildFilter = (String, Int) -> Boolean

/**
 * This class is a work in progress. You can help by reporting leak traces that seem to be caused
 * by the Android SDK, here: https://github.com/square/leakcanary/issues/new
 *
 * We filter on SDK versions and Manufacturers because many of those leaks are specific to a given
 * manufacturer implementation, they usually share their builds across multiple models, and the
 * leaks eventually get fixed in newer versions.
 *
 * Most app developers should use [appDefaults]. However, you can also pick the
 * leaks you want to ignore by creating an [EnumSet] that matches your needs and calling
 * [buildKnownReferences]
 */
class AndroidKnownReference private constructor(
  val type: ExclusionType,
  val reason: String?,
  val status: Status,
  val filter: BuildFilter
) {

  companion object {
    private const val LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump"
    const val SAMSUNG = "samsung"
    const val MOTOROLA = "motorola"
    const val LENOVO = "LENOVO"
    const val LG = "LGE"
    const val NVIDIA = "NVIDIA"
    const val MEIZU = "Meizu"
    const val HUAWEI = "HUAWEI"
    const val VIVO = "vivo"

    /**
     * This returns the references in the leak path that should be ignored by all on Android.
     */
    val androidDefaults: Set<AndroidKnownReference>
      get() = buildKnownReferences(
          EnumSet.of(
              Defaults.REFERENCES,
              Defaults.FINALIZER_WATCHDOG_DAEMON,
              Defaults.MAIN,
              Defaults.LEAK_CANARY_THREAD,
              Defaults.EVENT_RECEIVER__MMESSAGE_QUEUE
          )
      )

    /**
     * This returns the references in the leak path that can be ignored for app developers. This
     * doesn't mean there is no memory leak, to the contrary. However, some leaks are caused by bugs
     * in AOSP or manufacturer forks of AOSP. In such cases, there is very little we can do as app
     * developers except by resorting to serious hacks, so we remove the noise caused by those leaks.
     */
    val appDefaults: Set<AndroidKnownReference>
      get() = buildKnownReferences(EnumSet.allOf(Defaults::class.java))

    fun buildKnownReferences(defaults: Set<Defaults>): Set<AndroidKnownReference> {
      val resultSet = mutableSetOf<AndroidKnownReference>()
      defaults.forEach {
        it.add(resultSet)
      }
      return resultSet
    }

    private val NO_FILTER: BuildFilter = { _, _ ->
      true
    }

    fun staticField(
      className: String,
      fieldName: String,
      reason: String? = null,
      status: Status = WONT_FIX_LEAK,
      filter: BuildFilter = NO_FILTER
    ) = AndroidKnownReference(StaticFieldExclusion(className, fieldName), reason, status, filter)

    fun instanceField(
      className: String,
      fieldName: String,
      reason: String? = null,
      status: Status = WONT_FIX_LEAK,
      filter: BuildFilter = NO_FILTER
    ) = AndroidKnownReference(InstanceFieldExclusion(className, fieldName), reason, status, filter)

    fun javaLocal(
      threadName: String,
      reason: String? = null,
      status: Status = WONT_FIX_LEAK,
      filter: BuildFilter = NO_FILTER
    ) = AndroidKnownReference(JavaLocalExclusion(threadName), reason, status, filter)

    /**
     * Used to transform [AndroidKnownReference] instances to [Exclusion] which is what
     * the heap analyzer actually uses. The resulting set should only be used
     * for exactly one analysis, it caches the Android version information.
     */
    fun mapToExclusions(knownReferences: Set<AndroidKnownReference>): List<Exclusion> {
      val buildMirror = BuildMirror()
      return knownReferences.map { knownReference ->
        Exclusion(
            knownReference.type, knownReference.reason, knownReference.status,
            buildMirror.wrapFilter(knownReference.filter)
        )
      }
    }
  }

  enum class Defaults {
    // ######## Android Framework known leaks ########

    ACTIVITY_CLIENT_RECORD__NEXT_IDLE {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.app.ActivityThread\$ActivityClientRecord", "nextIdle",
            reason = "Android AOSP sometimes keeps a reference to a destroyed activity as a"
                + " nextIdle client record in the android.app.ActivityThread.mActivities map."
                + " Not sure what's going on there, input welcome."
        ) { _, sdkInt ->
          sdkInt in 19..27
        }
      }
    },

    SPAN_CONTROLLER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        val reason =
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

        references += instanceField(
            "android.widget.Editor\$SpanController", "this$0", reason
        ) { _, sdkInt ->
          sdkInt <= 19
        }

        references += instanceField(
            "android.widget.Editor\$EasyEditSpanController", "this$0", reason
        ) { _, sdkInt ->
          sdkInt <= 19
        }
      }
    },

    MEDIA_SESSION_LEGACY_HELPER__SINSTANCE {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references +=
          staticField(
              "android.media.session.MediaSessionLegacyHelper", "sInstance",
              reason = "MediaSessionLegacyHelper is a static singleton that is lazily instantiated and"
                  + " keeps a reference to the context it's given the first time"
                  + " MediaSessionLegacyHelper.getHelper() is called."
                  + " This leak was introduced in android-5.0.1_r1 and fixed in Android 5.1.0_r1 by"
                  + " calling context.getApplicationContext()."
                  + " Fix: https://github.com/android/platform_frameworks_base/commit"
                  + "/9b5257c9c99c4cb541d8e8e78fb04f008b1a9091"
                  + " To fix this, you could call MediaSessionLegacyHelper.getHelper() early"
                  + " in Application.onCreate() and pass it the application context."
          ) { _, sdkInt ->
            sdkInt == 21
          }
      }
    },

    TEXT_LINE__SCACHED {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += staticField(
            "android.text.TextLine", "sCached",
            reason = "TextLine.sCached is a pool of 3 TextLine instances. TextLine.recycle() has had"
                + " at least two bugs that created memory leaks by not correctly clearing the"
                + " recycled TextLine instances. The first was fixed in android-5.1.0_r1:"
                + " https://github.com/android/platform_frameworks_base/commit"
                + "/893d6fe48d37f71e683f722457bea646994a10"
                + " The second was fixed, not released yet:"
                + " https://github.com/android/platform_frameworks_base/commit"
                + "/b3a9bc038d3a218b1dbdf7b5668e3d6c12be5e"
                + " To fix this, you could access TextLine.sCached and clear the pool every now"
                + " and then (e.g. on activity destroy)."
        ) { _, sdkInt ->
          sdkInt <= 22
        }
      }
    },

    BLOCKING_QUEUE {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        val reason = ("A thread waiting on a blocking queue will leak the last"
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
            + " has been shown to happen in both Dalvik and ART.")

        references += instanceField("android.os.Message", "obj", reason)
        references += instanceField("android.os.Message", "next", reason)
        references += instanceField("android.os.Message", "target", reason)
      }
    },

    INPUT_METHOD_MANAGER_IS_TERRIBLE {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        val reason = ("When we detach a view that receives keyboard input, the InputMethodManager"
            + " leaks a reference to it until a new view asks for keyboard input."
            + " Tracked here: https://code.google.com/p/android/issues/detail?id=171190"
            + " Hack: https://gist.github.com/pyricau/4df64341cc978a7de414")

        references += instanceField(
            "android.view.inputmethod.InputMethodManager", "mNextServedView", reason
        ) { _, sdkInt ->
          sdkInt in 15..27
        }

        references += instanceField(
            "android.view.inputmethod.InputMethodManager", "mServedView", reason
        ) { _, sdkInt ->
          sdkInt in 15..27
        }

        references += instanceField(
            "android.view.inputmethod.InputMethodManager", "mServedInputConnection", reason
        ) { _, sdkInt ->
          sdkInt in 15..27
        }

        references += instanceField(
            "android.view.inputmethod.InputMethodManager", "mLastSrvView"
            ,
            reason =
            "HUAWEI added a mLastSrvView field to InputMethodManager" + " that leaks a reference to the last served view."
        ) { manufacturer, sdkInt ->
          manufacturer == HUAWEI && sdkInt in 23..27
        }

        references += instanceField(
            "android.view.inputmethod.InputMethodManager", "mCurRootView",
            reason = "The singleton InputMethodManager is holding a reference to mCurRootView long"
                + " after the activity has been destroyed."
                + " Observed on ICS MR1: https://github.com/square/leakcanary/issues/1"
                + "#issuecomment-100579429"
                + " Hack: https://gist.github.com/pyricau/4df64341cc978a7de414"
        ) { _, sdkInt ->
          sdkInt in 15..28
        }

        references += instanceField(
            "android.view.inputmethod.InputMethodManager", "mImeInsetsConsumer",
            reason = """
              Android Q Beta has a leak where InputMethodManager.mImeInsetsConsumer isn't set to
              null when the activity is destroyed.
            """.trimIndent()
        ) { _, sdkInt ->
          sdkInt == 28
        }

        references += instanceField(
            "android.view.inputmethod.InputMethodManager", "mCurrentInputConnection",
            reason = """
              In Android Q Beta InputMethodManager keeps its EditableInputConnection after the
              activity has been destroyed.
            """.trimIndent()
        ) { _, sdkInt ->
          sdkInt == 28
        }
      }
    },

    LAYOUT_TRANSITION {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.animation.LayoutTransition$1", "val\$parent",
            reason = "LayoutTransition leaks parent ViewGroup through"
                + " ViewTreeObserver.OnPreDrawListener When triggered, this leaks stays until the"
                + " window is destroyed. Tracked here:"
                + " https://code.google.com/p/android/issues/detail?id=171830"
        ) { _, sdkInt ->
          sdkInt in 14..22
        }
      }
    },

    SPELL_CHECKER_SESSION {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.view.textservice.SpellCheckerSession$1", "this$0",
            reason = "SpellCheckerSessionListenerImpl.mHandler is leaking destroyed Activity when the"
                + " SpellCheckerSession is closed before the service is connected."
                + " Tracked here: https://code.google.com/p/android/issues/detail?id=172542"
        ) { _, sdkInt ->
          sdkInt in 16..24
        }
      }
    },

    SPELL_CHECKER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.widget.SpellChecker$1", "this$0",
            reason = "SpellChecker holds on to a detached view that points to a destroyed activity."
                + " mSpellRunnable is being enqueued, and that callback should be removed when "
                + " closeSession() is called. Maybe closeSession() wasn't called, or maybe it was "
                + " called after the view was detached."
        ) { _, sdkInt ->
          sdkInt == 22
        }
      }
    },

    ACTIVITY_CHOOSE_MODEL {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        val reason = ("ActivityChooserModel holds a static reference to the last set"
            + " ActivityChooserModelPolicy which can be an activity context."
            + " Tracked here: https://code.google.com/p/android/issues/detail?id=172659"
            + " Hack: https://gist.github.com/andaag/b05ab66ed0f06167d6e0")


        references += instanceField(
            "android.support.v7.internal.widget.ActivityChooserModel",
            "mActivityChoserModelPolicy",
            reason = reason
        ) { _, sdkInt ->
          sdkInt in 15..22
        }

        references += instanceField(
            "android.widget.ActivityChooserModel", "mActivityChoserModelPolicy",
            reason = reason
        )
      }
    },

    MEDIA_PROJECTION_CALLBACK {
      override fun add(references: MutableSet<AndroidKnownReference>) {
        references += instanceField("android.media.projection.MediaProjection\$MediaProjectionCallback",
            "this$0", reason = """
              MediaProjectionCallback is held by another process, and holds on to MediaProjection
              which has an activity as its context.
            """.trimIndent()) { _, sdkInt ->
          sdkInt in 22..28
        }
      }

    },

    SPEECH_RECOGNIZER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.speech.SpeechRecognizer\$InternalListener", "this$0"
            ,
            reason = "Prior to Android 5, SpeechRecognizer.InternalListener was a non static inner"
                + " class and leaked the SpeechRecognizer which leaked an activity context."
                + " Fixed in AOSP: https://github.com/android/platform_frameworks_base/commit"
                + " /b37866db469e81aca534ff6186bdafd44352329b"
        ) { _, sdkInt ->
          sdkInt < 21
        }
      }
    },

    ACCOUNT_MANAGER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.accounts.AccountManager\$AmsTask\$Response", "this$1"
            ,
            reason =
            "AccountManager\$AmsTask\$Response is a stub and is held in memory by native code,"
                + " probably because the reference to the response in the other process hasn't been"
                + " cleared."
                + " AccountManager\$AmsTask is holding on to the activity reference to use for"
                + " launching a new sub- Activity."
                + " Tracked here: https://code.google.com/p/android/issues/detail?id=173689"
                + " Fix: Pass a null activity reference to the AccountManager methods and then deal"
                + " with the returned future to to get the result and correctly start an activity"
                + " when it's available."
        ) { _, sdkInt ->
          sdkInt <= 27
        }
      }
    },

    MEDIA_SCANNER_CONNECTION {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.media.MediaScannerConnection", "mContext",

            reason =
            "The static method MediaScannerConnection.scanFile() takes an activity context"
                + " but the service might not disconnect after the activity has been destroyed."
                + " Tracked here: https://code.google.com/p/android/issues/detail?id=173788"
                + " Fix: Create an instance of MediaScannerConnection yourself and pass in the"
                + " application context. Call connect() and disconnect() manually."
        ) { _, sdkInt ->
          sdkInt <= 22
        }
      }
    },

    USER_MANAGER__SINSTANCE {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.os.UserManager", "mContext",
            reason =
            "UserManager has a static sInstance field that creates an instance and caches it"
                + " the first time UserManager.get() is called. This instance is created with the"
                + " outer context (which is an activity base context)."
                + " Tracked here: https://code.google.com/p/android/issues/detail?id=173789"
                + " Introduced by: https://github.com/android/platform_frameworks_base/commit"
                + "/27db46850b708070452c0ce49daf5f79503fbde6"
                + " Fix: trigger a call to UserManager.get() in Application.onCreate(), so that the"
                + " UserManager instance gets cached with a reference to the application context."
        ) { _, sdkInt ->
          sdkInt in 18..25
        }
      }
    },

    APP_WIDGET_HOST_CALLBACKS {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.appwidget.AppWidgetHost\$Callbacks", "this$0"
            ,
            reason =
            "android.appwidget.AppWidgetHost\$Callbacks is a stub and is held in memory native"
                + " code. The reference to the `mContext` was not being cleared, which caused the"
                + " Callbacks instance to retain this reference"
                + " Fixed in AOSP: https://github.com/android/platform_frameworks_base/commit"
                + "/7a96f3c917e0001ee739b65da37b2fadec7d7765"
        ) { _, sdkInt ->
          sdkInt < 22
        }
      }
    },

    AUDIO_MANAGER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.media.AudioManager$1", "this$0",
            reason =
            "Prior to Android M, VideoView required audio focus from AudioManager and"
                + " never abandoned it, which leaks the Activity context through the AudioManager."
                + " The root of the problem is that AudioManager uses whichever"
                + " context it receives, which in the case of the VideoView example is an Activity,"
                + " even though it only needs the application's context. The issue is fixed in"
                + " Android M, and the AudioManager now uses the application's context."
                + " Tracked here: https://code.google.com/p/android/issues/detail?id=152173"
                + " Fix: https://gist.github.com/jankovd/891d96f476f7a9ce24e2"
        ) { _, sdkInt ->
          sdkInt <= 22
        }
      }
    },

    EDITTEXT_BLINK_MESSAGEQUEUE {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.widget.Editor\$Blink", "this$0",
            reason =
            "The EditText Blink of the Cursor is implemented using a callback and Messages,"
                + " which trigger the display of the Cursor. If an AlertDialog or DialogFragment that"
                + " contains a blinking cursor is detached, a message is posted with a delay after the"
                + " dialog has been closed and as a result leaks the Activity."
                + " This can be fixed manually by calling TextView.setCursorVisible(false) in the"
                + " dismiss() method of the dialog."
                + " Tracked here: https://code.google.com/p/android/issues/detail?id=188551"
                + " Fixed in AOSP: https://android.googlesource.com/platform/frameworks/base/+"
                + "/5b734f2430e9f26c769d6af8ea5645e390fcf5af%5E%21/"
        ) { _, sdkInt ->
          sdkInt <= 22
        }
      }
    },

    CONNECTIVITY_MANAGER__SINSTANCE {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.net.ConnectivityManager", "sInstance",
            reason =
            "ConnectivityManager has a sInstance field that is set when the first"
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
                + "e0bef71662d81caaaa0d7214fb0bef5d39996a69"
        ) { _, sdkInt ->
          sdkInt <= 23
        }
      }
    },

    ACCESSIBILITY_NODE_INFO__MORIGINALTEXT {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.view.accessibility.AccessibilityNodeInfo", "mOriginalText"
            ,
            reason =
            "AccessibilityNodeInfo has a static sPool of AccessibilityNodeInfo. When"
                + " AccessibilityNodeInfo instances are released back in the pool,"
                + " AccessibilityNodeInfo.clear() does not clear the mOriginalText field, which"
                + " causes spans to leak which in turns causes TextView.ChangeWatcher to leak and the"
                + " whole view hierarchy. Introduced here: https://android.googlesource.com/platform/"
                + "frameworks/base/+/193520e3dff5248ddcf8435203bf99d2ba667219%5E%21/core/java/"
                + "android/view/accessibility/AccessibilityNodeInfo.java"
        ) { _, sdkInt ->
          sdkInt in 26..27
        }
      }
    },

    BACKDROP_FRAME_RENDERER__MDECORVIEW {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "com.android.internal.policy.BackdropFrameRenderer", "mDecorView"
            ,
            reason =
            "When BackdropFrameRenderer.releaseRenderer() is called, there's an unknown case"
                + " where mRenderer becomes null but mChoreographer doesn't and the thread doesn't"
                + " stop and ends up leaking mDecorView which itself holds on to a destroyed"
                + " activity"
        ) { _, sdkInt ->
          sdkInt in 24..26
        }
      }
    },

    VIEWLOCATIONHOLDER_ROOT {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.view.ViewGroup\$ViewLocationHolder",
            "mRoot"
            ,
            reason = "In Android P, ViewLocationHolder has an mRoot field that is not cleared " +
                "in its clear() method. Introduced in https://github.com/aosp-mirror" +
                "/platform_frameworks_base/commit/86b326012813f09d8f1de7d6d26c986a909d Bug " +
                "report: https://issuetracker.google.com/issues/112792715"
        ) { _, sdkInt ->
          sdkInt == 28
        }
      }
    },

    ACCESSIBILITY_NODE_ID_MANAGER {
      override fun add(references: MutableSet<AndroidKnownReference>) {
        references += instanceField(
            "android.view.accessibility.AccessibilityNodeIdManager", "mIdsToViews"
            ,
            reason = """
              Android Q Beta added AccessibilityNodeIdManager which stores all views from their
              onAttachedToWindow() call, until detached. Unfortunately it's possible to trigger
              the view framework to call detach before attach (by having a view removing itself
              from its parent in onAttach, which then causes AccessibilityNodeIdManager to keep
              children view forever. Future releases of Q will hold weak references.
            """.trimIndent()
        ) { _, sdkInt ->
          sdkInt == 28
        }
      }

    },

    TEXT_TO_SPEECH {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        val reason =
          ("TextToSpeech.shutdown() does not release its references to context objects." +
              " Furthermore, TextToSpeech instances cannot be garbage collected due to other process" +
              " keeping the references, resulting the context objects leaked." +
              " Developers might be able to mitigate the issue by passing application context" +
              " to TextToSpeech constructor." +
              " Tracked at: https://github.com/square/leakcanary/issues/1210 and" +
              " https://issuetracker.google.com/issues/129250419")
        references += instanceField(
            "android.speech.tts.TextToSpeech", "mContext",
            reason = reason
        ) { _, sdkInt ->
          sdkInt == 24
        }

        references += instanceField(
            "android.speech.tts.TtsEngines", "mContext",
            reason = reason
        ) { _, sdkInt ->
          sdkInt == 24
        }
      }
    },

    // ######## Manufacturer specific known leaks ########

    // SAMSUNG

    SPEN_GESTURE_MANAGER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += staticField(
            "com.samsung.android.smartclip.SpenGestureManager", "mContext"
            ,
            reason =
            "SpenGestureManager has a static mContext field that leaks a reference to the" + " activity. Yes, a STATIC mContext field."
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt == 19
        }
      }
    },

    CLIPBOARD_UI_MANAGER__SINSTANCE {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.sec.clipboard.ClipboardUIManager", "mContext"
            ,
            reason =
            "ClipboardUIManager is a static singleton that leaks an activity context."
                + " Fix: trigger a call to ClipboardUIManager.getInstance() in Application.onCreate()"
                + " , so that the ClipboardUIManager instance gets cached with a reference to the"
                + " application context. Example: https://gist.github.com/cypressious/"
                + "91c4fb1455470d803a602838dfcd5774"
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt in 19..21
        }
      }
    },

    SEM_CLIPBOARD_MANAGER__MCONTEXT {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "com.samsung.android.content.clipboard.SemClipboardManager",
            "mContext"
            ,
            reason =
            "SemClipboardManager is held in memory by an anonymous inner class" + " implementation of android.os.Binder, thereby leaking an activity context."
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt in 19..24
        }
        references += instanceField(
            "com.samsung.android.content.clipboard.SemClipboardManager$3",
            "this$0"
            ,
            reason =
            "SemClipboardManager is held in memory by an anonymous inner class" + " implementation of android.os.Binder, thereby leaking an activity context."
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt in 22..28
        }
      }
    },

    SEM_EMERGENCY_MANAGER__MCONTEXT {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "com.samsung.android.emergencymode.SemEmergencyManager", "mContext"
            ,
            reason =
            "SemEmergencyManager is a static singleton that leaks a DecorContext." + " Fix: https://gist.github.com/jankovd/a210460b814c04d500eb12025902d60d"
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt in 19..24
        }
      }
    },

    SEM_PERSONA_MANAGER {
      override fun add(references: MutableSet<AndroidKnownReference>) {
        references += instanceField(
            "com.samsung.android.knox.SemPersonaManager", "mContext"
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt == 24
        }
      }
    },

    SEM_APP_ICON_SOLUTION {
      override fun add(references: MutableSet<AndroidKnownReference>) {
        references += instanceField(
            "android.app.SemAppIconSolution", "mContext"
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt == 28
        }
      }
    },

    AW_RESOURCE__SRESOURCES {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        // AwResource#setResources() is called with resources that hold a reference to the
        // activity context (instead of the application context) and doesn't clear it.
        // Not sure what's going on there, input welcome.
        references += staticField(
            "com.android.org.chromium.android_webview.AwResource", "sResources"
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt == 19
        }
      }
    },

    TEXT_VIEW__MLAST_HOVERED_VIEW {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += staticField(
            "android.widget.TextView", "mLastHoveredView",
            reason =
            "mLastHoveredView is a static field in TextView that leaks the last hovered" + " view."
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt in 19..28
        }
      }
    },

    PERSONA_MANAGER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.os.PersonaManager", "mContext",
            reason =
            "android.app.LoadedApk.mResources has a reference to"
                + " android.content.res.Resources.mPersonaManager which has a reference to"
                + " android.os.PersonaManager.mContext which is an activity."
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt == 19
        }
      }
    },

    RESOURCES__MCONTEXT {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.content.res.Resources", "mContext",
            reason =
            "In AOSP the Resources class does not have a context."
                + " Here we have ZygoteInit.mResources (static field) holding on to a Resources"
                + " instance that has a context that is the activity."
                + " Observed here: https://github.com/square/leakcanary/issues/1#issue-74450184"
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt == 19
        }
      }
    },

    VIEW_CONFIGURATION__MCONTEXT {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.view.ViewConfiguration", "mContext",
            reason =
            "In AOSP the ViewConfiguration class does not have a context."
                + " Here we have ViewConfiguration.sConfigurations (static field) holding on to a"
                + " ViewConfiguration instance that has a context that is the activity."
                + " Observed here: https://github.com/square/leakcanary/issues"
                + "/1#issuecomment-100324683"
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt == 19
        }
      }
    },

    AUDIO_MANAGER__MCONTEXT_STATIC {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += staticField(
            "android.media.AudioManager", "mContext_static",
            reason =
            "Samsung added a static mContext_static field to AudioManager, holds a reference"
                + " to the activity."
                + " Observed here: https://github.com/square/leakcanary/issues/32"
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt == 19
        }
      }
    },

    ACTIVITY_MANAGER_MCONTEXT {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += staticField(
            "android.app.ActivityManager", "mContext",
            reason =
            "Samsung added a static mContext field to ActivityManager, holds a reference"
                + " to the activity."
                + " Observed here: https://github.com/square/leakcanary/issues/177 Fix in comment:"
                + " https://github.com/square/leakcanary/issues/177#issuecomment-222724283"
        ) { manufacturer, sdkInt ->
          manufacturer == SAMSUNG && sdkInt in 22..23
        }
      }
    },

    // OTHER MANUFACTURERS

    GESTURE_BOOST_MANAGER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += staticField(
            "android.gestureboost.GestureBoostManager", "mContext"
            ,
            reason =
            "GestureBoostManager is a static singleton that leaks an activity context." + " Fix: https://github.com/square/leakcanary/issues/696#issuecomment-296420756"
        ) { manufacturer, sdkInt ->
          manufacturer == HUAWEI && sdkInt in 24..25
        }
      }
    },

    BUBBLE_POPUP_HELPER__SHELPER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += staticField(
            "android.widget.BubblePopupHelper", "sHelper",
            reason =
            "A static helper for EditText bubble popups leaks a reference to the latest" + " focused view."
        ) { manufacturer, sdkInt ->
          manufacturer == LG && sdkInt in 19..22
        }
      }
    },

    LGCONTEXT__MCONTEXT {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "com.lge.systemservice.core.LGContext", "mContext",
            reason = "LGContext is a static singleton that leaks an activity context."
        ) { manufacturer, sdkInt ->
          manufacturer == LG && sdkInt == 21
        }
      }
    },

    MAPPER_CLIENT {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "com.nvidia.ControllerMapper.MapperClient\$ServiceClient", "this$0"
            ,
            reason =
            "Not sure exactly what ControllerMapper is about, but there is an anonymous"
                + " Handler in ControllerMapper.MapperClient.ServiceClient, which leaks"
                + " ControllerMapper.MapperClient which leaks the activity context."
        ) { manufacturer, sdkInt ->
          manufacturer == NVIDIA && sdkInt == 19
        }
      }
    },

    SYSTEM_SENSOR_MANAGER__MAPPCONTEXTIMPL {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += staticField(
            "android.hardware.SystemSensorManager", "mAppContextImpl"
            ,
            reason =
            "SystemSensorManager stores a reference to context"
                + " in a static field in its constructor."
                + " Fix: use application context to get SensorManager"
        ) { manufacturer, sdkInt ->
          (manufacturer == LENOVO && sdkInt == 19) || (manufacturer == VIVO && sdkInt == 22)
        }
      }
    },

    INSTRUMENTATION_RECOMMEND_ACTIVITY {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += staticField(
            "android.app.Instrumentation", "mRecommendActivity",
            reason =
            "Instrumentation would leak com.android.internal.app.RecommendActivity (in"
                + " framework.jar) in Meizu FlymeOS 4.5 and above, which is based on Android 5.0 and "
                + " above"
        ) { manufacturer, sdkInt ->
          manufacturer == MEIZU && sdkInt in 21..22
        }
      }
    },

    DEVICE_POLICY_MANAGER__SETTINGS_OBSERVER {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            "android.app.admin.DevicePolicyManager\$SettingsObserver", "this$0"
            ,
            reason =
            "DevicePolicyManager keeps a reference to the context it has been created with"
                + " instead of extracting the application context. In this Motorola build,"
                + " DevicePolicyManager has an inner SettingsObserver class that is a content"
                + " observer, which is held into memory by a binder transport object."
        ) { manufacturer, sdkInt ->
          manufacturer == MOTOROLA && sdkInt in 19..22
        }
      }
    },


    // ######## General known references (not leaks) ########

    REFERENCES {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += instanceField(
            WeakReference::class.java.name, "referent",
            status = WEAKLY_REACHABLE
        )
        references += instanceField(
            KeyedWeakReference::class.java.name, "referent"
            ,
            status = NEVER_REACHABLE
        )
        references += instanceField(
            SoftReference::class.java.name, "referent",
            status = NEVER_REACHABLE
        )
        references += instanceField(
            PhantomReference::class.java.name, "referent",
            status = NEVER_REACHABLE
        )
        references += instanceField(
            "java.lang.ref.Finalizer", "prev",
            status = NEVER_REACHABLE
        )
        references += instanceField(
            "java.lang.ref.Finalizer", "element",
            status = NEVER_REACHABLE
        )
        references += instanceField(
            "java.lang.ref.Finalizer", "next",
            status = NEVER_REACHABLE
        )
        references += instanceField(
            "java.lang.ref.FinalizerReference", "prev",
            status = NEVER_REACHABLE
        )
        references += instanceField(
            "java.lang.ref.FinalizerReference", "element",
            status = NEVER_REACHABLE
        )
        references += instanceField(
            "java.lang.ref.FinalizerReference", "next",
            status = NEVER_REACHABLE
        )
        references += instanceField(
            "sun.misc.Cleaner", "prev", status = NEVER_REACHABLE
        )
        references += instanceField(
            "sun.misc.Cleaner", "next", status = NEVER_REACHABLE
        )
      }
    },

    FINALIZER_WATCHDOG_DAEMON {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        // If the FinalizerWatchdogDaemon thread is on the shortest path, then there was no other
        // reference to the object and it was about to be GCed.
        references += javaLocal(
            "FinalizerWatchdogDaemon",
            status = NEVER_REACHABLE
        )
      }
    },

    MAIN {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        // The main thread stack is ever changing so local variables aren't likely to hold references
        // for long. If this is on the shortest path, it's probably that there's a longer path with
        // a real leak.
        references += javaLocal(
            "main",
            status = NEVER_REACHABLE
        )
      }
    },

    LEAK_CANARY_THREAD {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        references += javaLocal(
            LEAK_CANARY_THREAD_NAME,
            status = NEVER_REACHABLE
        )
      }
    },

    EVENT_RECEIVER__MMESSAGE_QUEUE {
      override fun add(
        references: MutableSet<AndroidKnownReference>
      ) {
        //  DisplayEventReceiver keeps a reference message queue object so that it is not GC'd while
        // the native peer of the receiver is using them.
        // The main thread message queue is held on by the main Looper, but that might be a longer
        // path. Let's not confuse people with a shorter path that is less meaningful.
        references += instanceField(
            "android.view.Choreographer\$FrameDisplayEventReceiver",
            "mMessageQueue"
            ,
            status = NEVER_REACHABLE
        )
      }
    },

    ;

    internal abstract fun add(references: MutableSet<AndroidKnownReference>)
  }


}

