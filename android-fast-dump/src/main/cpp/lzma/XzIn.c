/* XzIn.c - Xz input
2018-07-04 : Igor Pavlov : Public domain */

#include "Precomp.h"

#include <string.h>

#include "7zCrc.h"
#include "CpuArch.h"
#include "Xz.h"

/*
#define XZ_FOOTER_SIG_CHECK(p) (memcmp((p), XZ_FOOTER_SIG, XZ_FOOTER_SIG_SIZE) == 0)
*/
#define XZ_FOOTER_SIG_CHECK(p) ((p)[0] == XZ_FOOTER_SIG_0 && (p)[1] == XZ_FOOTER_SIG_1)


SRes Xz_ReadHeader(CXzStreamFlags *p, ISeqInStream *inStream)
{
  Byte sig[XZ_STREAM_HEADER_SIZE];
  RINOK(SeqInStream_Read2(inStream, sig, XZ_STREAM_HEADER_SIZE, SZ_ERROR_NO_ARCHIVE));
  if (memcmp(sig, XZ_SIG, XZ_SIG_SIZE) != 0)
    return SZ_ERROR_NO_ARCHIVE;
  return Xz_ParseHeader(p, sig);
}

#define READ_VARINT_AND_CHECK(buf, pos, size, res) \
  { unsigned s = Xz_ReadVarInt(buf + pos, size - pos, res); \
  if (s == 0) return SZ_ERROR_ARCHIVE; pos += s; }

SRes XzBlock_ReadHeader(CXzBlock *p, ISeqInStream *inStream, BoolInt *isIndex, UInt32 *headerSizeRes)
{
  Byte header[XZ_BLOCK_HEADER_SIZE_MAX];
  unsigned headerSize;
  *headerSizeRes = 0;
  RINOK(SeqInStream_ReadByte(inStream, &header[0]));
  headerSize = (unsigned)header[0];
  if (headerSize == 0)
  {
    *headerSizeRes = 1;
    *isIndex = True;
    return SZ_OK;
  }

  *isIndex = False;
  headerSize = (headerSize << 2) + 4;
  *headerSizeRes = headerSize;
  RINOK(SeqInStream_Read(inStream, header + 1, headerSize - 1));
  return XzBlock_Parse(p, header);
}

#define ADD_SIZE_CHECK(size, val) \
  { UInt64 newSize = size + (val); if (newSize < size) return XZ_SIZE_OVERFLOW; size = newSize; }

UInt64 Xz_GetUnpackSize(const CXzStream *p)
{
  UInt64 size = 0;
  size_t i;
  for (i = 0; i < p->numBlocks; i++)
    ADD_SIZE_CHECK(size, p->blocks[i].unpackSize);
  return size;
}

UInt64 Xz_GetPackSize(const CXzStream *p)
{
  UInt64 size = 0;
  size_t i;
  for (i = 0; i < p->numBlocks; i++)
    ADD_SIZE_CHECK(size, (p->blocks[i].totalSize + 3) & ~(UInt64)3);
  return size;
}

/*
SRes XzBlock_ReadFooter(CXzBlock *p, CXzStreamFlags f, ISeqInStream *inStream)
{
  return SeqInStream_Read(inStream, p->check, XzFlags_GetCheckSize(f));
}
*/

static SRes Xz_ReadIndex2(CXzStream *p, const Byte *buf, size_t size, ISzAllocPtr alloc)
{
  size_t numBlocks, pos = 1;
  UInt32 crc;

  if (size < 5 || buf[0] != 0)
    return SZ_ERROR_ARCHIVE;

  size -= 4;
  crc = CrcCalc(buf, size);
  if (crc != GetUi32(buf + size))
    return SZ_ERROR_ARCHIVE;

  {
    UInt64 numBlocks64;
    READ_VARINT_AND_CHECK(buf, pos, size, &numBlocks64);
    numBlocks = (size_t)numBlocks64;
    if (numBlocks != numBlocks64 || numBlocks * 2 > size)
      return SZ_ERROR_ARCHIVE;
  }
  
  Xz_Free(p, alloc);
  if (numBlocks != 0)
  {
    size_t i;
    p->numBlocks = numBlocks;
    p->blocks = (CXzBlockSizes *)ISzAlloc_Alloc(alloc, sizeof(CXzBlockSizes) * numBlocks);
    if (!p->blocks)
      return SZ_ERROR_MEM;
    for (i = 0; i < numBlocks; i++)
    {
      CXzBlockSizes *block = &p->blocks[i];
      READ_VARINT_AND_CHECK(buf, pos, size, &block->totalSize);
      READ_VARINT_AND_CHECK(buf, pos, size, &block->unpackSize);
      if (block->totalSize == 0)
        return SZ_ERROR_ARCHIVE;
    }
  }
  while ((pos & 3) != 0)
    if (buf[pos++] != 0)
      return SZ_ERROR_ARCHIVE;
  return (pos == size) ? SZ_OK : SZ_ERROR_ARCHIVE;
}

static SRes Xz_ReadIndex(CXzStream *p, ILookInStream *stream, UInt64 indexSize, ISzAllocPtr alloc)
{
  SRes res;
  size_t size;
  Byte *buf;
  if (indexSize > ((UInt32)1 << 31))
    return SZ_ERROR_UNSUPPORTED;
  size = (size_t)indexSize;
  if (size != indexSize)
    return SZ_ERROR_UNSUPPORTED;
  buf = (Byte *)ISzAlloc_Alloc(alloc, size);
  if (!buf)
    return SZ_ERROR_MEM;
  res = LookInStream_Read2(stream, buf, size, SZ_ERROR_UNSUPPORTED);
  if (res == SZ_OK)
    res = Xz_ReadIndex2(p, buf, size, alloc);
  ISzAlloc_Free(alloc, buf);
  return res;
}

static SRes LookInStream_SeekRead_ForArc(ILookInStream *stream, UInt64 offset, void *buf, size_t size)
{
  RINOK(LookInStream_SeekTo(stream, offset));
  return LookInStream_Read(stream, buf, size);
  /* return LookInStream_Read2(stream, buf, size, SZ_ERROR_NO_ARCHIVE); */
}

static SRes Xz_ReadBackward(CXzStream *p, ILookInStream *stream, Int64 *startOffset, ISzAllocPtr alloc)
{
  UInt64 indexSize;
  Byte buf[XZ_STREAM_FOOTER_SIZE];
  UInt64 pos = *startOffset;

  if ((pos & 3) != 0 || pos < XZ_STREAM_FOOTER_SIZE)
    return SZ_ERROR_NO_ARCHIVE;

  pos -= XZ_STREAM_FOOTER_SIZE;
  RINOK(LookInStream_SeekRead_ForArc(stream, pos, buf, XZ_STREAM_FOOTER_SIZE));
  
  if (!XZ_FOOTER_SIG_CHECK(buf + 10))
  {
    UInt32 total = 0;
    pos += XZ_STREAM_FOOTER_SIZE;
    
    for (;;)
    {
      size_t i;
      #define TEMP_BUF_SIZE (1 << 10)
      Byte temp[TEMP_BUF_SIZE];
      
      i = (pos > TEMP_BUF_SIZE) ? TEMP_BUF_SIZE : (size_t)pos;
      pos -= i;
      RINOK(LookInStream_SeekRead_ForArc(stream, pos, temp, i));
      total += (UInt32)i;
      for (; i != 0; i--)
        if (temp[i - 1] != 0)
          break;
      if (i != 0)
      {
        if ((i & 3) != 0)
          return SZ_ERROR_NO_ARCHIVE;
        pos += i;
        break;
      }
      if (pos < XZ_STREAM_FOOTER_SIZE || total > (1 << 16))
        return SZ_ERROR_NO_ARCHIVE;
    }
    
    if (pos < XZ_STREAM_FOOTER_SIZE)
      return SZ_ERROR_NO_ARCHIVE;
    pos -= XZ_STREAM_FOOTER_SIZE;
    RINOK(LookInStream_SeekRead_ForArc(stream, pos, buf, XZ_STREAM_FOOTER_SIZE));
    if (!XZ_FOOTER_SIG_CHECK(buf + 10))
      return SZ_ERROR_NO_ARCHIVE;
  }
  
  p->flags = (CXzStreamFlags)GetBe16(buf + 8);

  if (!XzFlags_IsSupported(p->flags))
    return SZ_ERROR_UNSUPPORTED;

  if (GetUi32(buf) != CrcCalc(buf + 4, 6))
    return SZ_ERROR_ARCHIVE;

  indexSize = ((UInt64)GetUi32(buf + 4) + 1) << 2;

  if (pos < indexSize)
    return SZ_ERROR_ARCHIVE;

  pos -= indexSize;
  RINOK(LookInStream_SeekTo(stream, pos));
  RINOK(Xz_ReadIndex(p, stream, indexSize, alloc));

  {
    UInt64 totalSize = Xz_GetPackSize(p);
    if (totalSize == XZ_SIZE_OVERFLOW
        || totalSize >= ((UInt64)1 << 63)
        || pos < totalSize + XZ_STREAM_HEADER_SIZE)
      return SZ_ERROR_ARCHIVE;
    pos -= (totalSize + XZ_STREAM_HEADER_SIZE);
    RINOK(LookInStream_SeekTo(stream, pos));
    *startOffset = pos;
  }
  {
    CXzStreamFlags headerFlags;
    CSecToRead secToRead;
    SecToRead_CreateVTable(&secToRead);
    secToRead.realStream = stream;

    RINOK(Xz_ReadHeader(&headerFlags, &secToRead.vt));
    return (p->flags == headerFlags) ? SZ_OK : SZ_ERROR_ARCHIVE;
  }
}


/* ---------- Xz Streams ---------- */

void Xzs_Construct(CXzs *p)
{
  p->num = p->numAllocated = 0;
  p->streams = 0;
}

void Xzs_Free(CXzs *p, ISzAllocPtr alloc)
{
  size_t i;
  for (i = 0; i < p->num; i++)
    Xz_Free(&p->streams[i], alloc);
  ISzAlloc_Free(alloc, p->streams);
  p->num = p->numAllocated = 0;
  p->streams = 0;
}

UInt64 Xzs_GetNumBlocks(const CXzs *p)
{
  UInt64 num = 0;
  size_t i;
  for (i = 0; i < p->num; i++)
    num += p->streams[i].numBlocks;
  return num;
}

UInt64 Xzs_GetUnpackSize(const CXzs *p)
{
  UInt64 size = 0;
  size_t i;
  for (i = 0; i < p->num; i++)
    ADD_SIZE_CHECK(size, Xz_GetUnpackSize(&p->streams[i]));
  return size;
}

/*
UInt64 Xzs_GetPackSize(const CXzs *p)
{
  UInt64 size = 0;
  size_t i;
  for (i = 0; i < p->num; i++)
    ADD_SIZE_CHECK(size, Xz_GetTotalSize(&p->streams[i]));
  return size;
}
*/

SRes Xzs_ReadBackward(CXzs *p, ILookInStream *stream, Int64 *startOffset, ICompressProgress *progress, ISzAllocPtr alloc)
{
  Int64 endOffset = 0;
  RINOK(ILookInStream_Seek(stream, &endOffset, SZ_SEEK_END));
  *startOffset = endOffset;
  for (;;)
  {
    CXzStream st;
    SRes res;
    Xz_Construct(&st);
    res = Xz_ReadBackward(&st, stream, startOffset, alloc);
    st.startOffset = *startOffset;
    RINOK(res);
    if (p->num == p->numAllocated)
    {
      size_t newNum = p->num + p->num / 4 + 1;
      Byte *data = (Byte *)ISzAlloc_Alloc(alloc, newNum * sizeof(CXzStream));
      if (!data)
        return SZ_ERROR_MEM;
      p->numAllocated = newNum;
      if (p->num != 0)
        memcpy(data, p->streams, p->num * sizeof(CXzStream));
      ISzAlloc_Free(alloc, p->streams);
      p->streams = (CXzStream *)data;
    }
    p->streams[p->num++] = st;
    if (*startOffset == 0)
      break;
    RINOK(LookInStream_SeekTo(stream, *startOffset));
    if (progress && ICompressProgress_Progress(progress, endOffset - *startOffset, (UInt64)(Int64)-1) != SZ_OK)
      return SZ_ERROR_PROGRESS;
  }
  return SZ_OK;
}
