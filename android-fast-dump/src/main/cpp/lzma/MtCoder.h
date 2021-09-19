/* MtCoder.h -- Multi-thread Coder
2018-07-04 : Igor Pavlov : Public domain */

#ifndef __MT_CODER_H
#define __MT_CODER_H

#include "MtDec.h"

EXTERN_C_BEGIN

/*
  if (    defined MTCODER__USE_WRITE_THREAD) : main thread writes all data blocks to output stream
  if (not defined MTCODER__USE_WRITE_THREAD) : any coder thread can write data blocks to output stream
*/
/* #define MTCODER__USE_WRITE_THREAD */

#ifndef _7ZIP_ST
  #define MTCODER__GET_NUM_BLOCKS_FROM_THREADS(numThreads) ((numThreads) + (numThreads) / 8 + 1)
  #define MTCODER__THREADS_MAX 64
  #define MTCODER__BLOCKS_MAX (MTCODER__GET_NUM_BLOCKS_FROM_THREADS(MTCODER__THREADS_MAX) + 3)
#else
  #define MTCODER__THREADS_MAX 1
  #define MTCODER__BLOCKS_MAX 1
#endif


#ifndef _7ZIP_ST


typedef struct
{
  ICompressProgress vt;
  CMtProgress *mtProgress;
  UInt64 inSize;
  UInt64 outSize;
} CMtProgressThunk;

void MtProgressThunk_CreateVTable(CMtProgressThunk *p);
    
#define MtProgressThunk_Init(p) { (p)->inSize = 0; (p)->outSize = 0; }


struct _CMtCoder;


typedef struct
{
  struct _CMtCoder *mtCoder;
  unsigned index;
  int stop;
  Byte *inBuf;

  CAutoResetEvent startEvent;
  CThread thread;
} CMtCoderThread;


typedef struct
{
  SRes (*Code)(void *p, unsigned coderIndex, unsigned outBufIndex,
      const Byte *src, size_t srcSize, int finished);
  SRes (*Write)(void *p, unsigned outBufIndex);
} IMtCoderCallback2;


typedef struct
{
  SRes res;
  unsigned bufIndex;
  BoolInt finished;
} CMtCoderBlock;


typedef struct _CMtCoder
{
  /* input variables */
  
  size_t blockSize;        /* size of input block */
  unsigned numThreadsMax;
  UInt64 expectedDataSize;

  ISeqInStream *inStream;
  const Byte *inData;
  size_t inDataSize;

  ICompressProgress *progress;
  ISzAllocPtr allocBig;

  IMtCoderCallback2 *mtCallback;
  void *mtCallbackObject;

  
  /* internal variables */
  
  size_t allocatedBufsSize;

  CAutoResetEvent readEvent;
  CSemaphore blocksSemaphore;

  BoolInt stopReading;
  SRes readRes;

  #ifdef MTCODER__USE_WRITE_THREAD
    CAutoResetEvent writeEvents[MTCODER__BLOCKS_MAX];
  #else
    CAutoResetEvent finishedEvent;
    SRes writeRes;
    unsigned writeIndex;
    Byte ReadyBlocks[MTCODER__BLOCKS_MAX];
    LONG numFinishedThreads;
  #endif

  unsigned numStartedThreadsLimit;
  unsigned numStartedThreads;

  unsigned numBlocksMax;
  unsigned blockIndex;
  UInt64 readProcessed;

  CCriticalSection cs;

  unsigned freeBlockHead;
  unsigned freeBlockList[MTCODER__BLOCKS_MAX];

  CMtProgress mtProgress;
  CMtCoderBlock blocks[MTCODER__BLOCKS_MAX];
  CMtCoderThread threads[MTCODER__THREADS_MAX];
} CMtCoder;


void MtCoder_Construct(CMtCoder *p);
void MtCoder_Destruct(CMtCoder *p);
SRes MtCoder_Code(CMtCoder *p);


#endif


EXTERN_C_END

#endif
