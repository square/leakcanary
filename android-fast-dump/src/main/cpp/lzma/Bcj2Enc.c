/* Bcj2Enc.c -- BCJ2 Encoder (Converter for x86 code)
2018-07-04 : Igor Pavlov : Public domain */

#include "Precomp.h"

/* #define SHOW_STAT */

#ifdef SHOW_STAT
#include <stdio.h>
#define PRF(x) x
#else
#define PRF(x)
#endif

#include <string.h>

#include "Bcj2.h"
#include "CpuArch.h"

#define CProb UInt16

#define kTopValue ((UInt32)1 << 24)
#define kNumModelBits 11
#define kBitModelTotal (1 << kNumModelBits)
#define kNumMoveBits 5

void Bcj2Enc_Init(CBcj2Enc *p)
{
  unsigned i;

  p->state = BCJ2_ENC_STATE_OK;
  p->finishMode = BCJ2_ENC_FINISH_MODE_CONTINUE;

  p->prevByte = 0;

  p->cache = 0;
  p->range = 0xFFFFFFFF;
  p->low = 0;
  p->cacheSize = 1;

  p->ip = 0;

  p->fileIp = 0;
  p->fileSize = 0;
  p->relatLimit = BCJ2_RELAT_LIMIT;

  p->tempPos = 0;

  p->flushPos = 0;

  for (i = 0; i < sizeof(p->probs) / sizeof(p->probs[0]); i++)
    p->probs[i] = kBitModelTotal >> 1;
}

static BoolInt MY_FAST_CALL RangeEnc_ShiftLow(CBcj2Enc *p)
{
  if ((UInt32)p->low < (UInt32)0xFF000000 || (UInt32)(p->low >> 32) != 0)
  {
    Byte *buf = p->bufs[BCJ2_STREAM_RC];
    do
    {
      if (buf == p->lims[BCJ2_STREAM_RC])
      {
        p->state = BCJ2_STREAM_RC;
        p->bufs[BCJ2_STREAM_RC] = buf;
        return True;
      }
      *buf++ = (Byte)(p->cache + (Byte)(p->low >> 32));
      p->cache = 0xFF;
    }
    while (--p->cacheSize);
    p->bufs[BCJ2_STREAM_RC] = buf;
    p->cache = (Byte)((UInt32)p->low >> 24);
  }
  p->cacheSize++;
  p->low = (UInt32)p->low << 8;
  return False;
}

static void Bcj2Enc_Encode_2(CBcj2Enc *p)
{
  if (BCJ2_IS_32BIT_STREAM(p->state))
  {
    Byte *cur = p->bufs[p->state];
    if (cur == p->lims[p->state])
      return;
    SetBe32(cur, p->tempTarget);
    p->bufs[p->state] = cur + 4;
  }

  p->state = BCJ2_ENC_STATE_ORIG;

  for (;;)
  {
    if (p->range < kTopValue)
    {
      if (RangeEnc_ShiftLow(p))
        return;
      p->range <<= 8;
    }

    {
      {
        const Byte *src = p->src;
        const Byte *srcLim;
        Byte *dest;
        SizeT num = p->srcLim - src;

        if (p->finishMode == BCJ2_ENC_FINISH_MODE_CONTINUE)
        {
          if (num <= 4)
            return;
          num -= 4;
        }
        else if (num == 0)
          break;

        dest = p->bufs[BCJ2_STREAM_MAIN];
        if (num > (SizeT)(p->lims[BCJ2_STREAM_MAIN] - dest))
        {
          num = p->lims[BCJ2_STREAM_MAIN] - dest;
          if (num == 0)
          {
            p->state = BCJ2_STREAM_MAIN;
            return;
          }
        }
       
        srcLim = src + num;

        if (p->prevByte == 0x0F && (src[0] & 0xF0) == 0x80)
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
        
        num = src - p->src;
        
        if (src == srcLim)
        {
          p->prevByte = src[-1];
          p->bufs[BCJ2_STREAM_MAIN] = dest;
          p->src = src;
          p->ip += (UInt32)num;
          continue;
        }
 
        {
          Byte context = (Byte)(num == 0 ? p->prevByte : src[-1]);
          BoolInt needConvert;

          p->bufs[BCJ2_STREAM_MAIN] = dest + 1;
          p->ip += (UInt32)num + 1;
          src++;
          
          needConvert = False;

          if ((SizeT)(p->srcLim - src) >= 4)
          {
            UInt32 relatVal = GetUi32(src);
            if ((p->fileSize == 0 || (UInt32)(p->ip + 4 + relatVal - p->fileIp) < p->fileSize)
                && ((relatVal + p->relatLimit) >> 1) < p->relatLimit)
              needConvert = True;
          }

          {
            UInt32 bound;
            unsigned ttt;
            Byte b = src[-1];
            CProb *prob = p->probs + (unsigned)(b == 0xE8 ? 2 + (unsigned)context : (b == 0xE9 ? 1 : 0));

            ttt = *prob;
            bound = (p->range >> kNumModelBits) * ttt;
            
            if (!needConvert)
            {
              p->range = bound;
              *prob = (CProb)(ttt + ((kBitModelTotal - ttt) >> kNumMoveBits));
              p->src = src;
              p->prevByte = b;
              continue;
            }
            
            p->low += bound;
            p->range -= bound;
            *prob = (CProb)(ttt - (ttt >> kNumMoveBits));

            {
              UInt32 relatVal = GetUi32(src);
              UInt32 absVal;
              p->ip += 4;
              absVal = p->ip + relatVal;
              p->prevByte = src[3];
              src += 4;
              p->src = src;
              {
                unsigned cj = (b == 0xE8) ? BCJ2_STREAM_CALL : BCJ2_STREAM_JUMP;
                Byte *cur = p->bufs[cj];
                if (cur == p->lims[cj])
                {
                  p->state = cj;
                  p->tempTarget = absVal;
                  return;
                }
                SetBe32(cur, absVal);
                p->bufs[cj] = cur + 4;
              }
            }
          }
        }
      }
    }
  }

  if (p->finishMode != BCJ2_ENC_FINISH_MODE_END_STREAM)
    return;

  for (; p->flushPos < 5; p->flushPos++)
    if (RangeEnc_ShiftLow(p))
      return;
  p->state = BCJ2_ENC_STATE_OK;
}


void Bcj2Enc_Encode(CBcj2Enc *p)
{
  PRF(printf("\n"));
  PRF(printf("---- ip = %8d   tempPos = %8d   src = %8d\n", p->ip, p->tempPos, p->srcLim - p->src));

  if (p->tempPos != 0)
  {
    unsigned extra = 0;
   
    for (;;)
    {
      const Byte *src = p->src;
      const Byte *srcLim = p->srcLim;
      unsigned finishMode = p->finishMode;
      
      p->src = p->temp;
      p->srcLim = p->temp + p->tempPos;
      if (src != srcLim)
        p->finishMode = BCJ2_ENC_FINISH_MODE_CONTINUE;
      
      PRF(printf("     ip = %8d   tempPos = %8d   src = %8d\n", p->ip, p->tempPos, p->srcLim - p->src));

      Bcj2Enc_Encode_2(p);
      
      {
        unsigned num = (unsigned)(p->src - p->temp);
        unsigned tempPos = p->tempPos - num;
        unsigned i;
        p->tempPos = tempPos;
        for (i = 0; i < tempPos; i++)
          p->temp[i] = p->temp[(size_t)i + num];
      
        p->src = src;
        p->srcLim = srcLim;
        p->finishMode = finishMode;
        
        if (p->state != BCJ2_ENC_STATE_ORIG || src == srcLim)
          return;
        
        if (extra >= tempPos)
        {
          p->src = src - tempPos;
          p->tempPos = 0;
          break;
        }
        
        p->temp[tempPos] = src[0];
        p->tempPos = tempPos + 1;
        p->src = src + 1;
        extra++;
      }
    }
  }

  PRF(printf("++++ ip = %8d   tempPos = %8d   src = %8d\n", p->ip, p->tempPos, p->srcLim - p->src));

  Bcj2Enc_Encode_2(p);
  
  if (p->state == BCJ2_ENC_STATE_ORIG)
  {
    const Byte *src = p->src;
    unsigned rem = (unsigned)(p->srcLim - src);
    unsigned i;
    for (i = 0; i < rem; i++)
      p->temp[i] = src[i];
    p->tempPos = rem;
    p->src = src + rem;
  }
}
