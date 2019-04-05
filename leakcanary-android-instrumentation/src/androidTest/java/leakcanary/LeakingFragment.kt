package leakcanary

import androidx.fragment.app.Fragment

import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation

class LeakingFragment : Fragment() {
  companion object {
    fun add(activity: TestActivity) {
      getInstrumentation().runOnMainSync {
        leakingFragment = LeakingFragment()
        activity.supportFragmentManager
            .beginTransaction()
            .add(0, leakingFragment)
            .commitNow()
      }
    }

    private lateinit var leakingFragment: LeakingFragment
  }
}
