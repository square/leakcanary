package com.example.leakcanary

import android.view.View

class LeakingThread : Thread() {

  val leakedViews = mutableListOf<View>()

  init {
    name = "Leaking thread"
    start()
  }

  override fun run() {
    synchronized(obj) {
      obj.wait()
    }
  }

  companion object {
    private val obj = Object()
    val thread = LeakingThread()
  }
}