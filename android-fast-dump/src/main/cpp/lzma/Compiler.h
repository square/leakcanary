/* Compiler.h
2017-04-03 : Igor Pavlov : Public domain */

#ifndef __7Z_COMPILER_H
#define __7Z_COMPILER_H

#ifdef _MSC_VER

  #ifdef UNDER_CE
    #define RPC_NO_WINDOWS_H
    /* #pragma warning(disable : 4115) // '_RPC_ASYNC_STATE' : named type definition in parentheses */
    #pragma warning(disable : 4201) // nonstandard extension used : nameless struct/union
    #pragma warning(disable : 4214) // nonstandard extension used : bit field types other than int
  #endif

  #if _MSC_VER >= 1300
    #pragma warning(disable : 4996) // This function or variable may be unsafe
  #else
    #pragma warning(disable : 4511) // copy constructor could not be generated
    #pragma warning(disable : 4512) // assignment operator could not be generated
    #pragma warning(disable : 4514) // unreferenced inline function has been removed
    #pragma warning(disable : 4702) // unreachable code
    #pragma warning(disable : 4710) // not inlined
    #pragma warning(disable : 4714) // function marked as __forceinline not inlined
    #pragma warning(disable : 4786) // identifier was truncated to '255' characters in the debug information
  #endif

#endif

#define UNUSED_VAR(x) (void)x;
/* #define UNUSED_VAR(x) x=x; */

#endif
