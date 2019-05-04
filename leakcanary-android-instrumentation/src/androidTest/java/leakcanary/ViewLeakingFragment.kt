package leakcanary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.squareup.leakcanary.instrumentation.test.R

class ViewLeakingFragment : Fragment() {

  private var leakingView: View? = null

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ) = View(container?.context)

  override fun onViewCreated(
    view: View,
    savedInstanceState: Bundle?
  ) {
    // Leak: this fragment will stay in memory after being replaced, leakingView should be cleared
    // onDestroyView()
    leakingView = view
  }

  companion object {
    fun addToBackstack(activity: TestActivity) {
      getInstrumentation().runOnMainSync {
        activity.supportFragmentManager
            .beginTransaction()
            .addToBackStack(null)
            .replace(R.id.fragments, ViewLeakingFragment())
            .commit()
      }
    }
  }
}
