package leakcanary

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build.MANUFACTURER
import android.os.Build.VERSION.SDK_INT
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import shark.SharkLog
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

/**
 * A collection of hacks to fix leaks in the Android Framework and other Google Android libraries.
 */
@SuppressLint("NewApi")
enum class AndroidLeakFixes {

  /**
   * MediaSessionLegacyHelper is a static singleton and did not use the application context.
   * Introduced in android-5.0.1_r1, fixed in Android 5.1.0_r1.
   * https://github.com/android/platform_frameworks_base/commit/
   * 9b5257c9c99c4cb541d8e8e78fb04f008b1a9091
   *
   * We fix this leak by invoking MediaSessionLegacyHelper.getHelper() early in the app lifecycle.
   */
  MEDIA_SESSION_LEGACY_HELPER {
    override fun apply(application: Application) {
      if (SDK_INT != 21) {
        return
      }
      backgroundExecutor.execute {
        try {
          val clazz = Class.forName("android.media.session.MediaSessionLegacyHelper")
          val getHelperMethod = clazz.getDeclaredMethod("getHelper", Context::class.java)
          getHelperMethod.invoke(null, application)
        } catch (ignored: Exception) {
          SharkLog.d(ignored) { "Could not fix the $name leak" }
        }
      }
    }
  },

  /**
   * This flushes the TextLine pool when an activity is destroyed, to prevent memory leaks.
   *
   * The first memory leak has been fixed in android-5.1.0_r1
   * https://github.com/android/platform_frameworks_base/commit/
   * 893d6fe48d37f71e683f722457bea646994a10bf
   *
   * Second memory leak: https://github.com/android/platform_frameworks_base/commit/
   * b3a9bc038d3a218b1dbdf7b5668e3d6c12be5ee4
   */
  TEXT_LINE_POOL {
    override fun apply(application: Application) {
      // Can't use reflection starting in SDK 28
      if (SDK_INT >= 28) {
        return
      }
      backgroundExecutor.execute {
        // Pool of TextLine instances.
        val sCached: Any?
        try {
          val textLineClass = Class.forName("android.text.TextLine")
          val sCachedField = textLineClass.getDeclaredField("sCached")
          sCachedField.isAccessible = true
          sCached = sCachedField.get(null)
          // Can't happen in current Android source, but hidden APIs can change.
          if (sCached == null || !sCached.javaClass.isArray) {
            SharkLog.d { "Could not fix the $name leak, sCached=$sCached" }
            return@execute
          }
        } catch (ignored: Exception) {
          SharkLog.d(ignored) { "Could not fix the $name leak" }
          return@execute
        }

        application.onActivityDestroyed {
          // TextLine locks on sCached. We take that lock and clear the whole array at once.
          synchronized(sCached) {
            val length = Array.getLength(sCached)
            for (i in 0 until length) {
              Array.set(sCached, i, null)
            }
          }
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
   * Fixed in https://android.googlesource.com/platform/frameworks/base/+/
   * 5200e1cb07190a1f6874d72a4561064cad3ee3e0%5E%21/#F0 (Android O)
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
      val flushedThreadIds = mutableSetOf<Int>()
      // Wait 2 seconds then look for handler threads every 3 seconds.
      backgroundExecutor.scheduleWithFixedDelay({
        val newHandlerThreadsById = findAllHandlerThreads()
            .mapNotNull { thread ->
              val threadId = thread.threadId
              if (threadId == -1 || threadId in flushedThreadIds) {
                null
              } else {
                threadId to thread
              }
            }
        flushedThreadIds += newHandlerThreadsById.map { it.first }
        newHandlerThreadsById
            .map { it.second }
            .forEach { handlerThread ->
              SharkLog.d { "Setting up flushing for $handlerThread" }
              var scheduleFlush = true
              val flushHandler = Handler(handlerThread.looper)
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
      }, 2, 3, TimeUnit.SECONDS)
    }
  },

  /**
   * Until API 28, AccessibilityNodeInfo has a mOriginalText field that was not properly cleared
   * when instance were put back in the pool.
   * Leak introduced here: https://android.googlesource.com/platform/frameworks/base/+
   * /193520e3dff5248ddcf8435203bf99d2ba667219%5E%21/core/java/android/view/accessibility
   * /AccessibilityNodeInfo.java
   *
   * Fixed here: https://android.googlesource.com/platform/frameworks/base/+
   * /6f8ec1fd8c159b09d617ed6d9132658051443c0c
   */
  ACCESSIBILITY_NODE_INFO {
    override fun apply(application: Application) {
      if (SDK_INT >= 28) {
        return
      }
      // Starve the pool every 5 seconds.
      backgroundExecutor.scheduleAtFixedRate({
        val maxPoolSize = 50
        for (i in 0 until maxPoolSize) {
          AccessibilityNodeInfo.obtain()
        }
      }, 5, 5, SECONDS)
    }
  },

  /**
   * ConnectivityManager has a sInstance field that is set when the first ConnectivityManager instance is created.
   * ConnectivityManager has a mContext field.
   * When calling activity.getSystemService(Context.CONNECTIVITY_SERVICE) , the first ConnectivityManager instance
   * is created with the activity context and stored in sInstance.
   * That activity context then leaks forever.
   *
   * This fix makes sure the connectivity manager is created with the application context.
   *
   * Tracked here: https://code.google.com/p/android/issues/detail?id=198852
   * Introduced here: https://github.com/android/platform_frameworks_base/commit/e0bef71662d81caaaa0d7214fb0bef5d39996a69
   */
  CONNECTIVITY_MANAGER {
    override fun apply(application: Application) {
      if (SDK_INT > 23) {
        return
      }

      try {
        application.getSystemService(Context.CONNECTIVITY_SERVICE)
      } catch (ignored: Exception) {
        SharkLog.d(ignored) { "Could not fix the $name leak" }
      }
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

      backgroundExecutor.execute {
        val helperField: Field
        try {
          val helperClass = Class.forName("android.widget.BubblePopupHelper")
          helperField = helperClass.getDeclaredField("sHelper")
          helperField.isAccessible = true
        } catch (ignored: Exception) {
          SharkLog.d(ignored) { "Could not fix the $name leak" }
          return@execute
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

      backgroundExecutor.execute {
        val field: Field
        try {
          field = TextView::class.java.getDeclaredField("mLastHoveredView")
          field.isAccessible = true
        } catch (ignored: Exception) {
          SharkLog.d(ignored) { "Could not fix the $name leak" }
          return@execute
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
   * Samsung added a static mContext field to ActivityManager, holding a reference to the activity.
   *
   * This fix clears the field when an activity is destroyed if it refers to this specific activity.
   *
   * Observed here: https://github.com/square/leakcanary/issues/177
   */
  ACTIVITY_MANAGER {
    override fun apply(application: Application) {
      if (MANUFACTURER != SAMSUNG || SDK_INT != 22) {
        return
      }

      backgroundExecutor.execute {
        val contextField: Field
        try {
          contextField = application
              .getSystemService(Context.ACTIVITY_SERVICE)
              .javaClass
              .getDeclaredField("mContext")
          contextField.isAccessible = true
          if ((contextField.modifiers or Modifier.STATIC) != contextField.modifiers) {
            SharkLog.d { "Could not fix the $name leak, contextField=$contextField" }
            return@execute
          }
        } catch (ignored: Exception) {
          SharkLog.d(ignored) { "Could not fix the $name leak" }
          return@execute
        }

        application.onActivityDestroyed { activity ->
          try {
            if (contextField.get(null) == activity) {
              contextField.set(null, null)
            }
          } catch (ignored: Exception) {
            SharkLog.d(ignored) { "Could not fix the $name leak" }
          }
        }
      }
    }
  },

  /**
   * In Android P, ViewLocationHolder has an mRoot field that is not cleared in its clear() method.
   * Introduced in https://github.com/aosp-mirror/platform_frameworks_base/commit
   * /86b326012813f09d8f1de7d6d26c986a909d
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

    private val backgroundExecutor =
      // Single thread => avoid dealing with concurrency (aside from background vs main thread)
      Executors.newSingleThreadScheduledExecutor { runnable ->
        val thread = object : Thread() {
          override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
          }
        }
        thread.name = "plumber-android-leaks"
        thread
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

    internal inline fun <reified T : Any> noOpDelegate(): T {
      val javaClass = T::class.java
      val noOpHandler = InvocationHandler { _, _, _ ->
        // no op
      }
      return Proxy.newProxyInstance(
          javaClass.classLoader, arrayOf(javaClass), noOpHandler
      ) as T
    }

    internal fun checkMainThread() {
      if (Looper.getMainLooper().thread !== Thread.currentThread()) {
        throw UnsupportedOperationException(
            "Should be called from the main thread, not ${Thread.currentThread()}"
        )
      }
    }
  }
}
