package com.example.leakcanary.tv

import android.view.View

object LeakingSingleton {
  val leakedViews = mutableListOf<View>()
}