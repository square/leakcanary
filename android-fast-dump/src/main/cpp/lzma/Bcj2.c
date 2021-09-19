/* Bcj2.c -- BCJ2 Decoder (Converter for x86 code)
2018-04-28 : Igor Pavlov : Public domain */

#include "Precomp.h"

#include "Bcj2.h"
#include "CpuArch.h"

#define CProb UInt16

#define kTopValue ((UInt32)1 << 24)
#define kNumModelBits 11
#define kBitModelTotal (1 << kNumModelBits)
#define kNumMoveBits 5

#define _IF_BIT_0 ttt = *prob; bound = (p->range >> kNumModelBits) * ttt; if (p->code < bound)
#define _UPDATE_0 p->range = bound; *prob = (CProb)(ttt + ((kBitModelTotal - ttt) >> kNumMoveBits));
#define _UPDATE_1 p->range -= bound; p->code -= bound; *prob = (CProb)(ttt - (ttt >> kNumMoveBits));

void Bcj2Dec_Init(CBcj2Dec *p)
{
  unsigned i;

  p->state = BCJ2_DEC_STATE_OK;
  p->ip = 0;
  p->temp[3] = 0;
  p->range = 0;
  p->code = 0;
  for (i = 0; i < sizeof(p->probs) / sizeof(p->probs[0]); i++)
    p->probs[i] = kBitModelTotal >> 1;
}

SRes Bcj2Dec_Decode(CBcj2Dec *p)
{
  if (p->range <= 5)
  {
    p->state = BCJ2_DEC_STATE_OK;
    for (; p->range != 5; p->range++)
    {
      if (p->range == 1 && p->code != 0)
        return SZ_ERROR_DATA;
      
      if (p->bufs[BCJ2_STREAM_RC] == p->lims[BCJ2_STREAM_RC])
      {
        p->state = BCJ2_STREAM_RC;
        return SZ_OK;
      }

      p->code = (p->code << 8) | *(p->bufs[BCJ2_STREAM_RC])++;
    }
    
    if (p->code == 0xFFFFFFFF)
      return SZ_ERROR_DATA;
    
    p->range = 0xFFFFFFFF;
  }
  else if (p->state >= BCJ2_DEC_STATE_ORIG_0)
  {
    while (p->state <= BCJ2_DEC_STATE_ORIG_3)
    {
      Byte *dest = p->dest;
      if (dest == p->destLim)
        return SZ_OK;
      *dest = p->temp[(size_t)p->state - BCJ2_DEC_STATE_ORIG_0];
      p->state++;
      p->dest = dest + 1;
    }
  }

  /*
  if (BCJ2_IS_32BIT_STREAM(p->state))
  {
    const Byte *cur = p->bufs[p->state];
    if (cur == p->lims[p->state])
      return SZ_OK;
    p->bufs[p->state] = cur + 4;
    
    {
      UInt32 val;
      Byte *dest;
      SizeT rem;
      
      p->ip += 4;
      val = GetBe32(cur) - p->ip;
      dest = p->dest;
      rem = p->destLim - dest;
      if (rem < 4)
      {
        SizeT i;
        SetUi32(p->temp, val);
        for (i = 0; i < rem; i++)
          dest[i] = p->temp[i];
        p->dest = dest + rem;
        p->state = BCJ2_DEC_STATE_ORIG_0 + (unsigned)rem;
        return SZ_OK;
      }
      SetUi32(dest, val);
      p->temp[3] = (Byte)(val >> 24);
      p->dest = dest + 4;
      p->state = BCJ2_DEC_STATE_OK;
    }
  }
  */

  for (;;)
  {
    if (BCJ2_IS_32BIT_STREAM(p->state))
      p->state = BCJ2_DEC_STATE_OK;
    else
    {
      if (p->range < kTopValue)
      {
        if (p->bufs[BCJ2_STREAM_RC] == p->lims[BCJ2_STREAM_RC])
        {
          p->state = BCJ2_STREAM_RC;
          return SZ_OK;
        }
        p->range <<= 8;
        p->code = (p->code << 8) | *(p->bufs[BCJ2_STREAM_RC])++;
      }

      {
        const Byte *src = p->bufs[BCJ2_STREAM_MAIN];
        const Byte *srcLim;
        Byte *dest;
        SizeT num = p->lims[BCJ2_STREAM_MAIN] - src;
        
        if (num == 0)
        {
          p->state = BCJ2_STREAM_MAIN;
          return SZ_OK;
        }
        
        dest = p->dest;
        if (num > (SizeT)(p->destLim - dest))
        {
          num = p->destLim - dest;
          if (num == 0)
          {
            p->state = BCJ2_DEC_STATE_ORIG;
            return SZ_OK;
          }
        }
       
        srcLim = src + num;

        if (p->temp[3] == 0x0F && (src[0] & 0xF0) == 0x80)
          *dest = src[0];
        else for (;;)
        {
          Byte b = *src;
          *dest = b;
          if (b != 0x0F)
          {
            if ((b & 0xFE) == 0xE8)
              break;
            dest++;
            if (++src != srcLim)
              continue;
            break;
          }
          dest++;
          if (++src == srcLim)
            break;
          if ((*src & 0xF0) != 0x80)
            continue;
          *dest = *src;
          break;
        }
        
        num = src - p->bufs[BCJ2_STREAM_MAIN];
        
        if (src == srcLim)
        {
          p->temp[3] = src[-1];
          p->bufs[BCJ2_STREAM_MAIN] = src;
          p->ip += (UInt32)num;
          p->dest += num;
          p->state =
            p->bufs[BCJ2_STREAM_MAIN] ==
            p->lims[BCJ2_STREAM_MAIN] ?
              (unsigned)BCJ2_STREAM_MAIN :
              (unsigned)BCJ2_DEC_STATE_ORIG;
          return SZ_OK;
        }
        
        {
          UInt32 bound, ttt;
          CProb *prob;
          Byte b = src[0];
          Byte prev = (Byte)(num == 0 ? p->temp[3] : src[-1]);
          
          p->temp[3] = b;
          p->bufs[BCJ2_STREAM_MAIN] = src + 1;
          num++;
          p->ip += (UInt32)num;
          p->dest += num;
          
          prob = p->probs + (unsigned)(b == 0xE8 ? 2 + (unsigned)prev : (b == 0xE9 ? 1 : 0));
          
          _IF_BIT_0
          {
            _UPDATE_0
            continue;
          }
          _UPDATE_1
            
        }
      }
    }

    {
      UInt32 val;
      unsigned cj = (p->temp[3] == 0xE8) ? BCJ2_STREAM_CALL : BCJ2_STREAM_JUMP;
      const Byte *cur = p->bufs[cj];
      Byte *dest;
      SizeT rem;
      
      if (cur == p->lims[cj])
      {
        p->state = cj;
        break;
      }
      
      val = GetBe32(cur);
      p->bufs[cj] = cur + 4;

      p->ip += 4;
      val -= p->ip;
      dest = p->dest;
      rem = p->destLim - dest;
      
      if (rem < 4)
      {
        p->temp[0] = (Byte)val; if (rem > 0) dest[0] = (Byte)val; val >>= 8;
        p->temp[1] = (Byte)val; if (rem > 1) dest[1] = (Byte)val; val >>= 8;
        p->temp[2] = (Byte)val; if (rem > 2) dest[2] = (Byte)val; val >>= 8;
        p->temp[3] = (Byte)val;
        p->dest = dest + rem;
        p->state = BCJ2_DEC_STATE_ORIG_0 + (unsigned)rem;
        break;
      }
      
      SetUi32(dest, val);
      p->temp[3] = (Byte)(val >> 24);
      p->dest = dest + 4;
    }
  }

  if (p->range < kTopValue && p->bufs[BCJ2_STREAM_RC] != p->lims[BCJ2_STREAM_RC])
  {
    p->range <<= 8;
    p->code = (p->code << 8) | *(p->bufs[BCJ2_STREAM_RC])++;
  }

  return SZ_OK;
}
