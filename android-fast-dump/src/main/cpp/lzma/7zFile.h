/* 7zFile.h -- File IO
2017-04-03 : Igor Pavlov : Public domain */

#ifndef __7Z_FILE_H
#define __7Z_FILE_H

#ifdef _WIN32
#define USE_WINDOWS_FILE
#endif

#ifdef USE_WINDOWS_FILE
#include <windows.h>
#else
#include <stdio.h>
#endif

#include "7zTypes.h"

EXTERN_C_BEGIN

/* ---------- File ---------- */

typedef struct
{
  #ifdef USE_WINDOWS_FILE
  HANDLE handle;
  #else
  FILE *file;
  #endif
} CSzFile;

void File_Construct(CSzFile *p);
#if !defined(UNDER_CE) || !defined(USE_WINDOWS_FILE)
WRes InFile_Open(CSzFile *p, const char *name);
WRes OutFile_Open(CSzFile *p, const char *name);
#endif
#ifdef USE_WINDOWS_FILE
WRes InFile_OpenW(CSzFile *p, const WCHAR *name);
WRes OutFile_OpenW(CSzFile *p, const WCHAR *name);
#endif
WRes File_Close(CSzFile *p);

/* reads max(*size, remain file's size) bytes */
WRes File_Read(CSzFile *p, void *data, size_t *size);

/* writes *size bytes */
WRes File_Write(CSzFile *p, const void *data, size_t *size);

WRes File_Seek(CSzFile *p, Int64 *pos, ESzSeek origin);
WRes File_GetLength(CSzFile *p, UInt64 *length);


/* ---------- FileInStream ---------- */

typedef struct
{
  ISeqInStream vt;
  CSzFile file;
} CFileSeqInStream;

void FileSeqInStream_CreateVTable(CFileSeqInStream *p);


typedef struct
{
  ISeekInStream vt;
  CSzFile file;
} CFileInStream;

void FileInStream_CreateVTable(CFileInStream *p);


typedef struct
{
  ISeqOutStream vt;
  CSzFile file;
} CFileOutStream;

void FileOutStream_CreateVTable(CFileOutStream *p);

EXTERN_C_END

#endif
