#ifndef LEAKCANARY_MACROS_H
#define LEAKCANARY_MACROS_H

// A macro to disallow the copy constructor and operator= functions
// This must be placed in the private: declarations for a class.
//
// For disallowing only assign or copy, delete the relevant operator or
// constructor, for example:
// void operator=(const TypeName&) = delete;
// Note, that most uses of DISALLOW_ASSIGN and DISALLOW_COPY are broken
// semantically, one should either use disallow both or neither. Try to
// avoid these in new code.
#define DISALLOW_COPY_AND_ASSIGN(TypeName) \
  TypeName(const TypeName &) = delete;     \
  void operator=(const TypeName &) = delete

// A deprecated function to call to create a false use of the parameter, for
// example:
//   int foo(int x) { UNUSED(x); return 10; }
// to avoid compiler warnings. Going forward we prefer ATTRIBUTE_UNUSED.
template <typename... T>
void UNUSED(const T &...) {}

// An attribute to place on a parameter to a function, for example:
//   int foo(int x ATTRIBUTE_UNUSED) { return 10; }
// to avoid compiler warnings.
#define ATTRIBUTE_UNUSED __attribute__((__unused__))

#if defined(__aarch64__)
#define __get_tls()                             \
  ({                                            \
    void **__val;                               \
    __asm__("mrs %0, tpidr_el0" : "=r"(__val)); \
    __val;                                      \
  })
#elif defined(__arm__)
#define __get_tls()                                      \
  ({                                                     \
    void **__val;                                        \
    __asm__("mrc p15, 0, %0, c13, c0, 3" : "=r"(__val)); \
    __val;                                               \
  })
#elif defined(__i386__)
#define __get_tls()                           \
  ({                                          \
    void **__val;                             \
    __asm__("movl %%gs:0, %0" : "=r"(__val)); \
    __val;                                    \
  })
#elif defined(__x86_64__)
#define __get_tls()                          \
  ({                                         \
    void **__val;                            \
    __asm__("mov %%fs:0, %0" : "=r"(__val)); \
    __val;                                   \
  })
#else
#error unsupported architecture
#endif

/** WARNING WARNING WARNING
 **
 ** This header file is *NOT* part of the public Bionic ABI/API and should not
 ** be used/included by user-serviceable parts of the system (e.g.
 ** applications).
 **
 ** It is only provided here for the benefit of Android components that need a
 ** pre-allocated slot for performance reasons (including ART, the OpenGL
 ** subsystem, and sanitizers).
 **/

// Bionic TCB / TLS slots:
//
//  - TLS_SLOT_SELF: On x86-{32,64}, the kernel makes TLS memory available via
//    the gs/fs segments. To get the address of a TLS variable, the first slot
//    of TLS memory (accessed using %gs:0 / %fs:0) holds the address of the
//    gs/fs segment. This slot is used by:
//     - OpenGL and compiler-rt
//     - Accesses of x86 ELF TLS variables
//
//  - TLS_SLOT_OPENGL and TLS_SLOT_OPENGL_API: These two aren't used by bionic
//    itself, but allow the graphics code to access TLS directly rather than
//    using the pthread API.
//
//  - TLS_SLOT_STACK_GUARD: Used for -fstack-protector by:
//     - Clang targeting Android/arm64
//     - gcc targeting Linux/x86-{32,64}
//
//  - TLS_SLOT_SANITIZER: Lets sanitizers avoid using pthread_getspecific for
//    finding the current thread state.
//
//  - TLS_SLOT_DTV: Pointer to ELF TLS dynamic thread vector.
//
//  - TLS_SLOT_ART_THREAD_SELF: Fast storage for Thread::Current() in ART.
//
//  - TLS_SLOT_BIONIC_TLS: Optimizes accesses to bionic_tls by one load versus
//    finding it using __get_thread().
//
//  - TLS_SLOT_APP: Available for use by apps in Android Q and later. (This slot
//    was used for errno in P and earlier.)

#if defined(__arm__) || defined(__aarch64__)

// The ARM ELF TLS ABI specifies[1] that the thread pointer points at a 2-word
// TCB followed by the executable's TLS segment. Both the TCB and the
// executable's segment are aligned according to the segment, so Bionic requires
// a minimum segment alignment, which effectively reserves an 8-word TCB. The
// ARM spec allocates the first TCB word to the DTV.
//
// [1] "Addenda to, and Errata in, the ABI for the ARM Architecture". Section 3.
// http://infocenter.arm.com/help/topic/com.arm.doc.ihi0045e/IHI0045E_ABI_addenda.pdf

#define MIN_TLS_SLOT (-1)  // update this value when reserving a slot
#define TLS_SLOT_BIONIC_TLS (-1)
#define TLS_SLOT_DTV 0
#define TLS_SLOT_THREAD_ID 1
#define TLS_SLOT_APP 2  // was historically used for errno
#define TLS_SLOT_OPENGL 3
#define TLS_SLOT_OPENGL_API 4
#define TLS_SLOT_STACK_GUARD 5
#define TLS_SLOT_SANITIZER 6  // was historically used for dlerror
#define TLS_SLOT_ART_THREAD_SELF 7

// The maximum slot is fixed by the minimum TLS alignment in Bionic executables.
#define MAX_TLS_SLOT 7

#elif defined(__i386__) || defined(__x86_64__)

// x86 uses variant 2 ELF TLS layout, which places the executable's TLS segment
// immediately before the thread pointer. New slots are allocated at positive
// offsets from the thread pointer.

#define MIN_TLS_SLOT 0

#define TLS_SLOT_SELF 0
#define TLS_SLOT_THREAD_ID 1
#define TLS_SLOT_APP 2  // was historically used for errno
#define TLS_SLOT_OPENGL 3
#define TLS_SLOT_OPENGL_API 4
#define TLS_SLOT_STACK_GUARD 5
#define TLS_SLOT_SANITIZER 6  // was historically used for dlerror
#define TLS_SLOT_ART_THREAD_SELF 7
#define TLS_SLOT_DTV 8
#define TLS_SLOT_BIONIC_TLS 9
#define MAX_TLS_SLOT 9  // update this value when reserving a slot

#endif

#define BIONIC_TLS_SLOTS (MAX_TLS_SLOT - MIN_TLS_SLOT + 1)

#endif  // LEAKCANARY_MACROS_H
