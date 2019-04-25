package leakcanary

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo

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

  private fun isInServiceProcess(
    context: Context,
    serviceClass: Class<out Service>
  ): Boolean {
    val packageManager = context.packageManager
    val packageInfo: PackageInfo
    try {
      packageInfo = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
    } catch (e: Exception) {
      CanaryLog.d(e, "Could not get package info for %s", context.packageName)
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
      CanaryLog.d("Did not expect service %s to have a null process name", serviceClass)
      return false
    } else if (serviceInfo.processName == mainProcess) {
      CanaryLog.d(
          "Did not expect service %s to run in main process %s", serviceClass, mainProcess
      )
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
      CanaryLog.d("Could not get running app processes %d", exception)
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
      CanaryLog.d("Could not find running process for %d", myPid)
      return false
    }

    return myProcess.processName == serviceInfo.processName
  }
}