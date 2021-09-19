/* XzCrc64.c -- CRC64 calculation
2017-05-23 : Igor Pavlov : Public domain */

#include "Precomp.h"

#include "XzCrc64.h"
#include "CpuArch.h"

#define kCrc64Poly UINT64_CONST(0xC96C5795D7870F42)

#ifdef MY_CPU_LE
  #define CRC64_NUM_TABLES 4
#else
  #define CRC64_NUM_TABLES 5
  #define CRC_UINT64_SWAP(v) \
      ((v >> 56) \
    | ((v >> 40) & ((UInt64)0xFF <<  8)) \
    | ((v >> 24) & ((UInt64)0xFF << 16)) \
    | ((v >>  8) & ((UInt64)0xFF << 24)) \
    | ((v <<  8) & ((UInt64)0xFF << 32)) \
    | ((v << 24) & ((UInt64)0xFF << 40)) \
    | ((v << 40) & ((UInt64)0xFF << 48)) \
    | ((v << 56)))

  UInt64 MY_FAST_CALL XzCrc64UpdateT1_BeT4(UInt64 v, const void *data, size_t size, const UInt64 *table);
#endif

#ifndef MY_CPU_BE
  UInt64 MY_FAST_CALL XzCrc64UpdateT4(UInt64 v, const void *data, size_t size, const UInt64 *table);
#endif

typedef UInt64 (MY_FAST_CALL *CRC64_FUNC)(UInt64 v, const void *data, size_t size, const UInt64 *table);

static CRC64_FUNC g_Crc64Update;
UInt64 g_Crc64Table[256 * CRC64_NUM_TABLES];

UInt64 MY_FAST_CALL Crc64Update(UInt64 v, const void *data, size_t size)
{
  return g_Crc64Update(v, data, size, g_Crc64Table);
}

UInt64 MY_FAST_CALL Crc64Calc(const void *data, size_t size)
{
  return g_Crc64Update(CRC64_INIT_VAL, data, size, g_Crc64Table) ^ CRC64_INIT_VAL;
}

void MY_FAST_CALL Crc64GenerateTable()
{
  UInt32 i;
  for (i = 0; i < 256; i++)
  {
    UInt64 r = i;
    unsigned j;
    for (j = 0; j < 8; j++)
      r = (r >> 1) ^ (kCrc64Poly & ((UInt64)0 - (r & 1)));
    g_Crc64Table[i] = r;
  }
  for (i = 256; i < 256 * CRC64_NUM_TABLES; i++)
  {
    UInt64 r = g_Crc64Table[(size_t)i - 256];
    g_Crc64Table[i] = g_Crc64Table[r & 0xFF] ^ (r >> 8);
  }
  
  #ifdef MY_CPU_LE

  g_Crc64Update = XzCrc64UpdateT4;

  #else
  {
    #ifndef MY_CPU_BE
    UInt32 k = 1;
    if (*(const Byte *)&k == 1)
      g_Crc64Update = XzCrc64UpdateT4;
    else
    #endif
    {
      for (i = 256 * CRC64_NUM_TABLES - 1; i >= 256; i--)
      {
        UInt64 x = g_Crc64Table[(size_t)i - 256];
        g_Crc64Table[i] = CRC_UINT64_SWAP(x);
      }
      g_Crc64Update = XzCrc64UpdateT1_BeT4;
    }
  }
  #endif
}
