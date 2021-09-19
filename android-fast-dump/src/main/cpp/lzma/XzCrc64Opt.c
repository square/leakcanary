/* XzCrc64Opt.c -- CRC64 calculation
2017-06-30 : Igor Pavlov : Public domain */

#include "Precomp.h"

#include "CpuArch.h"

#ifndef MY_CPU_BE

#define CRC64_UPDATE_BYTE_2(crc, b) (table[((crc) ^ (b)) & 0xFF] ^ ((crc) >> 8))

UInt64 MY_FAST_CALL XzCrc64UpdateT4(UInt64 v, const void *data, size_t size, const UInt64 *table)
{
  const Byte *p = (const Byte *)data;
  for (; size > 0 && ((unsigned)(ptrdiff_t)p & 3) != 0; size--, p++)
    v = CRC64_UPDATE_BYTE_2(v, *p);
  for (; size >= 4; size -= 4, p += 4)
  {
    UInt32 d = (UInt32)v ^ *(const UInt32 *)p;
    v = (v >> 32)
        ^ (table + 0x300)[((d      ) & 0xFF)]
        ^ (table + 0x200)[((d >>  8) & 0xFF)]
        ^ (table + 0x100)[((d >> 16) & 0xFF)]
        ^ (table + 0x000)[((d >> 24))];
  }
  for (; size > 0; size--, p++)
    v = CRC64_UPDATE_BYTE_2(v, *p);
  return v;
}

#endif


#ifndef MY_CPU_LE

#define CRC_UINT64_SWAP(v) \
      ((v >> 56) \
    | ((v >> 40) & ((UInt64)0xFF <<  8)) \
    | ((v >> 24) & ((UInt64)0xFF << 16)) \
    | ((v >>  8) & ((UInt64)0xFF << 24)) \
    | ((v <<  8) & ((UInt64)0xFF << 32)) \
    | ((v << 24) & ((UInt64)0xFF << 40)) \
    | ((v << 40) & ((UInt64)0xFF << 48)) \
    | ((v << 56)))

#define CRC64_UPDATE_BYTE_2_BE(crc, b) (table[(Byte)((crc) >> 56) ^ (b)] ^ ((crc) << 8))

UInt64 MY_FAST_CALL XzCrc64UpdateT1_BeT4(UInt64 v, const void *data, size_t size, const UInt64 *table)
{
  const Byte *p = (const Byte *)data;
  table += 0x100;
  v = CRC_UINT64_SWAP(v);
  for (; size > 0 && ((unsigned)(ptrdiff_t)p & 3) != 0; size--, p++)
    v = CRC64_UPDATE_BYTE_2_BE(v, *p);
  for (; size >= 4; size -= 4, p += 4)
  {
    UInt32 d = (UInt32)(v >> 32) ^ *(const UInt32 *)p;
    v = (v << 32)
        ^ (table + 0x000)[((d      ) & 0xFF)]
        ^ (table + 0x100)[((d >>  8) & 0xFF)]
        ^ (table + 0x200)[((d >> 16) & 0xFF)]
        ^ (table + 0x300)[((d >> 24))];
  }
  for (; size > 0; size--, p++)
    v = CRC64_UPDATE_BYTE_2_BE(v, *p);
  return CRC_UINT64_SWAP(v);
}

#endif
