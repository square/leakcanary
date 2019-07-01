package leakcanary

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.MessageQueue
import android.view.View
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.rule.ActivityTestRule
import leakcanary.TestUtils.assertLeak
import leakcanary.internal.InternalHelper.noOpDelegate
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch

class FragmentLeakTest {

  @get:Rule
  var activityRule = ActivityTestRule(
      TestActivity::class.java, !TOUCH_MODE, !LAUNCH_ACTIVITY
  )

  @Before fun setUp() {
    LeakSentry.refWatcher
        .clearWatchedInstances()
  }

  @After fun tearDown() {
    LeakSentry.refWatcher
        .clearWatchedInstances()
  }

  @Test
  fun fragmentShouldLeak() {
    startActivityAndWaitForCreate()

    LeakingFragment.add(activityRule.activity)

    val waitForFragmentDetach = activityRule.activity.waitForFragmentDetached()
    val waitForActivityDestroy = waitForActivityDestroy()
    activityRule.finishActivity()
    waitForFragmentDetach.await()
    waitForActivityDestroy.await()

    assertLeak(LeakingFragment::class.java)
  }

  @Test
  fun fragmentViewShouldLeak() {
    startActivityAndWaitForCreate()
    val activity = activityRule.activity

    val waitForFragmentViewDestroyed = activity.waitForFragmentViewDestroyed()
    // First, add a new fragment
    ViewLeakingFragment.addToBackstack(activity)
    // Then, add a new fragment again, which destroys the view of the previous fragment and puts
    // that fragment in the backstack.
    ViewLeakingFragment.addToBackstack(activity)
    waitForFragmentViewDestroyed.await()

    assertLeak(View::class.java)
  }

  private fun startActivityAndWaitForCreate() {
    val waitForActivityOnCreate = CountDownLatch(1)
    val app = getApplicationContext<Application>()
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks by noOpDelegate() {
      override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?
      ) {
        app.unregisterActivityLifecycleCallbacks(this)
        waitForActivityOnCreate.countDown()
      }
    })

    activityRule.launchActivity(null)

    try {
      waitForActivityOnCreate.await()
    } catch (e: InterruptedException) {
      throw RuntimeException(e)
    }
  }

  private fun waitForActivityDestroy(): CountDownLatch {
    val latch = CountDownLatch(1)
    val countDownOnIdle = MessageQueue.IdleHandler {
      latch.countDown()
      false
    }
    val testActivity = activityRule.activity
    testActivity.application.registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks by noOpDelegate() {
          override fun onActivityDestroyed(activity: Activity) {
            if (activity == testActivity) {
              activity.application.unregisterActivityLifecycleCallbacks(this)
              Looper.myQueue()
                  .addIdleHandler(countDownOnIdle)
            }
          }
        })
    return latch
  }

  companion object {
    private const val TOUCH_MODE = true
    private const val LAUNCH_ACTIVITY = true
  }
}
