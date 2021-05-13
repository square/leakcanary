#include "klog.h"
#include "kwai_dlfcn.h"
#include <cstdlib>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <wait.h>

#define LOG_TAG "fast_dump"

#ifdef __cplusplus
extern "C" {
#endif

static void init();
static bool initDumpHprofSymbols();
static pthread_once_t once_control = PTHREAD_ONCE_INIT;
static int android_api;
static bool init_success;

void (*suspendVM)();
void (*resumeVM)();

// Over size malloc ScopedSuspendAll instance for device compatibility
static void *g_SSA_handle = malloc(64);
void (*ScopedSuspendAllConstructor)(void *handle, const char *cause, bool long_suspend);
void (*ScopedSuspendAllDestructor)(void *handle);
// Over size malloc Hprof instance for device compatibility
static void *g_hprof_handle = malloc(128);
void (*HprofConstructor)(void *handle, const char *output_filename, int fd, bool direct_to_ddms);
void (*HprofDestructor)(void *handle);
void (*Dump)(void *handle);

JNIEXPORT
void Java_com_squareup_leakcanary_FastDump_forkDumpHprof(JNIEnv *env,
                                                         jclass clazz __attribute__((unused)),
                                                         jstring file_name) {
  pthread_once(&once_control, init);
  KCHECKV(init_success == true)

  if (android_api < __ANDROID_API_R__) {
    suspendVM();
    pid_t pid = fork();
    KCHECKV(pid != -1)
    if (pid != 0) {
      // Parent process
      resumeVM();
      int stat_loc;
      for (;;) {
        if (waitpid(pid, &stat_loc, 0) != -1 || errno != EINTR) {
          break;
        }
      }
      return;
    }
    // Set timeout for child process
    alarm(60);
    auto android_os_debug_class = env->FindClass("android/os/Debug");
    KCHECKV(android_os_debug_class)
    auto dump_hprof_data =
        env->GetStaticMethodID(android_os_debug_class, "dumpHprofData", "(Ljava/lang/String;)V");
    KCHECKV(dump_hprof_data)
    env->CallStaticVoidMethod(android_os_debug_class, dump_hprof_data, file_name);
    _exit(0);
  } else if (android_api == __ANDROID_API_R__) {
    ScopedSuspendAllConstructor(g_SSA_handle, LOG_TAG, true);
    pid_t pid = fork();
    KCHECKV(pid != -1)
    if (pid != 0) {
      // Parent process
      ScopedSuspendAllDestructor(g_SSA_handle);

      int stat_loc;
      for (;;) {
        if (waitpid(pid, &stat_loc, 0) != -1 || errno != EINTR) {
          break;
        }
      }
      return;
    }
    // Set timeout for child process
    alarm(60);
    const char *raw_file_name = env->GetStringUTFChars(file_name, nullptr);
    HprofConstructor(g_hprof_handle, raw_file_name, -1, false);
    Dump(g_hprof_handle);
    HprofDestructor(g_hprof_handle);
    env->ReleaseStringUTFChars(file_name, raw_file_name);
    _exit(0);
  }
}

static void init() { init_success = initDumpHprofSymbols(); }

static bool initDumpHprofSymbols() {
  android_api = android_get_device_api_level();
  KCHECKB(android_api > __ANDROID_API_K__)
  void *lib_handle = kwai::linker::DlFcn::dlopen("libart.so", RTLD_NOW);
  KCHECKB(lib_handle)

  if (android_api < __ANDROID_API_R__) {
    suspendVM = (void (*)())kwai::linker::DlFcn::dlsym(lib_handle, "_ZN3art3Dbg9SuspendVMEv");
    KFINISHB_FUC(suspendVM, kwai::linker::DlFcn::dlclose, lib_handle)
    resumeVM = (void (*)())kwai::linker::DlFcn::dlsym(lib_handle, "_ZN3art3Dbg8ResumeVMEv");
    KFINISHB_FUC(resumeVM, kwai::linker::DlFcn::dlclose, lib_handle)
  } else if (android_api == __ANDROID_API_R__) {
    ScopedSuspendAllConstructor = (void (*)(void *, const char *, bool))kwai::linker::DlFcn::dlsym(
        lib_handle, "_ZN3art16ScopedSuspendAllC1EPKcb");
    KFINISHB_FUC(ScopedSuspendAllConstructor, kwai::linker::DlFcn::dlclose, lib_handle)
    ScopedSuspendAllDestructor =
        (void (*)(void *))kwai::linker::DlFcn::dlsym(lib_handle, "_ZN3art16ScopedSuspendAllD1Ev");
    KFINISHB_FUC(ScopedSuspendAllDestructor, kwai::linker::DlFcn::dlclose, lib_handle)
  }

  kwai::linker::DlFcn::dlclose(lib_handle);

  // Parse .symtab(LOCAL)
  if (android_api == __ANDROID_API_R__) {
    lib_handle = kwai::linker::DlFcn::dlopen_elf("libart.so", RTLD_NOW);
    KCHECKB(lib_handle)
    HprofConstructor = (void (*)(void *, const char *, int, bool))kwai::linker::DlFcn::dlsym_elf(
        lib_handle, "_ZN3art5hprof5HprofC2EPKcib");
    KFINISHB_FUC(HprofConstructor, kwai::linker::DlFcn::dlclose_elf, lib_handle)
    HprofDestructor =
        (void (*)(void *))kwai::linker::DlFcn::dlsym_elf(lib_handle, "_ZN3art5hprof5HprofD0Ev");
    KFINISHB_FUC(HprofDestructor, kwai::linker::DlFcn::dlclose_elf, lib_handle)
    Dump =
        (void (*)(void *))kwai::linker::DlFcn::dlsym_elf(lib_handle, "_ZN3art5hprof5Hprof4DumpEv");
    KFINISHB_FUC(Dump, kwai::linker::DlFcn::dlclose_elf, lib_handle)
    kwai::linker::DlFcn::dlclose_elf(lib_handle);
  }

  return true;
}

#ifdef __cplusplus
}
#endif