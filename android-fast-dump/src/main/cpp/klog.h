// Copyright 2020 Kwai, Inc. All rights reserved.

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include <android/log.h>
#include <cstring>
#include <errno.h>

#ifndef LOG_PRI
#define LOG_PRI(priority, tag, ...) __android_log_print(priority, tag, __VA_ARGS__)
#endif // LOG_PRI

#ifndef KLOG
#define KLOG(priority, tag, ...) LOG_PRI(ANDROID_##priority, tag, __VA_ARGS__)
#endif // KLOG

#ifndef KLOGD
#if NDK_DEBUG
#define KLOGD(...) ((void)KLOG(LOG_DEBUG, LOG_TAG, __VA_ARGS__))
#else
#define KLOGD(...) ((void)0)
#endif // NDK_DEBUG
#endif // KLOGD

#ifndef KLOGV
#define KLOGV(...) ((void)KLOG(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__))
#endif

#ifndef KLOGI
#define KLOGI(...) ((void)KLOG(LOG_INFO, LOG_TAG, __VA_ARGS__))
#endif

#ifndef KLOGW
#define KLOGW(...) ((void)KLOG(LOG_WARN, LOG_TAG, __VA_ARGS__))
#endif

#ifndef KLOGE
#define KLOGE(...) ((void)KLOG(LOG_ERROR, LOG_TAG, __VA_ARGS__))
#endif

#ifndef KLOGF
#define KLOGF(...) ((void)KLOG(LOG_FATAL, LOG_TAG, __VA_ARGS__))
#endif

#ifndef KCHECK_LOG
#define KCHECK_LOG(assertion)                                                                      \
  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,                                                  \
                      "CHECK failed at %s (line: %d) - <%s>: "                                     \
                      "%s: %s",                                                                    \
                      __FILE__, __LINE__, __FUNCTION__, #assertion, strerror(errno));
#endif

#ifndef KCHECK
#define KCHECK(assertion)                                                                          \
  if (__builtin_expect(!(assertion), false)) {                                                     \
    KCHECK_LOG(assertion)                                                                          \
  }
#endif

#ifndef KCHECKV
#define KCHECKV(assertion)                                                                         \
  if (__builtin_expect(!(assertion), false)) {                                                     \
    KCHECK_LOG(assertion)                                                                          \
    return;                                                                                        \
  }
#endif

#ifndef KCHECKI
#define KCHECKI(assertion)                                                                         \
  if (__builtin_expect(!(assertion), false)) {                                                     \
    KCHECK_LOG(assertion)                                                                          \
    return -1;                                                                                     \
  }
#endif

#ifndef KCHECKP
#define KCHECKP(assertion)                                                                         \
  if (__builtin_expect(!(assertion), false)) {                                                     \
    KCHECK_LOG(assertion)                                                                          \
    return nullptr;                                                                                \
  }
#endif

#ifndef KCHECKB
#define KCHECKB(assertion)                                                                         \
  if (__builtin_expect(!(assertion), false)) {                                                     \
    KCHECK_LOG(assertion)                                                                          \
    return false;                                                                                  \
  }
#endif

#ifndef KFINISHI_FUC
#define KFINISHI_FUC(assertion, func, ...)                                                         \
  if (__builtin_expect(!(assertion), false)) {                                                     \
    KCHECK_LOG(assertion)                                                                          \
    func(__VA_ARGS__);                                                                             \
    return -1;                                                                                     \
  }
#endif

#ifndef KFINISHB_FUC
#define KFINISHB_FUC(assertion, func, ...)                                                         \
  if (__builtin_expect(!(assertion), false)) {                                                     \
    KCHECK_LOG(assertion)                                                                          \
    func(__VA_ARGS__);                                                                             \
    return false;                                                                                  \
  }
#endif

#ifndef KFINISHP_FUC
#define KFINISHP_FUC(assertion, func, ...)                                                         \
  if (__builtin_expect(!(assertion), false)) {                                                     \
    KCHECK_LOG(assertion)                                                                          \
    func(__VA_ARGS__);                                                                             \
    return nullptr;                                                                                \
  }
#endif

#ifndef KFINISHV_FUC
#define KFINISHV_FUC(assertion, func, ...)                                                         \
  if (__builtin_expect(!(assertion), false)) {                                                     \
    KCHECK_LOG(assertion)                                                                          \
    func(__VA_ARGS__);                                                                             \
    return;                                                                                        \
  }
#endif