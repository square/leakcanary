package leakcanary.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.MessageQueue.IdleHandler
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewTreeObserver.OnGlobalFocusChangeListener
import android.view.inputmethod.InputMethodManager
import shark.SharkLog
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal class ReferenceCleaner(
  private val inputMethodManager: InputMethodManager,
  private val mHField: Field,
  private val mServedViewField: Field,
  private val finishInputLockedMethod: Method
) : IdleHandler,
  OnAttachStateChangeListener,
  OnGlobalFocusChangeListener {
  override fun onGlobalFocusChanged(
    oldFocus: View?,
    newFocus: View?
  ) {
    if (newFocus == null) {
      return
    }
    oldFocus?.removeOnAttachStateChangeListener(this)
    Looper.myQueue()
      .removeIdleHandler(this)
    newFocus.addOnAttachStateChangeListener(this)
  }

  override fun onViewAttachedToWindow(v: View) {}
  override fun onViewDetachedFromWindow(v: View) {
    v.removeOnAttachStateChangeListener(this)
    Looper.myQueue()
      .removeIdleHandler(this)
    Looper.myQueue()
      .addIdleHandler(this)
  }

  override fun queueIdle(): Boolean {
    clearInputMethodManagerLeak()
    return false
  }

  private fun clearInputMethodManagerLeak() {
    try {
      val lock = mHField[inputMethodManager]
      if (lock == null) {
        SharkLog.d { "InputMethodManager.mH was null, could not fix leak." }
        return
      }
      // This is highly dependent on the InputMethodManager implementation.
      synchronized(lock) {
        val servedView =
          mServedViewField[inputMethodManager] as View?
        if (servedView != null) {
          val servedViewAttached =
            servedView.windowVisibility != View.GONE
          if (servedViewAttached) {
            // The view held by the IMM was replaced without a global focus change. Let's make
            // sure we get notified when that view detaches.
            // Avoid double registration.
            servedView.removeOnAttachStateChangeListener(this)
            servedView.addOnAttachStateChangeListener(this)
          } else { // servedView is not attached. InputMethodManager is being stupid!
            val activity = extractActivity(servedView.context)
            if (activity == null || activity.window == null) {
              // Unlikely case. Let's finish the input anyways.
              finishInputLockedMethod.invoke(inputMethodManager)
            } else {
              val decorView = activity.window
                .peekDecorView()
              val windowAttached =
                decorView.windowVisibility != View.GONE
              // If the window is attached, we do nothing. The IMM is leaking a detached view
              // hierarchy, but we haven't found a way to clear the reference without breaking
              // the IMM behavior.
              if (!windowAttached) {
                finishInputLockedMethod.invoke(inputMethodManager)
              }
            }
          }
        }
      }
    } catch (ignored: Throwable) {
      SharkLog.d(ignored) { "Could not fix leak" }
    }
  }

  private fun extractActivity(sourceContext: Context): Activity? {
    var context = sourceContext
    while (true) {
      context = when (context) {
        is Application -> {
          return null
        }
        is Activity -> {
          return context
        }
        is ContextWrapper -> {
          val baseContext =
            context.baseContext
          // Prevent Stack Overflow.
          if (baseContext === context) {
            return null
          }
          baseContext
        }
        else -> {
          return null
        }
      }
    }
  }
}