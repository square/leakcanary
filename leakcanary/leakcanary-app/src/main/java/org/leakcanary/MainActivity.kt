package org.leakcanary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint
import org.leakcanary.internal.HeapDataRepository
import org.leakcanary.screens.BackStackViewModel
import org.leakcanary.screens.ScreenHost
import org.leakcanary.ui.theme.MyApplicationTheme
import shark.HeapAnalysis
import shark.SharkLog

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private var heapDataRepository: HeapDataRepository? = null

  private var connected by mutableStateOf(false)

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
      heapDataRepository = HeapDataRepository.Stub.asInterface(service)
      connected = true
    }

    override fun onServiceDisconnected(name: ComponentName) {
      heapDataRepository = null
      connected = false
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Dumb hack to ensure the backstack is created early enough in the activity
    // graph so that BackStackHolder will always have a ref.
    ViewModelProvider(this)[BackStackViewModel::class.java]

    val intent = Intent("org.leakcanary.internal.HeapDataRepositoryService.BIND")
      .apply {
        setPackage("com.example.leakcanary")
      }

    val bringingServiceUp = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    SharkLog.d { "HeapDataRepositoryService up=$bringingServiceUp" }

    setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          ScreenHost()
        }
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    unbindService(serviceConnection)
  }
}
