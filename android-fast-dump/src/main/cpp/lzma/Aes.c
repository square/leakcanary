/* Aes.c -- AES encryption / decryption
2017-01-24 : Igor Pavlov : Public domain */

#include "Precomp.h"

#include "Aes.h"
#include "CpuArch.h"

static UInt32 T[256 * 4];
static const Byte Sbox[256] = {
  0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
  0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
  0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
  0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
  0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
  0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
  0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
  0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
  0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
  0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
  0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
  0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
  0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
  0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
  0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
  0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16};

void MY_FAST_CALL AesCbc_Encode(UInt32 *ivAes, Byte *data, size_t numBlocks);
void MY_FAST_CALL AesCbc_Decode(UInt32 *ivAes, Byte *data, size_t numBlocks);
void MY_FAST_CALL AesCtr_Code(UInt32 *ivAes, Byte *data, size_t numBlocks);

void MY_FAST_CALL AesCbc_Encode_Intel(UInt32 *ivAes, Byte *data, size_t numBlocks);
void MY_FAST_CALL AesCbc_Decode_Intel(UInt32 *ivAes, Byte *data, size_t numBlocks);
void MY_FAST_CALL AesCtr_Code_Intel(UInt32 *ivAes, Byte *data, size_t numBlocks);

AES_CODE_FUNC g_AesCbc_Encode;
AES_CODE_FUNC g_AesCbc_Decode;
AES_CODE_FUNC g_AesCtr_Code;

static UInt32 D[256 * 4];
static Byte InvS[256];

static const Byte Rcon[11] = { 0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36 };

#define xtime(x) ((((x) << 1) ^ (((x) & 0x80) != 0 ? 0x1B : 0)) & 0xFF)

#define Ui32(a0, a1, a2, a3) ((UInt32)(a0) | ((UInt32)(a1) << 8) | ((UInt32)(a2) << 16) | ((UInt32)(a3) << 24))

#define gb0(x) ( (x)          & 0xFF)
#define gb1(x) (((x) >> ( 8)) & 0xFF)
#define gb2(x) (((x) >> (16)) & 0xFF)
#define gb3(x) (((x) >> (24)))

#define gb(n, x) gb ## n(x)

#define TT(x) (T + (x << 8))
#define DD(x) (D + (x << 8))


void AesGenTables(void)
{
  unsigned i;
  for (i = 0; i < 256; i++)
    InvS[Sbox[i]] = (Byte)i;
  
  for (i = 0; i < 256; i++)
  {
    {
      UInt32 a1 = Sbox[i];
      UInt32 a2 = xtime(a1);
      UInt32 a3 = a2 ^ a1;
      TT(0)[i] = Ui32(a2, a1, a1, a3);
      TT(1)[i] = Ui32(a3, a2, a1, a1);
      TT(2)[i] = Ui32(a1, a3, a2, a1);
      TT(3)[i] = Ui32(a1, a1, a3, a2);
    }
    {
      UInt32 a1 = InvS[i];
      UInt32 a2 = xtime(a1);
      UInt32 a4 = xtime(a2);
      UInt32 a8 = xtime(a4);
      UInt32 a9 = a8 ^ a1;
      UInt32 aB = a8 ^ a2 ^ a1;
      UInt32 aD = a8 ^ a4 ^ a1;
      UInt32 aE = a8 ^ a4 ^ a2;
      DD(0)[i] = Ui32(aE, a9, aD, aB);
      DD(1)[i] = Ui32(aB, aE, a9, aD);
      DD(2)[i] = Ui32(aD, aB, aE, a9);
      DD(3)[i] = Ui32(a9, aD, aB, aE);
    }
  }
  
  g_AesCbc_Encode = AesCbc_Encode;
  g_AesCbc_Decode = AesCbc_Decode;
  g_AesCtr_Code = AesCtr_Code;
  
  #ifdef MY_CPU_X86_OR_AMD64
  if (CPU_Is_Aes_Supported())
  {
    g_AesCbc_Encode = AesCbc_Encode_Intel;
    g_AesCbc_Decode = AesCbc_Decode_Intel;
    g_AesCtr_Code = AesCtr_Code_Intel;
  }
  #endif
}


#define HT(i, x, s) TT(x)[gb(x, s[(i + x) & 3])]

#define HT4(m, i, s, p) m[i] = \
    HT(i, 0, s) ^ \
    HT(i, 1, s) ^ \
    HT(i, 2, s) ^ \
    HT(i, 3, s) ^ w[p + i]

#define HT16(m, s, p) \
    HT4(m, 0, s, p); \
    HT4(m, 1, s, p); \
    HT4(m, 2, s, p); \
    HT4(m, 3, s, p); \

#define FT(i, x) Sbox[gb(x, m[(i + x) & 3])]
#define FT4(i) dest[i] = Ui32(FT(i, 0), FT(i, 1), FT(i, 2), FT(i, 3)) ^ w[i];


#define HD(i, x, s) DD(x)[gb(x, s[(i - x) & 3])]

#define HD4(m, i, s, p) m[i] = \
    HD(i, 0, s) ^ \
    HD(i, 1, s) ^ \
    HD(i, 2, s) ^ \
    HD(i, 3, s) ^ w[p + i];

#define HD16(m, s, p) \
    HD4(m, 0, s, p); \
    HD4(m, 1, s, p); \
    HD4(m, 2, s, p); \
    HD4(m, 3, s, p); \

#define FD(i, x) InvS[gb(x, m[(i - x) & 3])]
#define FD4(i) dest[i] = Ui32(FD(i, 0), FD(i, 1), FD(i, 2), FD(i, 3)) ^ w[i];

void MY_FAST_CALL Aes_SetKey_Enc(UInt32 *w, const Byte *key, unsigned keySize)
{
  unsigned i, wSize;
  wSize = keySize + 28;
  keySize /= 4;
  w[0] = ((UInt32)keySize / 2) + 3;
  w += 4;

  for (i = 0; i < keySize; i++, key += 4)
    w[i] = GetUi32(key);

  for (; i < wSize; i++)
  {
    UInt32 t = w[(size_t)i - 1];
    unsigned rem = i % keySize;
    if (rem == 0)
      t = Ui32(Sbox[gb1(t)] ^ Rcon[i / keySize], Sbox[gb2(t)], Sbox[gb3(t)], Sbox[gb0(t)]);
    else if (keySize > 6 && rem == 4)
      t = Ui32(Sbox[gb0(t)], Sbox[gb1(t)], Sbox[gb2(t)], Sbox[gb3(t)]);
    w[i] = w[i - keySize] ^ t;
  }
}

void MY_FAST_CALL Aes_SetKey_Dec(UInt32 *w, const Byte *key, unsigned keySize)
{
  unsigned i, num;
  Aes_SetKey_Enc(w, key, keySize);
  num = keySize + 20;
  w += 8;
  for (i = 0; i < num; i++)
  {
    UInt32 r = w[i];
    w[i] =
      DD(0)[Sbox[gb0(r)]] ^
      DD(1)[Sbox[gb1(r)]] ^
      DD(2)[Sbox[gb2(r)]] ^
      DD(3)[Sbox[gb3(r)]];
  }
}

/* Aes_Encode and Aes_Decode functions work with little-endian words.
  src and dest are pointers to 4 UInt32 words.
  src and dest can point to same block */

static void Aes_Encode(const UInt32 *w, UInt32 *dest, const UInt32 *src)
{
  UInt32 s[4];
  UInt32 m[4];
  UInt32 numRounds2 = w[0];
  w += 4;
  s[0] = src[0] ^ w[0];
  s[1] = src[1] ^ w[1];
  s[2] = src[2] ^ w[2];
  s[3] = src[3] ^ w[3];
  w += 4;
  for (;;)
  {
    HT16(m, s, 0);
    if (--numRounds2 == 0)
      break;
    HT16(s, m, 4);
    w += 8;
  }
  w += 4;
  FT4(0); FT4(1); FT4(2); FT4(3);
}

static void Aes_Decode(const UInt32 *w, UInt32 *dest, const UInt32 *src)
{
  UInt32 s[4];
  UInt32 m[4];
  UInt32 numRounds2 = w[0];
  w += 4 + numRounds2 * 8;
  s[0] = src[0] ^ w[0];
  s[1] = src[1] ^ w[1];
  s[2] = src[2] ^ w[2];
  s[3] = src[3] ^ w[3];
  for (;;)
  {
    w -= 8;
    HD16(m, s, 4);
    if (--numRounds2 == 0)
      break;
    HD16(s, m, 0);
  }
  FD4(0); FD4(1); FD4(2); FD4(3);
}

void AesCbc_Init(UInt32 *p, const Byte *iv)
{
  unsigned i;
  for (i = 0; i < 4; i++)
    p[i] = GetUi32(iv + i * 4);
}

void MY_FAST_CALL AesCbc_Encode(UInt32 *p, Byte *data, size_t numBlocks)
{
  for (; numBlocks != 0; numBlocks--, data += AES_BLOCK_SIZE)
  {
    p[0] ^= GetUi32(data);
    p[1] ^= GetUi32(data + 4);
    p[2] ^= GetUi32(data + 8);
    p[3] ^= GetUi32(data + 12);
    
    Aes_Encode(p + 4, p, p);
    
    SetUi32(data,      p[0]);
    SetUi32(data + 4,  p[1]);
    SetUi32(data + 8,  p[2]);
    SetUi32(data + 12, p[3]);
  }
}

void MY_FAST_CALL AesCbc_Decode(UInt32 *p, Byte *data, size_t numBlocks)
{
  UInt32 in[4], out[4];
  for (; numBlocks != 0; numBlocks--, data += AES_BLOCK_SIZE)
  {
    in[0] = GetUi32(data);
    in[1] = GetUi32(data + 4);
    in[2] = GetUi32(data + 8);
    in[3] = GetUi32(data + 12);

    Aes_Decode(p + 4, out, in);

    SetUi32(data,      p[0] ^ out[0]);
    SetUi32(data + 4,  p[1] ^ out[1]);
    SetUi32(data + 8,  p[2] ^ out[2]);
    SetUi32(data + 12, p[3] ^ out[3]);
    
    p[0] = in[0];
    p[1] = in[1];
    p[2] = in[2];
    p[3] = in[3];
  }
}

void MY_FAST_CALL AesCtr_Code(UInt32 *p, Byte *data, size_t numBlocks)
{
  for (; numBlocks != 0; numBlocks--)
  {
    UInt32 temp[4];
    unsigned i;

    if (++p[0] == 0)
      p[1]++;
    
    Aes_Encode(p + 4, temp, p);
    
    for (i = 0; i < 4; i++, data += 4)
    {
      UInt32 t = temp[i];

      #ifdef MY_CPU_LE_UNALIGN
        *((UInt32 *)data) ^= t;
      #else
        data[0] ^= (t & 0xFF);
        data[1] ^= ((t >> 8) & 0xFF);
        data[2] ^= ((t >> 16) & 0xFF);
        data[3] ^= ((t >> 24));
      #endif
    }
  }
}
