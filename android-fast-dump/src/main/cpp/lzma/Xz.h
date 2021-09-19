/* Xz.h - Xz interface
2018-07-04 : Igor Pavlov : Public domain */

#ifndef __XZ_H
#define __XZ_H

#include "Sha256.h"

EXTERN_C_BEGIN

#define XZ_ID_Subblock 1
#define XZ_ID_Delta 3
#define XZ_ID_X86 4
#define XZ_ID_PPC 5
#define XZ_ID_IA64 6
#define XZ_ID_ARM 7
#define XZ_ID_ARMT 8
#define XZ_ID_SPARC 9
#define XZ_ID_LZMA2 0x21

unsigned Xz_ReadVarInt(const Byte *p, size_t maxSize, UInt64 *value);
unsigned Xz_WriteVarInt(Byte *buf, UInt64 v);

/* ---------- xz block ---------- */

#define XZ_BLOCK_HEADER_SIZE_MAX 1024

#define XZ_NUM_FILTERS_MAX 4
#define XZ_BF_NUM_FILTERS_MASK 3
#define XZ_BF_PACK_SIZE (1 << 6)
#define XZ_BF_UNPACK_SIZE (1 << 7)

#define XZ_FILTER_PROPS_SIZE_MAX 20

typedef struct
{
  UInt64 id;
  UInt32 propsSize;
  Byte props[XZ_FILTER_PROPS_SIZE_MAX];
} CXzFilter;

typedef struct
{
  UInt64 packSize;
  UInt64 unpackSize;
  Byte flags;
  CXzFilter filters[XZ_NUM_FILTERS_MAX];
} CXzBlock;

#define XzBlock_GetNumFilters(p) (((p)->flags & XZ_BF_NUM_FILTERS_MASK) + 1)
#define XzBlock_HasPackSize(p)   (((p)->flags & XZ_BF_PACK_SIZE) != 0)
#define XzBlock_HasUnpackSize(p) (((p)->flags & XZ_BF_UNPACK_SIZE) != 0)
#define XzBlock_HasUnsupportedFlags(p) (((p)->flags & ~(XZ_BF_NUM_FILTERS_MASK | XZ_BF_PACK_SIZE | XZ_BF_UNPACK_SIZE)) != 0)

SRes XzBlock_Parse(CXzBlock *p, const Byte *header);
SRes XzBlock_ReadHeader(CXzBlock *p, ISeqInStream *inStream, BoolInt *isIndex, UInt32 *headerSizeRes);

/* ---------- xz stream ---------- */

#define XZ_SIG_SIZE 6
#define XZ_FOOTER_SIG_SIZE 2

extern const Byte XZ_SIG[XZ_SIG_SIZE];

/*
extern const Byte XZ_FOOTER_SIG[XZ_FOOTER_SIG_SIZE];
*/

#define XZ_FOOTER_SIG_0 'Y'
#define XZ_FOOTER_SIG_1 'Z'

#define XZ_STREAM_FLAGS_SIZE 2
#define XZ_STREAM_CRC_SIZE 4

#define XZ_STREAM_HEADER_SIZE (XZ_SIG_SIZE + XZ_STREAM_FLAGS_SIZE + XZ_STREAM_CRC_SIZE)
#define XZ_STREAM_FOOTER_SIZE (XZ_FOOTER_SIG_SIZE + XZ_STREAM_FLAGS_SIZE + XZ_STREAM_CRC_SIZE + 4)

#define XZ_CHECK_MASK 0xF
#define XZ_CHECK_NO 0
#define XZ_CHECK_CRC32 1
#define XZ_CHECK_CRC64 4
#define XZ_CHECK_SHA256 10

typedef struct
{
  unsigned mode;
  UInt32 crc;
  UInt64 crc64;
  CSha256 sha;
} CXzCheck;

void XzCheck_Init(CXzCheck *p, unsigned mode);
void XzCheck_Update(CXzCheck *p, const void *data, size_t size);
int XzCheck_Final(CXzCheck *p, Byte *digest);

typedef UInt16 CXzStreamFlags;

#define XzFlags_IsSupported(f) ((f) <= XZ_CHECK_MASK)
#define XzFlags_GetCheckType(f) ((f) & XZ_CHECK_MASK)
#define XzFlags_HasDataCrc32(f) (Xz_GetCheckType(f) == XZ_CHECK_CRC32)
unsigned XzFlags_GetCheckSize(CXzStreamFlags f);

SRes Xz_ParseHeader(CXzStreamFlags *p, const Byte *buf);
SRes Xz_ReadHeader(CXzStreamFlags *p, ISeqInStream *inStream);

typedef struct
{
  UInt64 unpackSize;
  UInt64 totalSize;
} CXzBlockSizes;

typedef struct
{
  CXzStreamFlags flags;
  size_t numBlocks;
  CXzBlockSizes *blocks;
  UInt64 startOffset;
} CXzStream;

void Xz_Construct(CXzStream *p);
void Xz_Free(CXzStream *p, ISzAllocPtr alloc);

#define XZ_SIZE_OVERFLOW ((UInt64)(Int64)-1)

UInt64 Xz_GetUnpackSize(const CXzStream *p);
UInt64 Xz_GetPackSize(const CXzStream *p);

typedef struct
{
  size_t num;
  size_t numAllocated;
  CXzStream *streams;
} CXzs;

void Xzs_Construct(CXzs *p);
void Xzs_Free(CXzs *p, ISzAllocPtr alloc);
SRes Xzs_ReadBackward(CXzs *p, ILookInStream *inStream, Int64 *startOffset, ICompressProgress *progress, ISzAllocPtr alloc);

UInt64 Xzs_GetNumBlocks(const CXzs *p);
UInt64 Xzs_GetUnpackSize(const CXzs *p);


// ECoderStatus values are identical to ELzmaStatus values of LZMA2 decoder

typedef enum
{
  CODER_STATUS_NOT_SPECIFIED,               /* use main error code instead */
  CODER_STATUS_FINISHED_WITH_MARK,          /* stream was finished with end mark. */
  CODER_STATUS_NOT_FINISHED,                /* stream was not finished */
  CODER_STATUS_NEEDS_MORE_INPUT             /* you must provide more input bytes */
} ECoderStatus;


// ECoderFinishMode values are identical to ELzmaFinishMode

typedef enum
{
  CODER_FINISH_ANY,   /* finish at any point */
  CODER_FINISH_END    /* block must be finished at the end */
} ECoderFinishMode;


typedef struct _IStateCoder
{
  void *p;
  void (*Free)(void *p, ISzAllocPtr alloc);
  SRes (*SetProps)(void *p, const Byte *props, size_t propSize, ISzAllocPtr alloc);
  void (*Init)(void *p);
  SRes (*Code2)(void *p, Byte *dest, SizeT *destLen, const Byte *src, SizeT *srcLen,
      int srcWasFinished, ECoderFinishMode finishMode,
      // int *wasFinished,
      ECoderStatus *status);
  SizeT (*Filter)(void *p, Byte *data, SizeT size);
} IStateCoder;



#define MIXCODER_NUM_FILTERS_MAX 4

typedef struct
{
  ISzAllocPtr alloc;
  Byte *buf;
  unsigned numCoders;

  Byte *outBuf;
  size_t outBufSize;
  size_t outWritten; // is equal to lzmaDecoder.dicPos (in outBuf mode)
  BoolInt wasFinished;
  SRes res;
  ECoderStatus status;
  // BoolInt SingleBufMode;
  
  int finished[MIXCODER_NUM_FILTERS_MAX - 1];
  size_t pos[MIXCODER_NUM_FILTERS_MAX - 1];
  size_t size[MIXCODER_NUM_FILTERS_MAX - 1];
  UInt64 ids[MIXCODER_NUM_FILTERS_MAX];
  SRes results[MIXCODER_NUM_FILTERS_MAX];
  IStateCoder coders[MIXCODER_NUM_FILTERS_MAX];
} CMixCoder;


typedef enum
{
  XZ_STATE_STREAM_HEADER,
  XZ_STATE_STREAM_INDEX,
  XZ_STATE_STREAM_INDEX_CRC,
  XZ_STATE_STREAM_FOOTER,
  XZ_STATE_STREAM_PADDING,
  XZ_STATE_BLOCK_HEADER,
  XZ_STATE_BLOCK,
  XZ_STATE_BLOCK_FOOTER
} EXzState;


typedef struct
{
  EXzState state;
  UInt32 pos;
  unsigned alignPos;
  unsigned indexPreSize;

  CXzStreamFlags streamFlags;
  
  UInt32 blockHeaderSize;
  UInt64 packSize;
  UInt64 unpackSize;

  UInt64 numBlocks; // number of finished blocks in current stream
  UInt64 indexSize;
  UInt64 indexPos;
  UInt64 padSize;

  UInt64 numStartedStreams;
  UInt64 numFinishedStreams;
  UInt64 numTotalBlocks;

  UInt32 crc;
  CMixCoder decoder;
  CXzBlock block;
  CXzCheck check;
  CSha256 sha;

  BoolInt parseMode;
  BoolInt headerParsedOk;
  BoolInt decodeToStreamSignature;
  unsigned decodeOnlyOneBlock;

  Byte *outBuf;
  size_t outBufSize;
  size_t outDataWritten; // the size of data in (outBuf) that were fully unpacked

  Byte shaDigest[SHA256_DIGEST_SIZE];
  Byte buf[XZ_BLOCK_HEADER_SIZE_MAX];
} CXzUnpacker;

/* alloc : aligned for cache line allocation is better */
void XzUnpacker_Construct(CXzUnpacker *p, ISzAllocPtr alloc);
void XzUnpacker_Init(CXzUnpacker *p);
void XzUnpacker_SetOutBuf(CXzUnpacker *p, Byte *outBuf, size_t outBufSize);
void XzUnpacker_Free(CXzUnpacker *p);

/*
  XzUnpacker
  The sequence for decoding functions:
  {
    XzUnpacker_Construct()
    [Decoding_Calls]
    XzUnpacker_Free()
  }

  [Decoding_Calls]

  There are 3 types of interfaces for [Decoding_Calls] calls:

  Interface-1 : Partial output buffers:
    {
      XzUnpacker_Init()
      for()
        XzUnpacker_Code();
    }
    
  Interface-2 : Direct output buffer:
    Use it, if you know exact size of decoded data, and you need
    whole xz unpacked data in one output buffer.
    xz unpacker doesn't allocate additional buffer for lzma2 dictionary in that mode.
    {
      XzUnpacker_Init()
      XzUnpacker_SetOutBufMode(); // to set output buffer and size
      for()
        XzUnpacker_Code(); // (dest = NULL) in XzUnpacker_Code()
    }

  Interface-3 : Direct output buffer : One call full decoding
    It unpacks whole input buffer to output buffer in one call.
    It uses Interface-2 internally.
    {
      XzUnpacker_CodeFull()
    }
*/

/*
finishMode:
  It has meaning only if the decoding reaches output limit (*destLen).
  CODER_FINISH_ANY - use smallest number of input bytes
  CODER_FINISH_END - read EndOfStream marker after decoding

Returns:
  SZ_OK
    status:
      CODER_STATUS_NOT_FINISHED,
      CODER_STATUS_NEEDS_MORE_INPUT - maybe there are more xz streams,
                                      call XzUnpacker_IsStreamWasFinished to check that current stream was finished
  SZ_ERROR_MEM  - Memory allocation error
  SZ_ERROR_DATA - Data error
  SZ_ERROR_UNSUPPORTED - Unsupported method or method properties
  SZ_ERROR_CRC  - CRC error
  // SZ_ERROR_INPUT_EOF - It needs more bytes in input buffer (src).

  SZ_ERROR_NO_ARCHIVE - the error with xz Stream Header with one of the following reasons:
     - xz Stream Signature failure
     - CRC32 of xz Stream Header is failed
     - The size of Stream padding is not multiple of four bytes.
    It's possible to get that error, if xz stream was finished and the stream
    contains some another data. In that case you can call XzUnpacker_GetExtraSize()
    function to get real size of xz stream.
*/


SRes XzUnpacker_Code(CXzUnpacker *p, Byte *dest, SizeT *destLen,
    const Byte *src, SizeT *srcLen, int srcFinished,
    ECoderFinishMode finishMode, ECoderStatus *status);

SRes XzUnpacker_CodeFull(CXzUnpacker *p, Byte *dest, SizeT *destLen,
    const Byte *src, SizeT *srcLen,
    ECoderFinishMode finishMode, ECoderStatus *status);

BoolInt XzUnpacker_IsStreamWasFinished(const CXzUnpacker *p);

/*
XzUnpacker_GetExtraSize() returns then number of uncofirmed bytes,
 if it's in (XZ_STATE_STREAM_HEADER) state or in (XZ_STATE_STREAM_PADDING) state.
These bytes can be some bytes after xz archive, or
it can be start of new xz stream.
 
Call XzUnpacker_GetExtraSize() after XzUnpacker_Code() function to detect real size of
xz stream in two cases, if XzUnpacker_Code() returns:
  res == SZ_OK && status == CODER_STATUS_NEEDS_MORE_INPUT
  res == SZ_ERROR_NO_ARCHIVE
*/

UInt64 XzUnpacker_GetExtraSize(const CXzUnpacker *p);


/*
  for random block decoding:
    XzUnpacker_Init();
    set CXzUnpacker::streamFlags
    XzUnpacker_PrepareToRandomBlockDecoding()
    loop
    {
      XzUnpacker_Code()
      XzUnpacker_IsBlockFinished()
    }
*/

void XzUnpacker_PrepareToRandomBlockDecoding(CXzUnpacker *p);
BoolInt XzUnpacker_IsBlockFinished(const CXzUnpacker *p);

#define XzUnpacker_GetPackSizeForIndex(p) ((p)->packSize + (p)->blockHeaderSize + XzFlags_GetCheckSize((p)->streamFlags))



/* ---------- Multi Threading Decoding ---------- */


typedef struct
{
  size_t inBufSize_ST;
  size_t outStep_ST;
  BoolInt ignoreErrors;
  
  #ifndef _7ZIP_ST
  unsigned numThreads;
  size_t inBufSize_MT;
  size_t memUseMax;
  #endif
} CXzDecMtProps;

void XzDecMtProps_Init(CXzDecMtProps *p);


typedef void * CXzDecMtHandle;

/*
  alloc    : XzDecMt uses CAlignOffsetAlloc for addresses allocated by (alloc).
  allocMid : for big allocations, aligned allocation is better
*/

CXzDecMtHandle XzDecMt_Create(ISzAllocPtr alloc, ISzAllocPtr allocMid);
void XzDecMt_Destroy(CXzDecMtHandle p);


typedef struct
{
  Byte UnpackSize_Defined;
  Byte NumStreams_Defined;
  Byte NumBlocks_Defined;

  Byte DataAfterEnd;
  Byte DecodingTruncated; // Decoding was Truncated, we need only partial output data

  UInt64 InSize;  // pack size processed
  UInt64 OutSize;

  UInt64 NumStreams;
  UInt64 NumBlocks;

  SRes DecodeRes;
  SRes ReadRes;
  SRes ProgressRes;
  SRes CombinedRes;
  SRes CombinedRes_Type;

} CXzStatInfo;

void XzStatInfo_Clear(CXzStatInfo *p);

/*
XzDecMt_Decode()
SRes:
  SZ_OK               - OK
  SZ_ERROR_MEM        - Memory allocation error
  SZ_ERROR_NO_ARCHIVE - is not xz archive
  SZ_ERROR_ARCHIVE    - Headers error
  SZ_ERROR_DATA       - Data Error
  SZ_ERROR_CRC        - CRC Error
  SZ_ERROR_INPUT_EOF  - it needs more input data
  SZ_ERROR_WRITE      - ISeqOutStream error
  (SZ_ERROR_READ)     - ISeqInStream errors
  (SZ_ERROR_PROGRESS) - ICompressProgress errors
  // SZ_ERROR_THREAD     - error in multi-threading functions
  MY_SRes_HRESULT_FROM_WRes(WRes_error) - error in multi-threading function
*/

SRes XzDecMt_Decode(CXzDecMtHandle p,
    const CXzDecMtProps *props,
    const UInt64 *outDataSize, // NULL means undefined
    int finishMode,            // 0 - partial unpacking is allowed, 1 - xz stream(s) must be finished
    ISeqOutStream *outStream,
    // Byte *outBuf, size_t *outBufSize,
    ISeqInStream *inStream,
    // const Byte *inData, size_t inDataSize,
    CXzStatInfo *stat,
    int *isMT,                 // 0 means that ST (Single-Thread) version was used
    ICompressProgress *progress);

EXTERN_C_END

#endif
