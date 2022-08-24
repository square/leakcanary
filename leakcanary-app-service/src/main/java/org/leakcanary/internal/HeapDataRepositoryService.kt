package org.leakcanary.internal

import android.app.Service
import android.content.Intent
import android.os.IBinder

internal class HeapDataRepositoryService : Service() {

  // TODO Stubs can be longer lived than the outer service, handle
  // manually clearing out the stub reference to the service.
  private val binder = object : HeapDataRepository.Stub() {
    override fun sayHi() {
      println("HeapDataRepository says hi")
    }
  }

  override fun onBind(intent: Intent): IBinder {
    // TODO Check signature of the calling app.
    // TODO Return null if we can't handle the caller's version
    return binder
  }
}
