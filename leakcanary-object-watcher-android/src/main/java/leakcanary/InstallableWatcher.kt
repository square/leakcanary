package leakcanary

interface InstallableWatcher {

  fun install()

  fun uninstall()
}