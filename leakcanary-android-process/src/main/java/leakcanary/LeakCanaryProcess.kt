package leakcanary

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import shark.SharkLog

/**
 * Used to determine whether the current process is the LeakCanary analyzer process. By depending
 * on the `leakcanary-android-process` artifact instead of the `leakcanary-android`, LeakCanary
 * will automatically run its analysis in a separate process.
 *
 * As such, you'll need to be careful to do any custom configuration of LeakCanary in both the main
 * process and the analyzer process.
 */
object LeakCanaryProcess {

  @Volatile private var isInAnalyzerProcess: Boolean? = null

  /**
   * Whether the current process is the process running the heap analyzer, which is
   * a different process than the normal app process.
   */
  fun isInAnalyzerProcess(context: Context): Boolean {
    val analyzerServiceClass: Class<out Service>
    @Suppress("UNCHECKED_CAST")
    try {
      analyzerServiceClass =
        Class.forName("leakcanary.internal.HeapAnalyzerService") as Class<out Service>
    } catch (e: Exception) {
      return false
    }

    var isInAnalyzerProcess: Boolean? = isInAnalyzerProcess
    // This only needs to be computed once per process.
    if (isInAnalyzerProcess == null) {
      isInAnalyzerProcess = isInServiceProcess(context, analyzerServiceClass)
      this.isInAnalyzerProcess = isInAnalyzerProcess
    }
    return isInAnalyzerProcess
  }

  @Suppress("ReturnCount")
  private fun isInServiceProcess(
    context: Context,
    serviceClass: Class<out Service>
  ): Boolean {
    val packageManager = context.packageManager
    val packageInfo: PackageInfo
    try {
      packageInfo = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
    } catch (e: Exception) {
      SharkLog.d(e) { "Could not get package info for ${context.packageName}" }
      return false
    }

    val mainProcess = packageInfo.applicationInfo.processName

    val component = ComponentName(context, serviceClass)
    val serviceInfo: ServiceInfo
    try {
      serviceInfo =
        packageManager.getServiceInfo(component, PackageManager.GET_DISABLED_COMPONENTS)
    } catch (ignored: PackageManager.NameNotFoundException) {
      // Service is disabled.
      return false
    }

    if (serviceInfo.processName == null) {
      SharkLog.d { "Did not expect service $serviceClass to have a null process name" }
      return false
    } else if (serviceInfo.processName == mainProcess) {
      SharkLog.d { "Did not expect service $serviceClass to run in main process $mainProcess" }
      // Technically we are in the service process, but we're not in the service dedicated process.
      return false
    }

    val myPid = android.os.Process.myPid()
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    var myProcess: ActivityManager.RunningAppProcessInfo? = null
    val runningProcesses: List<ActivityManager.RunningAppProcessInfo>?
    try {
      runningProcesses = activityManager.runningAppProcesses
    } catch (exception: SecurityException) {
      // https://github.com/square/leakcanary/issues/948
      SharkLog.d { "Could not get running app processes $exception" }
      return false
    }

    if (runningProcesses != null) {
      for (process in runningProcesses) {
        if (process.pid == myPid) {
          myProcess = process
          break
        }
      }
    }
    if (myProcess == null) {
      SharkLog.d { "Could not find running process for $myPid" }
      return false
    }

    return myProcess.processName == serviceInfo.processName
  }
}