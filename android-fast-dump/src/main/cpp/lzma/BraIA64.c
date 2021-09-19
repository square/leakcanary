/* BraIA64.c -- Converter for IA-64 code
2017-01-26 : Igor Pavlov : Public domain */

#include "Precomp.h"

#include "CpuArch.h"
#include "Bra.h"

SizeT IA64_Convert(Byte *data, SizeT size, UInt32 ip, int encoding)
{
  SizeT i;
  if (size < 16)
    return 0;
  size -= 16;
  i = 0;
  do
  {
    unsigned m = ((UInt32)0x334B0000 >> (data[i] & 0x1E)) & 3;
    if (m)
    {
      m++;
      do
      {
        Byte *p = data + (i + (size_t)m * 5 - 8);
        if (((p[3] >> m) & 15) == 5
            && (((p[-1] | ((UInt32)p[0] << 8)) >> m) & 0x70) == 0)
        {
          unsigned raw = GetUi32(p);
          unsigned v = raw >> m;
          v = (v & 0xFFFFF) | ((v & (1 << 23)) >> 3);
          
          v <<= 4;
          if (encoding)
            v += ip + (UInt32)i;
          else
            v -= ip + (UInt32)i;
          v >>= 4;
          
          v &= 0x1FFFFF;
          v += 0x700000;
          v &= 0x8FFFFF;
          raw &= ~((UInt32)0x8FFFFF << m);
          raw |= (v << m);
          SetUi32(p, raw);
        }
      }
      while (++m <= 4);
    }
    i += 16;
  }
  while (i <= size);
  return i;
}
