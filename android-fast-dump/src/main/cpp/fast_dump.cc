#include "fast_dump.h"

#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <wait.h>

#include <cstdlib>
#include <memory>

#include "klog.h"
#include "kwai_dlfcn.h"

#define LOG_TAG "LeakCanary-fd"

using namespace kwai::linker;

namespace leakcanary {

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT
jboolean Java_leakcanary_FastDump_forkAndDumpHprofData(
    JNIEnv *env, jclass clazz ATTRIBUTE_UNUSED, jstring file_name) {
  pid_t pid = FastDump::GetInstance().SuspendAndFork();
  if (pid == 0) {
    // Set timeout for child process.
    alarm(60);
    prctl(PR_SET_NAME, "fast-dump-process");
    auto android_os_debug_class = env->FindClass("android/os/Debug");
    auto dump_hprof_data = env->GetStaticMethodID(
        android_os_debug_class, "dumpHprofData", "(Ljava/lang/String;)V");
    env->CallStaticVoidMethod(android_os_debug_class, dump_hprof_data,
                              file_name);
    _exit(0);
  }
  // Parent process.
  return FastDump::GetInstance().ResumeAndWait(pid);
}

FastDump &FastDump::GetInstance() {
  static FastDump hprof_dump;
  return hprof_dump;
}

FastDump::FastDump() : init_done_(false), android_api_(0) {
  android_api_ = android_get_device_api_level();
}

void FastDump::Initialize() {
  if (init_done_ || android_api_ < __ANDROID_API_L__) {
    return;
  }

  void *handle = kwai::linker::DlFcn::dlopen("libart.so", RTLD_NOW);
  KCHECKV(handle)

  if (android_api_ < __ANDROID_API_R__) {
    suspend_vm_fnc_ =
        (void (*)())DlFcn::dlsym(handle, "_ZN3art3Dbg9SuspendVMEv");
    KFINISHV_FNC(suspend_vm_fnc_, DlFcn::dlclose, handle)

    resume_vm_fnc_ = (void (*)())kwai::linker::DlFcn::dlsym(
        handle, "_ZN3art3Dbg8ResumeVMEv");
    KFINISHV_FNC(resume_vm_fnc_, DlFcn::dlclose, handle)
  }
  if (android_api_ == __ANDROID_API_R__) {
    // Over size for device compatibility.
    ssa_instance_ = std::make_unique<char[]>(64);
    sgc_instance_ = std::make_unique<char[]>(64);

    ssa_constructor_fnc_ = (void (*)(void *, const char *, bool))DlFcn::dlsym(
        handle, "_ZN3art16ScopedSuspendAllC1EPKcb");
    KFINISHV_FNC(ssa_constructor_fnc_, DlFcn::dlclose, handle)

    ssa_destructor_fnc_ =
        (void (*)(void *))DlFcn::dlsym(handle, "_ZN3art16ScopedSuspendAllD1Ev");
    KFINISHV_FNC(ssa_destructor_fnc_, DlFcn::dlclose, handle)

    sgc_constructor_fnc_ =
        (void (*)(void *, void *, GcCause, CollectorType))DlFcn::dlsym(
            handle,
            "_ZN3art2gc23ScopedGCCriticalSectionC1EPNS_6ThreadENS0_"
            "7GcCauseENS0_13CollectorTypeE");
    KFINISHV_FNC(sgc_constructor_fnc_, DlFcn::dlclose, handle)

    sgc_destructor_fnc_ = (void (*)(void *))DlFcn::dlsym(
        handle, "_ZN3art2gc23ScopedGCCriticalSectionD1Ev");
    KFINISHV_FNC(sgc_destructor_fnc_, DlFcn::dlclose, handle)

    mutator_lock_ptr_ =
        (void **)DlFcn::dlsym(handle, "_ZN3art5Locks13mutator_lock_E");
    KFINISHV_FNC(mutator_lock_ptr_, DlFcn::dlclose, handle)

    exclusive_lock_fnc_ = (void (*)(void *, void *))DlFcn::dlsym(
        handle, "_ZN3art17ReaderWriterMutex13ExclusiveLockEPNS_6ThreadE");
    KFINISHV_FNC(exclusive_lock_fnc_, DlFcn::dlclose, handle)

    exclusive_unlock_fnc_ = (void (*)(void *, void *))DlFcn::dlsym(
        handle, "_ZN3art17ReaderWriterMutex15ExclusiveUnlockEPNS_6ThreadE");
    KFINISHV_FNC(exclusive_unlock_fnc_, DlFcn::dlclose, handle)
  }
  DlFcn::dlclose(handle);
  init_done_ = true;
}

pid_t FastDump::SuspendAndFork() {
  Initialize();
  KCHECKI(init_done_)

  if (android_api_ < __ANDROID_API_R__) {
    suspend_vm_fnc_();
  }
  if (android_api_ == __ANDROID_API_R__) {
    void *self = __get_tls()[TLS_SLOT_ART_THREAD_SELF];
    sgc_constructor_fnc_((void *)sgc_instance_.get(), self, kGcCauseHprof,
                         kCollectorTypeHprof);
    ssa_constructor_fnc_((void *)ssa_instance_.get(), LOG_TAG, true);
    // Avoid deadlock with child process.
    exclusive_unlock_fnc_(*mutator_lock_ptr_, self);
    sgc_destructor_fnc_((void *)sgc_instance_.get());
  }

  return fork();
}

bool FastDump::ResumeAndWait(pid_t pid) {
  KCHECKB(init_done_)

  if (android_api_ < __ANDROID_API_R__) {
    resume_vm_fnc_();
  }
  if (android_api_ == __ANDROID_API_R__) {
    void *self = __get_tls()[TLS_SLOT_ART_THREAD_SELF];
    exclusive_lock_fnc_(*mutator_lock_ptr_, self);
    ssa_destructor_fnc_((void *)ssa_instance_.get());
  }
  int status;
  if (pid_t rc = TEMP_FAILURE_RETRY(waitpid(pid, &status, 0)) != pid) {
    // Unexpected error code. We will continue anyway.
    KLOGE("waitpid failed rc=%d: %s", rc, strerror(errno));
  }
  // The child process terminated abnormally.
  if (!WIFEXITED(status)) {
    KLOGE("Child process %d exited with status %d, terminated by signal %d",
          pid, WEXITSTATUS(status), WTERMSIG(status));
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

#ifdef __cplusplus
}
#endif

}  // namespace leakcanary