package leakcanary

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.ContextWrapper
import android.os.Build.MANUFACTURER
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.UserManager
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import com.squareup.leakcanary.plumber.core.R
import curtains.Curtains
import curtains.OnRootViewAddedListener
import curtains.OnRootViewRemovedListener
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.util.EnumSet
import leakcanary.internal.friendly.checkMainThread
import leakcanary.internal.friendly.noOpDelegate
import shark.SharkLog

/**
 * A collection of hacks to fix leaks in the Android Framework and other Google Android libraries.
 */
@SuppressLint("NewApi")
enum class AndroidLeakFixes {

  /**
   * This flushes the TextLine pool when an activity is destroyed, to prevent memory leaks.
   *
   * The first memory leak has been fixed in android-5.1.0_r1
   * https://github.com/android/platform_frameworks_base/commit/893d6fe48d37f71e683f722457bea646994a10bf
   *
   * Second memory leak: https://github.com/android/platform_frameworks_base/commit/b3a9bc038d3a218b1dbdf7b5668e3d6c12be5ee4
   */
  TEXT_LINE_POOL {
    override fun apply(application: Application) {
      // Can't use reflection starting in SDK 28
      if (SDK_INT >= 28) {
        return
      }
      backgroundHandler.post {
        try {
          val textLineClass = Class.forName("android.text.TextLine")
          val sCachedField = textLineClass.getDeclaredField("sCached")
          sCachedField.isAccessible = true
          // One time retrieval to make sure this will work.
          val sCached = sCachedField.get(null)
          // Can't happen in current Android source, but hidden APIs can change.
          if (sCached == null || !sCached.javaClass.isArray) {
            SharkLog.d { "Could not fix the $name leak, sCached=$sCached" }
            return@post
          }
          application.onActivityDestroyed {
            // Pool of TextLine instances.
            val sCached = sCachedField.get(null)
            // TextLine locks on sCached. We take that lock and clear the whole array at once.
            synchronized(sCached) {
              val length = Array.getLength(sCached)
              for (i in 0 until length) {
                Array.set(sCached, i, null)
              }
            }
          }
        } catch (ignored: Exception) {
          SharkLog.d(ignored) { "Could not fix the $name leak" }
          return@post
        }
      }
    }
  },

  /**
   * Obtaining the UserManager service ends up calling the hidden UserManager.get() method which
   * stores the context in a singleton UserManager instance and then stores that instance in a
   * static field.
   *
   * We obtain the user manager from an activity context, so if it hasn't been created yet it will
   * leak that activity forever.
   *
   * This fix makes sure the UserManager is created and holds on to the Application context.
   *
   * Issue: https://code.google.com/p/android/issues/detail?id=173789
   *
   * Fixed in https://android.googlesource.com/platform/frameworks/base/+/5200e1cb07190a1f6874d72a4561064cad3ee3e0%5E%21/#F0
   * (Android O)
   */
  USER_MANAGER {
    @SuppressLint("NewApi")
    override fun apply(application: Application) {
      if (SDK_INT !in 17..25) {
        return
      }
      try {
        val getMethod = UserManager::class.java.getDeclaredMethod("get", Context::class.java)
        getMethod.invoke(null, application)
      } catch (ignored: Exception) {
        SharkLog.d(ignored) { "Could not fix the $name leak" }
      }
    }
  },

  /**
   * HandlerThread instances keep local reference to their last handled message after recycling it.
   * That message is obtained by a dialog which sets on an OnClickListener on it and then never
   * recycles it, expecting it to be garbage collected but it ends up being held by the
   * HandlerThread.
   */
  FLUSH_HANDLER_THREADS {
    override fun apply(application: Application) {
      if (SDK_INT >= 31) {
        return
      }
      val flushedThreadIds = mutableSetOf<Int>()
      // Don't flush the backgroundHandler's thread, we're rescheduling all the time anyway.
      flushedThreadIds += (backgroundHandler.looper.thread as HandlerThread).threadId
      // Wait 2 seconds then look for handler threads every 3 seconds.
      val flushNewHandlerThread = object : Runnable {
        override fun run() {
          val newHandlerThreadsById = findAllHandlerThreads()
            .mapNotNull { thread ->
              val threadId = thread.threadId
              if (threadId == -1 || threadId in flushedThreadIds) {
                null
              } else {
                threadId to thread
              }
            }
          newHandlerThreadsById
            .forEach { (threadId, handlerThread) ->
              val looper = handlerThread.looper
              if (looper == null) {
                SharkLog.d { "Handler thread found without a looper: $handlerThread" }
                return@forEach
              }
              flushedThreadIds += threadId
              SharkLog.d { "Setting up flushing for $handlerThread" }
              var scheduleFlush = true
              val flushHandler = Handler(looper)
              flushHandler.onEachIdle {
                if (handlerThread.isAlive && scheduleFlush) {
                  scheduleFlush = false
                  // When the Handler thread becomes idle, we post a message to force it to move.
                  // Source: https://developer.squareup.com/blog/a-small-leak-will-sink-a-great-ship/
                  try {
                    val posted = flushHandler.postDelayed({
                      // Right after this postDelayed executes, the idle handler will likely be called
                      // again (if the queue is otherwise empty), so we'll need to schedule a flush
                      // again.
                      scheduleFlush = true
                    }, 1000)
                    if (!posted) {
                      SharkLog.d { "Failed to post to ${handlerThread.name}" }
                    }
                  } catch (ignored: RuntimeException) {
                    // If the thread is quitting, posting to it may throw. There is no safe and atomic way
                    // to check if a thread is quitting first then post it it.
                    SharkLog.d(ignored) { "Failed to post to ${handlerThread.name}" }
                  }
                }
              }
            }
          backgroundHandler.postDelayed(this, 3000)
        }
      }
      backgroundHandler.postDelayed(flushNewHandlerThread, 2000)
    }
  },

  /**
   * Until API 28, AccessibilityNodeInfo has a mOriginalText field that was not properly cleared
   * when instance were put back in the pool.
   * Leak introduced here: https://android.googlesource.com/platform/frameworks/base/+/193520e3dff5248ddcf8435203bf99d2ba667219%5E%21/core/java/android/view/accessibility/AccessibilityNodeInfo.java
   *
   * Fixed here: https://android.googlesource.com/platform/frameworks/base/+/6f8ec1fd8c159b09d617ed6d9132658051443c0c
   */
  ACCESSIBILITY_NODE_INFO {
    override fun apply(application: Application) {
      if (SDK_INT >= 28) {
        return
      }

      val starvePool = object : Runnable {
        override fun run() {
          val maxPoolSize = 50
          for (i in 0 until maxPoolSize) {
            AccessibilityNodeInfo.obtain()
          }
          backgroundHandler.postDelayed(this, 5000)
        }
      }
      backgroundHandler.postDelayed(starvePool, 5000)
    }
  },

  /**
   * ClipboardUIManager is a static singleton that leaks an activity context.
   * This fix makes sure the manager is called with an application context.
   */
  SAMSUNG_CLIPBOARD_MANAGER {
    override fun apply(application: Application) {
      if (MANUFACTURER != SAMSUNG || SDK_INT !in 19..21) {
        return
      }

      try {
        val managerClass = Class.forName("android.sec.clipboard.ClipboardUIManager")
        val instanceMethod = managerClass.getDeclaredMethod("getInstance", Context::class.java)
        instanceMethod.isAccessible = true
        instanceMethod.invoke(null, application)
      } catch (ignored: Exception) {
        SharkLog.d(ignored) { "Could not fix the $name leak" }
      }
    }
  },

  /**
   * A static helper for EditText bubble popups leaks a reference to the latest focused view.
   *
   * This fix clears it when the activity is destroyed.
   */
  BUBBLE_POPUP {
    override fun apply(application: Application) {
      if (MANUFACTURER != LG || SDK_INT !in 19..21) {
        return
      }

      backgroundHandler.post {
        val helperField: Field
        try {
          val helperClass = Class.forName("android.widget.BubblePopupHelper")
          helperField = helperClass.getDeclaredField("sHelper")
          helperField.isAccessible = true
        } catch (ignored: Exception) {
          SharkLog.d(ignored) { "Could not fix the $name leak" }
          return@post
        }

        application.onActivityDestroyed {
          try {
            helperField.set(null, null)
          } catch (ignored: Exception) {
            SharkLog.d(ignored) { "Could not fix the $name leak" }
          }
        }
      }
    }
  },

  /**
   * mLastHoveredView is a static field in TextView that leaks the last hovered view.
   *
   * This fix clears it when the activity is destroyed.
   */
  LAST_HOVERED_VIEW {
    override fun apply(application: Application) {
      if (MANUFACTURER != SAMSUNG || SDK_INT !in 19..21) {
        return
      }

      backgroundHandler.post {
        val field: Field
        try {
          field = TextView::class.java.getDeclaredField("mLastHoveredView")
          field.isAccessible = true
        } catch (ignored: Exception) {
          SharkLog.d(ignored) { "Could not fix the $name leak" }
          return@post
        }

        application.onActivityDestroyed {
          try {
            field.set(null, null)
          } catch (ignored: Exception) {
            SharkLog.d(ignored) { "Could not fix the $name leak" }
          }
        }
      }
    }
  },

  /**
   * In Android P, ViewLocationHolder has an mRoot field that is not cleared in its clear() method.
   * Introduced in https://github.com/aosp-mirror/platform_frameworks_base/commit/86b326012813f09d8f1de7d6d26c986a909d
   *
   * This leaks triggers very often when accessibility is on. To fix this leak we need to clear
   * the ViewGroup.ViewLocationHolder.sPool pool. Unfortunately Android P prevents accessing that
   * field through reflection. So instead, we call [ViewGroup#addChildrenForAccessibility] with
   * a view group that has 32 children (32 being the pool size), which as result fills in the pool
   * with 32 dumb views that reference a dummy context instead of an activity context.
   *
   * This fix empties the pool on every activity destroy and every AndroidX fragment view destroy.
   * You can support other cases where views get detached by calling directly
   * [ViewLocationHolderLeakFix.clearStaticPool].
   */
  VIEW_LOCATION_HOLDER {
    override fun apply(application: Application) {
      ViewLocationHolderLeakFix.applyFix(application)
    }
  },

  /**
   * When an activity is destroyed, the corresponding ViewRootImpl instance is released and ready to
   * be garbage collected.
   * Some time after that, ViewRootImpl#W receives a windowfocusChanged() callback, which it
   * normally delegates to ViewRootImpl which in turn calls
   * InputMethodManager#onPreWindowFocus which clears InputMethodManager#mCurRootView.
   *
   * Unfortunately, since the ViewRootImpl instance is garbage collectable it may be garbage
   * collected before that happens.
   * ViewRootImpl#W has a weak reference on ViewRootImpl, so that weak reference will then return
   * null and the windowfocusChanged() callback will be ignored, leading to
   * InputMethodManager#mCurRootView not being cleared.
   *
   * Filed here: https://issuetracker.google.com/u/0/issues/116078227
   * Fixed here: https://android.googlesource.com/platform/frameworks/base/+/dff365ef4dc61239fac70953b631e92972a9f41f%5E%21/#F0
   * InputMethodManager.mCurRootView is part of the unrestricted grey list on Android 9:
   * https://android.googlesource.com/platform/frameworks/base/+/pie-release/config/hiddenapi-light-greylist.txt#6057
   */
  IMM_CUR_ROOT_VIEW {
    override fun apply(application: Application) {
      if (SDK_INT >= 29) {
        return
      }
      val inputMethodManager = try {
        application.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
      } catch (ignored: Throwable) {
        // https://github.com/square/leakcanary/issues/2140
        SharkLog.d(ignored) { "Could not retrieve InputMethodManager service" }
        return
      }
      val mCurRootViewField = try {
        InputMethodManager::class.java.getDeclaredField("mCurRootView").apply {
          isAccessible = true
        }
      } catch (ignored: Throwable) {
        SharkLog.d(ignored) { "Could not read InputMethodManager.mCurRootView field" }
        return
      }
      // Clear InputMethodManager.mCurRootView on activity destroy
      application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks
      by noOpDelegate() {
        override fun onActivityDestroyed(activity: Activity) {
          try {
            val rootView = mCurRootViewField[inputMethodManager] as View?
            val isDestroyedActivity = rootView != null &&
              activity.window != null &&
              activity.window.decorView === rootView
            val rootViewActivityContext = rootView?.context?.activityOrNull
            val isChildWindowOfDestroyedActivity = rootViewActivityContext === activity
            if (isDestroyedActivity || isChildWindowOfDestroyedActivity) {
              mCurRootViewField[inputMethodManager] = null
            }
          } catch (ignored: Throwable) {
            SharkLog.d(ignored) { "Could not update InputMethodManager.mCurRootView field" }
          }
        }
      })
      // Clear InputMethodManager.mCurRootView on window removal (e.g. dialog dismiss)
      Curtains.onRootViewsChangedListeners += OnRootViewRemovedListener { removedRootView ->
        val immRootView = mCurRootViewField[inputMethodManager] as View?
        if (immRootView === removedRootView) {
          mCurRootViewField[inputMethodManager] = null
        }
      }
    }

    private val Context.activityOrNull: Activity?
      get() {
        var context = this
        while (true) {
          if (context is Application) {
            return null
          }
          if (context is Activity) {
            return context
          }
          if (context is ContextWrapper) {
            val baseContext = context.baseContext
            // Prevent Stack Overflow.
            if (baseContext === this) {
              return null
            }
            context = baseContext
          } else {
            return null
          }
        }
      }
  },

  /**
   * InputMethodManager#mImeInsetsConsumer is the IME insets consumer of the window that last
   * gained focus, set when ViewRootImpl#handleWindowFocusChanged() calls
   * InsetsController#onWindowFocusGained():
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/ImeInsetsSourceConsumer.java#85
   *
   * handleWindowFocusChanged() makes that call before checking whether the window is still added,
   * and removing a window doesn't remove the focus change messages already queued for it:
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/android/view/ViewRootImpl.java#3215
   * So when a window is removed while its focus gain is still queued, that focus gain runs after
   * the window is gone and registers the consumer of the removed window. If the window that got
   * focus back had its own focus gain run first, InputMethodManager keeps holding the removed
   * window's consumer, and through it the removed ViewRootImpl, until another window gains focus.
   * This happens when a dialog is shown then dismissed while the main thread is busy.
   *
   * Android 16 doesn't register IME insets consumers anymore: the registration is behind the
   * refactor_insets_controller flag, which the Android 16 release configuration enables.
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/view/ImeInsetsSourceConsumer.java#105
   * https://android.googlesource.com/platform/build/release/+/refs/tags/android-16.0.0_r1/aconfig/bp1a/android.view.inputmethod/refactor_insets_controller_flag_values.textproto
   * Android 15 has the same check, but its release configuration leaves the flag disabled.
   *
   * This fix removes the focus change messages still queued for a window when its root view is
   * detached, which only happens as the window is removed.
   */
  IMM_IME_INSETS_CONSUMER {
    override fun apply(application: Application) {
      if (SDK_INT !in 30..35) {
        return
      }
      Curtains.onRootViewsChangedListeners += OnRootViewAddedListener { rootView ->
        rootView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
          override fun onViewAttachedToWindow(view: View) = Unit

          override fun onViewDetachedFromWindow(view: View) {
            // Still attached at this point, so this is the handler of the window being removed.
            view.handler.removeMessages(VIEW_ROOT_IMPL_MSG_WINDOW_FOCUS_CHANGED)
            view.removeOnAttachStateChangeListener(this)
          }
        })
      }
    }
  },

  /**
   * PermissionControllerManager stores the first context it's initialized with forever.
   * Sometimes it's an Activity context which then leaks after Activity is destroyed.
   *
   * This fix makes sure the PermissionControllerManager is created with the application context.
   *
   * For Pixel devices the issue can be tracked here
   * https://issuetracker.google.com/issues/318415056
   */
  PERMISSION_CONTROLLER_MANAGER {
    @SuppressLint("WrongConstant")
    override fun apply(application: Application) {
      if (SDK_INT < 29) {
        return
      }
      try {
        application.getSystemService("permission_controller")
      } catch (ignored: Exception) {
        SharkLog.d(ignored) { "Unable to fix PermissionControllerManager leak" }
      }
    }
  },

  /**
   * When Android relaunches an activity and preserves its window, the new activity reuses the
   * DecorView of the destroyed one:
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/java/com/android/internal/policy/PhoneWindow.java#335
   * On Android 10, the context of that DecorView is a DecorContext with a weak reference to the
   * activity it was created for, and DecorContext#setPhoneWindow() doesn't update it, so it keeps
   * returning the destroyed activity:
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/java/com/android/internal/policy/DecorContext.java#56
   * DecorView#getResources() reads that weak reference on every call. A weak reference read while
   * the garbage collector is marking keeps its referent alive through that garbage collection, so
   * the destroyed activity can outlive many of them while the window keeps drawing, and LeakCanary
   * reports it as retained even though the heap dump has no strong path to it.
   *
   * Android 10 preserves windows when it relaunches every activity of an app because an overlay
   * the app uses was enabled or disabled, e.g. a change of navigation mode, display cutout or
   * accent color:
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/java/android/app/ActivityThread.java#5701
   * and when it relaunches an activity whose window was only resized, e.g. in split screen:
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/services/core/java/com/android/server/wm/ActivityRecord.java#3296
   * Android 11 fixed it by replacing the weak reference in setPhoneWindow():
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/com/android/internal/policy/DecorContext.java#56
   *
   * The fields of DecorContext can't be accessed through reflection on Android 10, but
   * PhoneWindow creates the DecorContext with whatever Window#getContext() returns:
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/java/com/android/internal/policy/PhoneWindow.java#2325
   * and Window.mContext, the field it returns, can. So this fix sets that field to a wrapper of
   * the activity while the activity is created, then sets it back. That's when the DecorView is
   * created, even for an activity that doesn't set its content view in onCreate(), because
   * Activity#performCreate() calls ActivityTransitionState#setEnterActivityOptions() right before
   * it dispatches onActivityPostCreated():
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/java/android/app/Activity.java#7810
   * and that creates it:
   * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/core/java/android/app/ActivityTransitionState.java#171
   * The DecorContext then references the wrapper rather than the activity, and when a new activity
   * is created with a preserved window, this fix replaces the base context of that wrapper with the
   * new activity.
   */
  DECOR_CONTEXT {
    override fun apply(application: Application) {
      if (SDK_INT != 29) {
        return
      }
      val windowContextField: Field
      val baseContextField: Field
      try {
        windowContextField = Window::class.java.getDeclaredField("mContext").apply {
          isAccessible = true
        }
        baseContextField = ContextWrapper::class.java.getDeclaredField("mBase").apply {
          isAccessible = true
        }
      } catch (ignored: NoSuchFieldException) {
        SharkLog.d(ignored) { "Could not fix the $name leak" }
        return
      }
      application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks
      by noOpDelegate() {
        override fun onActivityPreCreated(
          activity: Activity,
          savedInstanceState: Bundle?
        ) {
          val window = activity.window ?: return
          try {
            // A window that has a DecorView before its activity is created is a preserved window.
            val preservedDecorView = window.peekDecorView()
            if (preservedDecorView == null) {
              windowContextField[window] = DecorActivityContext(activity)
            } else {
              val decorActivityContext = preservedDecorView.getTag(
                R.id.leak_canary_plumber_decor_activity_context
              ) as DecorActivityContext? ?: return
              baseContextField[decorActivityContext] = activity
            }
          } catch (ignored: IllegalAccessException) {
            SharkLog.d(ignored) { "Could not fix the $name leak" }
          }
        }

        override fun onActivityPostCreated(
          activity: Activity,
          savedInstanceState: Bundle?
        ) {
          val window = activity.window ?: return
          try {
            val decorActivityContext = windowContextField[window] as? DecorActivityContext ?: return
            windowContextField[window] = activity
            // The DecorContext only has a weak reference to the wrapper.
            window.peekDecorView()?.setTag(
              R.id.leak_canary_plumber_decor_activity_context, decorActivityContext
            )
          } catch (ignored: IllegalAccessException) {
            SharkLog.d(ignored) { "Could not fix the $name leak" }
          }
        }
      })
    }
  }

  ;

  protected abstract fun apply(application: Application)

  private var applied = false

  companion object {

    private const val SAMSUNG = "samsung"
    private const val LG = "LGE"

    // ViewRootImpl.MSG_WINDOW_FOCUS_CHANGED, 6 from Android 11 to Android 16.
    private const val VIEW_ROOT_IMPL_MSG_WINDOW_FOCUS_CHANGED = 6

    fun applyFixes(
      application: Application,
      fixes: Set<AndroidLeakFixes> = EnumSet.allOf(AndroidLeakFixes::class.java)
    ) {
      checkMainThread()
      fixes.forEach { fix ->
        if (!fix.applied) {
          fix.apply(application)
          fix.applied = true
        } else {
          SharkLog.d { "${fix.name} leak fix already applied." }
        }
      }
    }

    internal val backgroundHandler by lazy {
      val handlerThread = HandlerThread("plumber-android-leaks")
      handlerThread.start()
      Handler(handlerThread.looper)
    }

    private fun Handler.onEachIdle(onIdle: () -> Unit) {
      try {
        // Unfortunately Looper.getQueue() is API 23. Looper.myQueue() is API 1.
        // So we have to post to the handler thread to be able to obtain the queue for that
        // thread from within that thread.
        post {
          Looper
            .myQueue()
            .addIdleHandler {
              onIdle()
              true
            }
        }
      } catch (ignored: RuntimeException) {
        // If the thread is quitting, posting to it will throw. There is no safe and atomic way
        // to check if a thread is quitting first then post it it.
      }
    }

    private fun findAllHandlerThreads(): List<HandlerThread> {
      // Based on https://stackoverflow.com/a/1323480
      var rootGroup = Thread.currentThread().threadGroup!!
      while (rootGroup.parent != null) rootGroup = rootGroup.parent
      var threads = arrayOfNulls<Thread>(rootGroup.activeCount())
      while (rootGroup.enumerate(threads, true) == threads.size) {
        threads = arrayOfNulls(threads.size * 2)
      }
      return threads.mapNotNull { if (it is HandlerThread) it else null }
    }

    internal fun Application.onActivityDestroyed(block: (Activity) -> Unit) {
      registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks
      by noOpDelegate() {
        override fun onActivityDestroyed(activity: Activity) {
          block(activity)
        }
      })
    }

    private fun Window.onDecorViewReady(callback: () -> Unit) {
      if (peekDecorView() == null) {
        onContentChanged {
          callback()
          return@onContentChanged false
        }
      } else {
        callback()
      }
    }

    private fun Window.onContentChanged(block: () -> Boolean) {
      val callback = wrapCallback()
      callback.onContentChangedCallbacks += block
    }

    private fun Window.wrapCallback(): WindowDelegateCallback {
      val currentCallback = callback
      return if (currentCallback is WindowDelegateCallback) {
        currentCallback
      } else {
        val newCallback = WindowDelegateCallback(currentCallback)
        callback = newCallback
        newCallback
      }
    }

    /**
     * What the DecorContext of an activity's DecorView takes for that activity, see
     * [DECOR_CONTEXT].
     */
    private class DecorActivityContext(activity: Activity) : ContextWrapper(activity)

    private class WindowDelegateCallback constructor(
      private val delegate: Window.Callback
    ) : FixedWindowCallback(delegate) {

      val onContentChangedCallbacks = mutableListOf<() -> Boolean>()

      override fun onContentChanged() {
        onContentChangedCallbacks.removeAll { callback ->
          !callback()
        }
        delegate.onContentChanged()
      }
    }
  }
}
