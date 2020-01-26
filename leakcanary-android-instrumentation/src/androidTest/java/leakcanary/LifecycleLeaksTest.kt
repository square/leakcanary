package leakcanary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import com.squareup.leakcanary.instrumentation.test.R
import leakcanary.TestUtils.assertLeak
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LifecycleLeaksTest : HasActivityTestRule<TestActivity> {

  class TestViewModel : ViewModel()

  class TestFragment : Fragment() {
    override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
    ): View? {
      return View(context)
    }
  }

  @get:Rule
  override val activityRule = activityTestRule<TestActivity>(
      initialTouchMode = false,
      launchActivity = false
  )

  @Before fun setUp() {
    AppWatcher.objectWatcher
        .clearWatchedObjects()
  }

  @After fun tearDown() {
    AppWatcher.objectWatcher
        .clearWatchedObjects()
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
      assertLeak(Fragment::class.java)
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
