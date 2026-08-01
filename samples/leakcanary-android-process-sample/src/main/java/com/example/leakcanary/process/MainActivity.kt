package com.example.leakcanary.process

import android.app.Activity
import android.os.Bundle
import android.widget.Button

/**
 * Leaks itself on tap, so that installing this app is enough to watch a heap analysis run in the
 * `:leakcanary` process.
 */
class MainActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(
      Button(this).apply {
        text = "Leak this activity"
        setOnClickListener {
          leakedActivities += this@MainActivity
          finish()
        }
      }
    )
  }

  companion object {
    private val leakedActivities = mutableListOf<Activity>()
  }
}
