package leakcanary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import com.squareup.leakcanary.instrumentation.test.R
import leakcanary.TestUtils.assertLeak
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import shark.LeakTraceObject.LeakingStatus

class LifecycleLeaksTest : HasActivityTestRule<TestActivity> {

  class TestViewModel : ViewModel()

  class TestFragment : Fragment() {
    override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
    ): View {
      return View(context)
    }
  }

  class FragmentHoldingLeaky : Fragment() {
    val leaky = Any()
  }

  @get:Rule
  override val activityRule = activityTestRule<TestActivity>(
    initialTouchMode = false,
    launchActivity = false
  )

  @Before fun setUp() {
    AppWatcher.objectWatcher
      .clearAllObjectsTracked()
  }

  @After fun tearDown() {
    AppWatcher.objectWatcher
      .clearAllObjectsTracked()
  }

  @Test fun activityLeakDetected() {
    triggersOnActivityCreated {
      activityRule.launchActivity(null)
    }

    activity retained {
      triggersOnActivityDestroyed {
        activityRule.finishActivity()
      }
      assertLeak(TestActivity::class.java)
    }
  }

  @Test fun activityViewModelLeakDetected() {
    triggersOnActivityCreated {
      activityRule.launchActivity(null)
    }

    val viewModel = getOnMainSync {
      activity.installViewModel(TestViewModel::class)
    }

    viewModel retained {
      triggersOnActivityDestroyed {
        activityRule.finishActivity()
      }
      assertLeak(TestViewModel::class.java)
    }
  }

  @Test fun fragmentViewModelLeakDetected() {
    triggersOnActivityCreated {
      activityRule.launchActivity(null)
    }

    val viewModel = getOnMainSync {
      val fragment = Fragment()
      activity.addFragmentNow(fragment)
      val viewModel = fragment.installViewModel(TestViewModel::class)
      activity.removeFragmentNow(fragment)
      viewModel
    }

    viewModel retained {
      assertLeak(TestViewModel::class.java)
    }
  }

  @Test
  fun fragmentLeakDetected() {
    triggersOnActivityCreated {
      activityRule.launchActivity(null)
    }

    val fragment = getOnMainSync {
      val fragment = Fragment()
      activity.addFragmentNow(fragment)
      activity.removeFragmentNow(fragment)
      fragment
    }

    fragment retained {
      val expectedLeakClass = Fragment::class.java
      assertLeak { (heapAnalysis, leakTrace) ->
        val className = leakTrace.leakingObject.className
        assertThat(className)
          .describedAs("$heapAnalysis")
          .isEqualTo(expectedLeakClass.name)
        assertThat(leakTrace.leakingObject.leakingStatusReason)
          .describedAs("$heapAnalysis")
          .contains("Fragment.mLifecycleRegistry.state is DESTROYED")
      }
    }
  }

  @Test
  fun fragmentNotLeakingDetected() {
    triggersOnActivityCreated {
      activityRule.launchActivity(null)
    }

    getOnMainSync {
      val fragment = FragmentHoldingLeaky()
      activity.addFragmentNow(fragment)
      AppWatcher.objectWatcher.expectWeaklyReachable(fragment.leaky, "leaky leaks")
    }

    assertLeak { (heapAnalysis, leakTrace) ->
      val refToLeaky = leakTrace.referencePath.last()
      assertThat(refToLeaky.referenceName)
        .describedAs("$heapAnalysis")
        .isEqualTo("leaky")
      val fragment = refToLeaky.originObject
      // AssertJ uses lambdas when comparing enum values, which fails on older Android versions.
      if (fragment.leakingStatus != LeakingStatus.NOT_LEAKING) {
        throw AssertionError(
          "${fragment.leakingStatus} should be ${LeakingStatus.NOT_LEAKING}"
        )
      }
      assertThat(fragment.leakingStatusReason).isEqualTo(
        "Fragment.mLifecycleRegistry.state is RESUMED"
      )
    }
  }

  @Test
  fun fragmentViewLeakDetected() {
    triggersOnActivityCreated {
      activityRule.launchActivity(null)
    }

    val fragment = triggersOnFragmentCreated {
      getOnMainSync {
        val fragment = TestFragment()
        activity.replaceWithBackStack(fragment, R.id.fragments)
        fragment
      }
    }

    val fragmentView = getOnMainSync {
      fragment.view!!
    }

    triggersOnFragmentViewDestroyed {
      runOnMainSync {
        // Add a new fragment again, which destroys the view of the previous fragment and puts
        // the first fragment in the backstack.
        activity.replaceWithBackStack(Fragment(), R.id.fragments)
      }
    }

    fragmentView retained {
      assertLeak(View::class.java)
    }
  }
}
