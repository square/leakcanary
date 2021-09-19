/* RotateDefs.h -- Rotate functions
2015-03-25 : Igor Pavlov : Public domain */

#ifndef __ROTATE_DEFS_H
#define __ROTATE_DEFS_H

#ifdef _MSC_VER

#include <stdlib.h>

/* don't use _rotl with MINGW. It can insert slow call to function. */
 
/* #if (_MSC_VER >= 1200) */
#pragma intrinsic(_rotl)
#pragma intrinsic(_rotr)
/* #endif */

#define rotlFixed(x, n) _rotl((x), (n))
#define rotrFixed(x, n) _rotr((x), (n))

#else

/* new compilers can translate these macros to fast commands. */

#define rotlFixed(x, n) (((x) << (n)) | ((x) >> (32 - (n))))
#define rotrFixed(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

#endif

#endif
