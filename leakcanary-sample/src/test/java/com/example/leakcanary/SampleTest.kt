package com.example.leakcanary

import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SampleTest {
  @Test
  fun testTheThing() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    controller.get().findViewById<View>(R.id.async_work).performClick()
    controller.stop()
    controller.destroy()
  }
}
