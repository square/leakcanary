package leakcanary

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.util.concurrent.CountDownLatch

internal fun FragmentActivity.waitForFragmentDetached(): CountDownLatch {
  val latch = CountDownLatch(1)
  val fragmentManager = supportFragmentManager
  fragmentManager.registerFragmentLifecycleCallbacks(
      object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentDetached(
          fm: FragmentManager,
          f: Fragment
        ) {
          fragmentManager.unregisterFragmentLifecycleCallbacks(this)
          latch.countDown()
        }
      }, false
  )
  return latch
}

internal fun FragmentActivity.waitForFragmentViewDestroyed(): CountDownLatch {
  val latch = CountDownLatch(1)
  val fragmentManager = supportFragmentManager
  fragmentManager.registerFragmentLifecycleCallbacks(
      object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewDestroyed(
          fm: FragmentManager,
          f: Fragment
        ) {
          fragmentManager.unregisterFragmentLifecycleCallbacks(this)
          latch.countDown()
        }
      }, false
  )
  return latch
}
