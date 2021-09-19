/* AesOpt.c -- Intel's AES
2017-06-08 : Igor Pavlov : Public domain */

#include "Precomp.h"

#include "CpuArch.h"

#ifdef MY_CPU_X86_OR_AMD64
#if (_MSC_VER > 1500) || (_MSC_FULL_VER >= 150030729)
#define USE_INTEL_AES
#endif
#endif

#ifdef USE_INTEL_AES

#include <wmmintrin.h>

void MY_FAST_CALL AesCbc_Encode_Intel(__m128i *p, __m128i *data, size_t numBlocks)
{
  __m128i m = *p;
  for (; numBlocks != 0; numBlocks--, data++)
  {
    UInt32 numRounds2 = *(const UInt32 *)(p + 1) - 1;
    const __m128i *w = p + 3;
    m = _mm_xor_si128(m, *data);
    m = _mm_xor_si128(m, p[2]);
    do
    {
      m = _mm_aesenc_si128(m, w[0]);
      m = _mm_aesenc_si128(m, w[1]);
      w += 2;
    }
    while (--numRounds2 != 0);
    m = _mm_aesenc_si128(m, w[0]);
    m = _mm_aesenclast_si128(m, w[1]);
    *data = m;
  }
  *p = m;
}

#define NUM_WAYS 3

#define AES_OP_W(op, n) { \
    const __m128i t = w[n]; \
    m0 = op(m0, t); \
    m1 = op(m1, t); \
    m2 = op(m2, t); \
    }

#define AES_DEC(n) AES_OP_W(_mm_aesdec_si128, n)
#define AES_DEC_LAST(n) AES_OP_W(_mm_aesdeclast_si128, n)
#define AES_ENC(n) AES_OP_W(_mm_aesenc_si128, n)
#define AES_ENC_LAST(n) AES_OP_W(_mm_aesenclast_si128, n)

void MY_FAST_CALL AesCbc_Decode_Intel(__m128i *p, __m128i *data, size_t numBlocks)
{
  __m128i iv = *p;
  for (; numBlocks >= NUM_WAYS; numBlocks -= NUM_WAYS, data += NUM_WAYS)
  {
    UInt32 numRounds2 = *(const UInt32 *)(p + 1);
    const __m128i *w = p + numRounds2 * 2;
    __m128i m0, m1, m2;
    {
      const __m128i t = w[2];
      m0 = _mm_xor_si128(t, data[0]);
      m1 = _mm_xor_si128(t, data[1]);
      m2 = _mm_xor_si128(t, data[2]);
    }
    numRounds2--;
    do
    {
      AES_DEC(1)
      AES_DEC(0)
      w -= 2;
    }
    while (--numRounds2 != 0);
    AES_DEC(1)
    AES_DEC_LAST(0)

    {
      __m128i t;
      t = _mm_xor_si128(m0, iv); iv = data[0]; data[0] = t;
      t = _mm_xor_si128(m1, iv); iv = data[1]; data[1] = t;
      t = _mm_xor_si128(m2, iv); iv = data[2]; data[2] = t;
    }
  }
  for (; numBlocks != 0; numBlocks--, data++)
  {
    UInt32 numRounds2 = *(const UInt32 *)(p + 1);
    const __m128i *w = p + numRounds2 * 2;
    __m128i m = _mm_xor_si128(w[2], *data);
    numRounds2--;
    do
    {
      m = _mm_aesdec_si128(m, w[1]);
      m = _mm_aesdec_si128(m, w[0]);
      w -= 2;
    }
    while (--numRounds2 != 0);
    m = _mm_aesdec_si128(m, w[1]);
    m = _mm_aesdeclast_si128(m, w[0]);

    m = _mm_xor_si128(m, iv);
    iv = *data;
    *data = m;
  }
  *p = iv;
}

void MY_FAST_CALL AesCtr_Code_Intel(__m128i *p, __m128i *data, size_t numBlocks)
{
  __m128i ctr = *p;
  __m128i one;
  one.m128i_u64[0] = 1;
  one.m128i_u64[1] = 0;
  for (; numBlocks >= NUM_WAYS; numBlocks -= NUM_WAYS, data += NUM_WAYS)
  {
    UInt32 numRounds2 = *(const UInt32 *)(p + 1) - 1;
    const __m128i *w = p;
    __m128i m0, m1, m2;
    {
      const __m128i t = w[2];
      ctr = _mm_add_epi64(ctr, one); m0 = _mm_xor_si128(ctr, t);
      ctr = _mm_add_epi64(ctr, one); m1 = _mm_xor_si128(ctr, t);
      ctr = _mm_add_epi64(ctr, one); m2 = _mm_xor_si128(ctr, t);
    }
    w += 3;
    do
    {
      AES_ENC(0)
      AES_ENC(1)
      w += 2;
    }
    while (--numRounds2 != 0);
    AES_ENC(0)
    AES_ENC_LAST(1)
    data[0] = _mm_xor_si128(data[0], m0);
    data[1] = _mm_xor_si128(data[1], m1);
    data[2] = _mm_xor_si128(data[2], m2);
  }
  for (; numBlocks != 0; numBlocks--, data++)
  {
    UInt32 numRounds2 = *(const UInt32 *)(p + 1) - 1;
    const __m128i *w = p;
    __m128i m;
    ctr = _mm_add_epi64(ctr, one);
    m = _mm_xor_si128(ctr, p[2]);
    w += 3;
    do
    {
      m = _mm_aesenc_si128(m, w[0]);
      m = _mm_aesenc_si128(m, w[1]);
      w += 2;
    }
    while (--numRounds2 != 0);
    m = _mm_aesenc_si128(m, w[0]);
    m = _mm_aesenclast_si128(m, w[1]);
    *data = _mm_xor_si128(*data, m);
  }
  *p = ctr;
}

#else

void MY_FAST_CALL AesCbc_Encode(UInt32 *ivAes, Byte *data, size_t numBlocks);
void MY_FAST_CALL AesCbc_Decode(UInt32 *ivAes, Byte *data, size_t numBlocks);
void MY_FAST_CALL AesCtr_Code(UInt32 *ivAes, Byte *data, size_t numBlocks);

void MY_FAST_CALL AesCbc_Encode_Intel(UInt32 *p, Byte *data, size_t numBlocks)
{
  AesCbc_Encode(p, data, numBlocks);
}

void MY_FAST_CALL AesCbc_Decode_Intel(UInt32 *p, Byte *data, size_t numBlocks)
{
  AesCbc_Decode(p, data, numBlocks);
}

void MY_FAST_CALL AesCtr_Code_Intel(UInt32 *p, Byte *data, size_t numBlocks)
{
  AesCtr_Code(p, data, numBlocks);
}

#endif
