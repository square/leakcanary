package leakcanary

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.ContextWrapper
import android.os.Build.MANUFACTURER
import android.os.Build.VERSION.SDK_INT
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.UserManager
import android.view.View
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import curtains.Curtains
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
  }

  ;

  protected abstract fun apply(application: Application)

  private var applied = false

  companion object {

    private const val SAMSUNG = "samsung"
    private const val LG = "LGE"

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
