package com.example.leakcanary

import android.view.View

object LeakingSingleton {
  val leakedViews = mutableListOf<View>()
}