/* Bra.c -- Converters for RISC code
2017-04-04 : Igor Pavlov : Public domain */

#include "Precomp.h"

#include "CpuArch.h"
#include "Bra.h"

SizeT ARM_Convert(Byte *data, SizeT size, UInt32 ip, int encoding)
{
  Byte *p;
  const Byte *lim;
  size &= ~(size_t)3;
  ip += 4;
  p = data;
  lim = data + size;

  if (encoding)

  for (;;)
  {
    for (;;)
    {
      if (p >= lim)
        return p - data;
      p += 4;
      if (p[-1] == 0xEB)
        break;
    }
    {
      UInt32 v = GetUi32(p - 4);
      v <<= 2;
        v += ip + (UInt32)(p - data);
      v >>= 2;
      v &= 0x00FFFFFF;
      v |= 0xEB000000;
      SetUi32(p - 4, v);
    }
  }

  for (;;)
  {
    for (;;)
    {
      if (p >= lim)
        return p - data;
      p += 4;
      if (p[-1] == 0xEB)
        break;
    }
    {
      UInt32 v = GetUi32(p - 4);
      v <<= 2;
        v -= ip + (UInt32)(p - data);
      v >>= 2;
      v &= 0x00FFFFFF;
      v |= 0xEB000000;
      SetUi32(p - 4, v);
    }
  }
}


SizeT ARMT_Convert(Byte *data, SizeT size, UInt32 ip, int encoding)
{
  Byte *p;
  const Byte *lim;
  size &= ~(size_t)1;
  p = data;
  lim = data + size - 4;

  if (encoding)
  
  for (;;)
  {
    UInt32 b1;
    for (;;)
    {
      UInt32 b3;
      if (p > lim)
        return p - data;
      b1 = p[1];
      b3 = p[3];
      p += 2;
      b1 ^= 8;
      if ((b3 & b1) >= 0xF8)
        break;
    }
    {
      UInt32 v =
             ((UInt32)b1 << 19)
          + (((UInt32)p[1] & 0x7) << 8)
          + (((UInt32)p[-2] << 11))
          + (p[0]);

      p += 2;
      {
        UInt32 cur = (ip + (UInt32)(p - data)) >> 1;
          v += cur;
      }

      p[-4] = (Byte)(v >> 11);
      p[-3] = (Byte)(0xF0 | ((v >> 19) & 0x7));
      p[-2] = (Byte)v;
      p[-1] = (Byte)(0xF8 | (v >> 8));
    }
  }
  
  for (;;)
  {
    UInt32 b1;
    for (;;)
    {
      UInt32 b3;
      if (p > lim)
        return p - data;
      b1 = p[1];
      b3 = p[3];
      p += 2;
      b1 ^= 8;
      if ((b3 & b1) >= 0xF8)
        break;
    }
    {
      UInt32 v =
             ((UInt32)b1 << 19)
          + (((UInt32)p[1] & 0x7) << 8)
          + (((UInt32)p[-2] << 11))
          + (p[0]);

      p += 2;
      {
        UInt32 cur = (ip + (UInt32)(p - data)) >> 1;
          v -= cur;
      }

      /*
      SetUi16(p - 4, (UInt16)(((v >> 11) & 0x7FF) | 0xF000));
      SetUi16(p - 2, (UInt16)(v | 0xF800));
      */
      
      p[-4] = (Byte)(v >> 11);
      p[-3] = (Byte)(0xF0 | ((v >> 19) & 0x7));
      p[-2] = (Byte)v;
      p[-1] = (Byte)(0xF8 | (v >> 8));
    }
  }
}


SizeT PPC_Convert(Byte *data, SizeT size, UInt32 ip, int encoding)
{
  Byte *p;
  const Byte *lim;
  size &= ~(size_t)3;
  ip -= 4;
  p = data;
  lim = data + size;

  for (;;)
  {
    for (;;)
    {
      if (p >= lim)
        return p - data;
      p += 4;
      /* if ((v & 0xFC000003) == 0x48000001) */
      if ((p[-4] & 0xFC) == 0x48 && (p[-1] & 3) == 1)
        break;
    }
    {
      UInt32 v = GetBe32(p - 4);
      if (encoding)
        v += ip + (UInt32)(p - data);
      else
        v -= ip + (UInt32)(p - data);
      v &= 0x03FFFFFF;
      v |= 0x48000000;
      SetBe32(p - 4, v);
    }
  }
}


SizeT SPARC_Convert(Byte *data, SizeT size, UInt32 ip, int encoding)
{
  Byte *p;
  const Byte *lim;
  size &= ~(size_t)3;
  ip -= 4;
  p = data;
  lim = data + size;

  for (;;)
  {
    for (;;)
    {
      if (p >= lim)
        return p - data;
      /*
      v = GetBe32(p);
      p += 4;
      m = v + ((UInt32)5 << 29);
      m ^= (UInt32)7 << 29;
      m += (UInt32)1 << 22;
      if ((m & ((UInt32)0x1FF << 23)) == 0)
        break;
      */
      p += 4;
      if ((p[-4] == 0x40 && (p[-3] & 0xC0) == 0) ||
          (p[-4] == 0x7F && (p[-3] >= 0xC0)))
        break;
    }
    {
      UInt32 v = GetBe32(p - 4);
      v <<= 2;
      if (encoding)
        v += ip + (UInt32)(p - data);
      else
        v -= ip + (UInt32)(p - data);
      
      v &= 0x01FFFFFF;
      v -= (UInt32)1 << 24;
      v ^= 0xFF000000;
      v >>= 2;
      v |= 0x40000000;
      SetBe32(p - 4, v);
    }
  }
}
