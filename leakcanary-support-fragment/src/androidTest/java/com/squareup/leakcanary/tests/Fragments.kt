package com.squareup.leakcanary.tests

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.util.concurrent.CountDownLatch

class Fragments private constructor() {

  init {
    throw AssertionError()
  }

  companion object {
    fun waitForFragmentDetached(activity: FragmentActivity): CountDownLatch {
      val latch = CountDownLatch(1)
      val fragmentManager = activity.supportFragmentManager
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

    fun waitForFragmentViewDestroyed(activity: FragmentActivity): CountDownLatch {
      val latch = CountDownLatch(1)
      val fragmentManager = activity.supportFragmentManager
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
  }
}
