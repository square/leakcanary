/* XzEnc.h -- Xz Encode
2017-06-27 : Igor Pavlov : Public domain */

#ifndef __XZ_ENC_H
#define __XZ_ENC_H

#include "Lzma2Enc.h"

#include "Xz.h"

EXTERN_C_BEGIN


#define XZ_PROPS__BLOCK_SIZE__AUTO   LZMA2_ENC_PROPS__BLOCK_SIZE__AUTO
#define XZ_PROPS__BLOCK_SIZE__SOLID  LZMA2_ENC_PROPS__BLOCK_SIZE__SOLID


typedef struct
{
  UInt32 id;
  UInt32 delta;
  UInt32 ip;
  int ipDefined;
} CXzFilterProps;

void XzFilterProps_Init(CXzFilterProps *p);


typedef struct
{
  CLzma2EncProps lzma2Props;
  CXzFilterProps filterProps;
  unsigned checkId;
  UInt64 blockSize;
  int numBlockThreads_Reduced;
  int numBlockThreads_Max;
  int numTotalThreads;
  int forceWriteSizesInHeader;
  UInt64 reduceSize;
} CXzProps;

void XzProps_Init(CXzProps *p);


typedef void * CXzEncHandle;

CXzEncHandle XzEnc_Create(ISzAllocPtr alloc, ISzAllocPtr allocBig);
void XzEnc_Destroy(CXzEncHandle p);
SRes XzEnc_SetProps(CXzEncHandle p, const CXzProps *props);
void XzEnc_SetDataSize(CXzEncHandle p, UInt64 expectedDataSiize);
SRes XzEnc_Encode(CXzEncHandle p, ISeqOutStream *outStream, ISeqInStream *inStream, ICompressProgress *progress);

SRes Xz_Encode(ISeqOutStream *outStream, ISeqInStream *inStream,
    const CXzProps *props, ICompressProgress *progress);

SRes Xz_EncodeEmpty(ISeqOutStream *outStream);

EXTERN_C_END

#endif
