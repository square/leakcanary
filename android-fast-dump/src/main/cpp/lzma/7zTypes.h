/* 7zTypes.h -- Basic types
2018-08-04 : Igor Pavlov : Public domain */

#ifndef __7Z_TYPES_H
#define __7Z_TYPES_H

#ifdef _WIN32
/* #include <windows.h> */
#endif

#include <stddef.h>

#ifndef EXTERN_C_BEGIN
#ifdef __cplusplus
#define EXTERN_C_BEGIN extern "C" {
#define EXTERN_C_END }
#else
#define EXTERN_C_BEGIN
#define EXTERN_C_END
#endif
#endif

EXTERN_C_BEGIN

#define SZ_OK 0

#define SZ_ERROR_DATA 1
#define SZ_ERROR_MEM 2
#define SZ_ERROR_CRC 3
#define SZ_ERROR_UNSUPPORTED 4
#define SZ_ERROR_PARAM 5
#define SZ_ERROR_INPUT_EOF 6
#define SZ_ERROR_OUTPUT_EOF 7
#define SZ_ERROR_READ 8
#define SZ_ERROR_WRITE 9
#define SZ_ERROR_PROGRESS 10
#define SZ_ERROR_FAIL 11
#define SZ_ERROR_THREAD 12

#define SZ_ERROR_ARCHIVE 16
#define SZ_ERROR_NO_ARCHIVE 17

typedef int SRes;


#ifdef _WIN32

/* typedef DWORD WRes; */
typedef unsigned WRes;
#define MY_SRes_HRESULT_FROM_WRes(x) HRESULT_FROM_WIN32(x)

#else

typedef int WRes;
#define MY__FACILITY_WIN32 7
#define MY__FACILITY__WRes MY__FACILITY_WIN32
#define MY_SRes_HRESULT_FROM_WRes(x) ((HRESULT)(x) <= 0 ? ((HRESULT)(x)) : ((HRESULT) (((x) & 0x0000FFFF) | (MY__FACILITY__WRes << 16) | 0x80000000)))

#endif


#ifndef RINOK
#define RINOK(x) { int __result__ = (x); if (__result__ != 0) return __result__; }
#endif

typedef unsigned char Byte;
typedef short Int16;
typedef unsigned short UInt16;

#ifdef _LZMA_UINT32_IS_ULONG
typedef long Int32;
typedef unsigned long UInt32;
#else
typedef int Int32;
typedef unsigned int UInt32;
#endif

#ifdef _SZ_NO_INT_64

/* define _SZ_NO_INT_64, if your compiler doesn't support 64-bit integers.
   NOTES: Some code will work incorrectly in that case! */

typedef long Int64;
typedef unsigned long UInt64;

#else

#if defined(_MSC_VER) || defined(__BORLANDC__)
typedef __int64 Int64;
typedef unsigned __int64 UInt64;
#define UINT64_CONST(n) n
#else
typedef long long int Int64;
typedef unsigned long long int UInt64;
#define UINT64_CONST(n) n ## ULL
#endif

#endif

#ifdef _LZMA_NO_SYSTEM_SIZE_T
typedef UInt32 SizeT;
#else
typedef size_t SizeT;
#endif

typedef int BoolInt;
/* typedef BoolInt Bool; */
#define True 1
#define False 0


#ifdef _WIN32
#define MY_STD_CALL __stdcall
#else
#define MY_STD_CALL
#endif

#ifdef _MSC_VER

#if _MSC_VER >= 1300
#define MY_NO_INLINE __declspec(noinline)
#else
#define MY_NO_INLINE
#endif

#define MY_FORCE_INLINE __forceinline

#define MY_CDECL __cdecl
#define MY_FAST_CALL __fastcall

#else

#define MY_NO_INLINE
#define MY_FORCE_INLINE
#define MY_CDECL
#define MY_FAST_CALL

/* inline keyword : for C++ / C99 */

/* GCC, clang: */
/*
#if defined (__GNUC__) && (__GNUC__ >= 4)
#define MY_FORCE_INLINE __attribute__((always_inline))
#define MY_NO_INLINE __attribute__((noinline))
#endif
*/

#endif


/* The following interfaces use first parameter as pointer to structure */

typedef struct IByteIn IByteIn;
struct IByteIn
{
  Byte (*Read)(const IByteIn *p); /* reads one byte, returns 0 in case of EOF or error */
};
#define IByteIn_Read(p) (p)->Read(p)


typedef struct IByteOut IByteOut;
struct IByteOut
{
  void (*Write)(const IByteOut *p, Byte b);
};
#define IByteOut_Write(p, b) (p)->Write(p, b)


typedef struct ISeqInStream ISeqInStream;
struct ISeqInStream
{
  SRes (*Read)(const ISeqInStream *p, void *buf, size_t *size);
    /* if (input(*size) != 0 && output(*size) == 0) means end_of_stream.
       (output(*size) < input(*size)) is allowed */
};
#define ISeqInStream_Read(p, buf, size) (p)->Read(p, buf, size)

/* it can return SZ_ERROR_INPUT_EOF */
SRes SeqInStream_Read(const ISeqInStream *stream, void *buf, size_t size);
SRes SeqInStream_Read2(const ISeqInStream *stream, void *buf, size_t size, SRes errorType);
SRes SeqInStream_ReadByte(const ISeqInStream *stream, Byte *buf);


typedef struct ISeqOutStream ISeqOutStream;
struct ISeqOutStream
{
  size_t (*Write)(const ISeqOutStream *p, const void *buf, size_t size);
    /* Returns: result - the number of actually written bytes.
       (result < size) means error */
};
#define ISeqOutStream_Write(p, buf, size) (p)->Write(p, buf, size)

typedef enum
{
  SZ_SEEK_SET = 0,
  SZ_SEEK_CUR = 1,
  SZ_SEEK_END = 2
} ESzSeek;


typedef struct ISeekInStream ISeekInStream;
struct ISeekInStream
{
  SRes (*Read)(const ISeekInStream *p, void *buf, size_t *size);  /* same as ISeqInStream::Read */
  SRes (*Seek)(const ISeekInStream *p, Int64 *pos, ESzSeek origin);
};
#define ISeekInStream_Read(p, buf, size)   (p)->Read(p, buf, size)
#define ISeekInStream_Seek(p, pos, origin) (p)->Seek(p, pos, origin)


typedef struct ILookInStream ILookInStream;
struct ILookInStream
{
  SRes (*Look)(const ILookInStream *p, const void **buf, size_t *size);
    /* if (input(*size) != 0 && output(*size) == 0) means end_of_stream.
       (output(*size) > input(*size)) is not allowed
       (output(*size) < input(*size)) is allowed */
  SRes (*Skip)(const ILookInStream *p, size_t offset);
    /* offset must be <= output(*size) of Look */

  SRes (*Read)(const ILookInStream *p, void *buf, size_t *size);
    /* reads directly (without buffer). It's same as ISeqInStream::Read */
  SRes (*Seek)(const ILookInStream *p, Int64 *pos, ESzSeek origin);
};

#define ILookInStream_Look(p, buf, size)   (p)->Look(p, buf, size)
#define ILookInStream_Skip(p, offset)      (p)->Skip(p, offset)
#define ILookInStream_Read(p, buf, size)   (p)->Read(p, buf, size)
#define ILookInStream_Seek(p, pos, origin) (p)->Seek(p, pos, origin)


SRes LookInStream_LookRead(const ILookInStream *stream, void *buf, size_t *size);
SRes LookInStream_SeekTo(const ILookInStream *stream, UInt64 offset);

/* reads via ILookInStream::Read */
SRes LookInStream_Read2(const ILookInStream *stream, void *buf, size_t size, SRes errorType);
SRes LookInStream_Read(const ILookInStream *stream, void *buf, size_t size);



typedef struct
{
  ILookInStream vt;
  const ISeekInStream *realStream;
 
  size_t pos;
  size_t size; /* it's data size */
  
  /* the following variables must be set outside */
  Byte *buf;
  size_t bufSize;
} CLookToRead2;

void LookToRead2_CreateVTable(CLookToRead2 *p, int lookahead);

#define LookToRead2_Init(p) { (p)->pos = (p)->size = 0; }


typedef struct
{
  ISeqInStream vt;
  const ILookInStream *realStream;
} CSecToLook;

void SecToLook_CreateVTable(CSecToLook *p);



typedef struct
{
  ISeqInStream vt;
  const ILookInStream *realStream;
} CSecToRead;

void SecToRead_CreateVTable(CSecToRead *p);


typedef struct ICompressProgress ICompressProgress;

struct ICompressProgress
{
  SRes (*Progress)(const ICompressProgress *p, UInt64 inSize, UInt64 outSize);
    /* Returns: result. (result != SZ_OK) means break.
       Value (UInt64)(Int64)-1 for size means unknown value. */
};
#define ICompressProgress_Progress(p, inSize, outSize) (p)->Progress(p, inSize, outSize)



typedef struct ISzAlloc ISzAlloc;
typedef const ISzAlloc * ISzAllocPtr;

struct ISzAlloc
{
  void *(*Alloc)(ISzAllocPtr p, size_t size);
  void (*Free)(ISzAllocPtr p, void *address); /* address can be 0 */
};

#define ISzAlloc_Alloc(p, size) (p)->Alloc(p, size)
#define ISzAlloc_Free(p, a) (p)->Free(p, a)

/* deprecated */
#define IAlloc_Alloc(p, size) ISzAlloc_Alloc(p, size)
#define IAlloc_Free(p, a) ISzAlloc_Free(p, a)





#ifndef MY_offsetof
  #ifdef offsetof
    #define MY_offsetof(type, m) offsetof(type, m)
    /*
    #define MY_offsetof(type, m) FIELD_OFFSET(type, m)
    */
  #else
    #define MY_offsetof(type, m) ((size_t)&(((type *)0)->m))
  #endif
#endif



#ifndef MY_container_of

/*
#define MY_container_of(ptr, type, m) container_of(ptr, type, m)
#define MY_container_of(ptr, type, m) CONTAINING_RECORD(ptr, type, m)
#define MY_container_of(ptr, type, m) ((type *)((char *)(ptr) - offsetof(type, m)))
#define MY_container_of(ptr, type, m) (&((type *)0)->m == (ptr), ((type *)(((char *)(ptr)) - MY_offsetof(type, m))))
*/

/*
  GCC shows warning: "perhaps the 'offsetof' macro was used incorrectly"
    GCC 3.4.4 : classes with constructor
    GCC 4.8.1 : classes with non-public variable members"
*/

#define MY_container_of(ptr, type, m) ((type *)((char *)(1 ? (ptr) : &((type *)0)->m) - MY_offsetof(type, m)))


#endif

#define CONTAINER_FROM_VTBL_SIMPLE(ptr, type, m) ((type *)(ptr))

/*
#define CONTAINER_FROM_VTBL(ptr, type, m) CONTAINER_FROM_VTBL_SIMPLE(ptr, type, m)
*/
#define CONTAINER_FROM_VTBL(ptr, type, m) MY_container_of(ptr, type, m)

#define CONTAINER_FROM_VTBL_CLS(ptr, type, m) CONTAINER_FROM_VTBL_SIMPLE(ptr, type, m)
/*
#define CONTAINER_FROM_VTBL_CLS(ptr, type, m) CONTAINER_FROM_VTBL(ptr, type, m)
*/



#ifdef _WIN32

#define CHAR_PATH_SEPARATOR '\\'
#define WCHAR_PATH_SEPARATOR L'\\'
#define STRING_PATH_SEPARATOR "\\"
#define WSTRING_PATH_SEPARATOR L"\\"

#else

#define CHAR_PATH_SEPARATOR '/'
#define WCHAR_PATH_SEPARATOR L'/'
#define STRING_PATH_SEPARATOR "/"
#define WSTRING_PATH_SEPARATOR L"/"

#endif

EXTERN_C_END

#endif
