/* 7zCrcOpt.c -- CRC32 calculation
2017-04-03 : Igor Pavlov : Public domain */

#include "Precomp.h"

#include "CpuArch.h"

#ifndef MY_CPU_BE

#define CRC_UPDATE_BYTE_2(crc, b) (table[((crc) ^ (b)) & 0xFF] ^ ((crc) >> 8))

UInt32 MY_FAST_CALL CrcUpdateT4(UInt32 v, const void *data, size_t size, const UInt32 *table)
{
  const Byte *p = (const Byte *)data;
  for (; size > 0 && ((unsigned)(ptrdiff_t)p & 3) != 0; size--, p++)
    v = CRC_UPDATE_BYTE_2(v, *p);
  for (; size >= 4; size -= 4, p += 4)
  {
    v ^= *(const UInt32 *)p;
    v =
          (table + 0x300)[((v      ) & 0xFF)]
        ^ (table + 0x200)[((v >>  8) & 0xFF)]
        ^ (table + 0x100)[((v >> 16) & 0xFF)]
        ^ (table + 0x000)[((v >> 24))];
  }
  for (; size > 0; size--, p++)
    v = CRC_UPDATE_BYTE_2(v, *p);
  return v;
}

UInt32 MY_FAST_CALL CrcUpdateT8(UInt32 v, const void *data, size_t size, const UInt32 *table)
{
  const Byte *p = (const Byte *)data;
  for (; size > 0 && ((unsigned)(ptrdiff_t)p & 7) != 0; size--, p++)
    v = CRC_UPDATE_BYTE_2(v, *p);
  for (; size >= 8; size -= 8, p += 8)
  {
    UInt32 d;
    v ^= *(const UInt32 *)p;
    v =
          (table + 0x700)[((v      ) & 0xFF)]
        ^ (table + 0x600)[((v >>  8) & 0xFF)]
        ^ (table + 0x500)[((v >> 16) & 0xFF)]
        ^ (table + 0x400)[((v >> 24))];
    d = *((const UInt32 *)p + 1);
    v ^=
          (table + 0x300)[((d      ) & 0xFF)]
        ^ (table + 0x200)[((d >>  8) & 0xFF)]
        ^ (table + 0x100)[((d >> 16) & 0xFF)]
        ^ (table + 0x000)[((d >> 24))];
  }
  for (; size > 0; size--, p++)
    v = CRC_UPDATE_BYTE_2(v, *p);
  return v;
}

#endif


#ifndef MY_CPU_LE

#define CRC_UINT32_SWAP(v) ((v >> 24) | ((v >> 8) & 0xFF00) | ((v << 8) & 0xFF0000) | (v << 24))

#define CRC_UPDATE_BYTE_2_BE(crc, b) (table[(((crc) >> 24) ^ (b))] ^ ((crc) << 8))

UInt32 MY_FAST_CALL CrcUpdateT1_BeT4(UInt32 v, const void *data, size_t size, const UInt32 *table)
{
  const Byte *p = (const Byte *)data;
  table += 0x100;
  v = CRC_UINT32_SWAP(v);
  for (; size > 0 && ((unsigned)(ptrdiff_t)p & 3) != 0; size--, p++)
    v = CRC_UPDATE_BYTE_2_BE(v, *p);
  for (; size >= 4; size -= 4, p += 4)
  {
    v ^= *(const UInt32 *)p;
    v =
          (table + 0x000)[((v      ) & 0xFF)]
        ^ (table + 0x100)[((v >>  8) & 0xFF)]
        ^ (table + 0x200)[((v >> 16) & 0xFF)]
        ^ (table + 0x300)[((v >> 24))];
  }
  for (; size > 0; size--, p++)
    v = CRC_UPDATE_BYTE_2_BE(v, *p);
  return CRC_UINT32_SWAP(v);
}

UInt32 MY_FAST_CALL CrcUpdateT1_BeT8(UInt32 v, const void *data, size_t size, const UInt32 *table)
{
  const Byte *p = (const Byte *)data;
  table += 0x100;
  v = CRC_UINT32_SWAP(v);
  for (; size > 0 && ((unsigned)(ptrdiff_t)p & 7) != 0; size--, p++)
    v = CRC_UPDATE_BYTE_2_BE(v, *p);
  for (; size >= 8; size -= 8, p += 8)
  {
    UInt32 d;
    v ^= *(const UInt32 *)p;
    v =
          (table + 0x400)[((v      ) & 0xFF)]
        ^ (table + 0x500)[((v >>  8) & 0xFF)]
        ^ (table + 0x600)[((v >> 16) & 0xFF)]
        ^ (table + 0x700)[((v >> 24))];
    d = *((const UInt32 *)p + 1);
    v ^=
          (table + 0x000)[((d      ) & 0xFF)]
        ^ (table + 0x100)[((d >>  8) & 0xFF)]
        ^ (table + 0x200)[((d >> 16) & 0xFF)]
        ^ (table + 0x300)[((d >> 24))];
  }
  for (; size > 0; size--, p++)
    v = CRC_UPDATE_BYTE_2_BE(v, *p);
  return CRC_UINT32_SWAP(v);
}

#endif
