package leakcanary

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.Factory
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

interface HasActivityTestRule<T : Activity> {
  val activityRule: ActivityTestRule<T>

  val activity
    get() = activityRule.activity!!
}

inline fun <reified T : Activity> activityTestRule(
  initialTouchMode: Boolean,
  launchActivity: Boolean
): ActivityTestRule<T> = ActivityTestRule(
    T::class.java, initialTouchMode, launchActivity
)

fun <R> triggersOnActivityCreated(block: () -> R): R {
  return waitForTriggered(block) { triggered ->
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks by noOpDelegate() {
      override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?
      ) {
        app.unregisterActivityLifecycleCallbacks(this)
        triggered()
      }
    })
  }
}

infix fun Any.retained(block: () -> Unit) {
  block()
  "" + this
}

fun <T : FragmentActivity, R> HasActivityTestRule<T>.triggersOnActivityDestroyed(block: () -> R): R {
  return waitForTriggered(block) { triggered ->
    val testActivity = activity
    testActivity.application.registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks by noOpDelegate() {
          override fun onActivityDestroyed(activity: Activity) {
            if (activity == testActivity) {
              activity.application.unregisterActivityLifecycleCallbacks(this)
              Looper.myQueue()
                  .addIdleHandler {
                    triggered()
                    false
                  }
            }
          }
        })
  }
}

fun <T : FragmentActivity, R> HasActivityTestRule<T>.triggersOnFragmentCreated(block: () -> R): R {
  return waitForTriggered(block) { triggered ->
    val fragmentManager = activity.supportFragmentManager
    fragmentManager.registerFragmentLifecycleCallbacks(
        object : FragmentManager.FragmentLifecycleCallbacks() {
          override fun onFragmentCreated(
            fm: FragmentManager,
            fragment: Fragment,
            savedInstanceState: Bundle?
          ) {
            fragmentManager.unregisterFragmentLifecycleCallbacks(this)
            triggered()
          }
        }, false
    )
  }
}

fun <T : FragmentActivity, R> HasActivityTestRule<T>.triggersOnFragmentViewDestroyed(block: () -> R): R {
  return waitForTriggered(block) { triggered ->
    val fragmentManager = activity.supportFragmentManager
    fragmentManager.registerFragmentLifecycleCallbacks(
        object : FragmentManager.FragmentLifecycleCallbacks() {
          override fun onFragmentViewDestroyed(
            fm: FragmentManager,
            fragment: Fragment
          ) {
            fragmentManager.unregisterFragmentLifecycleCallbacks(this)
            triggered()
          }
        }, false
    )
  }
}

fun <R> waitForTriggered(
  trigger: () -> R,
  triggerListener: (triggered: () -> Unit) -> Unit
): R {
  val latch = CountDownLatch(1)
  triggerListener {
    latch.countDown()
  }
  val result = trigger()
  latch.await()
  return result
}

inline fun <reified T : Any> noOpDelegate(): T {
  val javaClass = T::class.java
  val noOpHandler = InvocationHandler { _, _, _ ->
    // no op
  }
  return Proxy.newProxyInstance(
      javaClass.classLoader, arrayOf(javaClass), noOpHandler
  ) as T
}

fun <T> getOnMainSync(block: () -> T): T {
  val resultHolder = AtomicReference<T>()
  val latch = CountDownLatch(1)
  InstrumentationRegistry.getInstrumentation()
      .runOnMainSync {
        resultHolder.set(block())
        latch.countDown()
      }
  latch.await()
  return resultHolder.get()
}

fun runOnMainSync(block: () -> Unit) = InstrumentationRegistry.getInstrumentation()
    .runOnMainSync(block)

fun <T : ViewModel> ViewModelStoreOwner.installViewModel(modelClass: KClass<T>): T =
  ViewModelProvider(this, object : Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.newInstance()
  }).get(modelClass.java)

fun FragmentActivity.addFragmentNow(fragment: Fragment) {
  supportFragmentManager
      .beginTransaction()
      .add(0, fragment)
      .commitNow()
}

fun FragmentActivity.replaceWithBackStack(fragment: Fragment, @IdRes containerViewId: Int) {
  supportFragmentManager
      .beginTransaction()
      .addToBackStack(null)
      .replace(containerViewId, fragment)
      .commit()
}

fun FragmentActivity.removeFragmentNow(fragment: Fragment) {
  supportFragmentManager
      .beginTransaction()
      .remove(fragment)
      .commitNow()
}