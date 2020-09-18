package com.example.leakcanary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiver created during direct boot.
 *
 * This allows to verify LeakCanary can properly be installed during direct boot with the sample app,
 * as it will cause the application to be created when the user hasn't unlocked their device yet.
 */
class DirectBootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    // no-op
  }
}
