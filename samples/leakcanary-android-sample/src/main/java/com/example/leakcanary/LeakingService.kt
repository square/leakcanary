package com.example.leakcanary

import android.app.Service
import android.content.Intent
import android.os.IBinder

class LeakingService : Service() {

  override fun onCreate() {
    super.onCreate()
    (application as ExampleApplication).leakedServices += this
    stopSelf()
  }

  override fun onBind(intent: Intent?): IBinder? {
    return null
  }
}