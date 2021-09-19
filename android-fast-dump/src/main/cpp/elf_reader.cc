#include "elf_reader.h"

#include <7zCrc.h>
#include <Xz.h>
#include <XzCrc64.h>
#include <fcntl.h>
#include <klog.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <unistd.h>

#include <cstring>
#include <string>

#undef LOG_TAG
#define LOG_TAG "LeakCanary-elr"

namespace kwai {
namespace linker {

static const char *kDynstrName = ".dynstr";
static const char *kStrtabName = ".strtab";
static const char *kGnuHash = ".gnu.hash";
static const char *kGnuDebugdata = ".gnu_debugdata";

ElfReader::ElfReader(std::shared_ptr<ElfWrapper> elf_wrapper)
    : elf_wrapper_(),
      shdr_table_(nullptr),
      dynsym_(nullptr),
      dynstr_(nullptr),
      symtab_(nullptr),
      symtab_ent_count_(0),
      strtab_(nullptr),
      gnu_debugdata_(nullptr),
      gnu_debugdata_size_(0),
      has_elf_hash_(false),
      has_gnu_hash_(false) {
  if (!elf_wrapper->IsValid()) {
    return;
  }
  elf_wrapper_ = elf_wrapper;
}

bool ElfReader::IsValidElf() {
  return elf_wrapper_ != nullptr &&
         !memcmp(elf_wrapper_->Start()->e_ident, ELFMAG, SELFMAG);
}

bool ElfReader::Init() {
  if (!IsValidElf() || !IsValidRange(elf_wrapper_->Start()->e_ehsize)) {
    return false;
  }

  shdr_table_ = CheckedOffset<ElfW(Shdr)>(
      elf_wrapper_->Start()->e_shoff,
      (elf_wrapper_->Start()->e_shnum) * (elf_wrapper_->Start()->e_shentsize));
  if (!shdr_table_) {
    return false;
  }

  const char *shstr = CheckedOffset<const char>(
      shdr_table_[elf_wrapper_->Start()->e_shstrndx].sh_offset,
      shdr_table_[elf_wrapper_->Start()->e_shstrndx].sh_size);
  if (!shstr) {
    return false;
  }

  for (int index = 0; index < elf_wrapper_->Start()->e_shnum; ++index) {
    if (shdr_table_[index].sh_size <= 0) {
      continue;
    }
    switch (shdr_table_[index].sh_type) {
      case SHT_DYNSYM:
        dynsym_ = CheckedOffset<ElfW(Sym)>(shdr_table_[index].sh_offset,
                                           shdr_table_[index].sh_size);
        break;
      case SHT_STRTAB: {
        const char *tmp_str = CheckedOffset<const char>(
            shdr_table_[index].sh_offset, shdr_table_[index].sh_size);
        if (!strcmp(shstr + shdr_table_[index].sh_name, kDynstrName)) {
          dynstr_ = tmp_str;
        } else if (!strcmp(shstr + shdr_table_[index].sh_name, kStrtabName)) {
          strtab_ = tmp_str;
        }
        break;
      }
      case SHT_SYMTAB:
        symtab_ = CheckedOffset<ElfW(Sym)>(shdr_table_[index].sh_offset,
                                           shdr_table_[index].sh_size);
        symtab_ent_count_ =
            shdr_table_[index].sh_size / shdr_table_[index].sh_entsize;
        break;
      case SHT_HASH:
        BuildHash(CheckedOffset<ElfW(Word)>(shdr_table_[index].sh_offset,
                                            shdr_table_[index].sh_size));
        break;
      case SHT_PROGBITS:
        if (!strcmp(shstr + shdr_table_[index].sh_name, kGnuDebugdata)) {
          gnu_debugdata_ = CheckedOffset<const char>(
              shdr_table_[index].sh_offset, shdr_table_[index].sh_size);
          gnu_debugdata_size_ = shdr_table_[index].sh_size;
        }
        break;
      default:
        if (!strcmp(shstr + shdr_table_[index].sh_name, kGnuHash)) {
          BuildGnuHash(CheckedOffset<ElfW(Word)>(shdr_table_[index].sh_offset,
                                                 shdr_table_[index].sh_size));
        }
        break;
    }
  }
  return true;
}

void *ElfReader::LookupSymbol(const char *symbol, ElfW(Addr) load_base,
                              bool only_dynsym) {
  if (!symbol) {
    return nullptr;
  }

  // First lookup from dynsym using hash
  ElfW(Addr) sym_vaddr =
      has_gnu_hash_ ? LookupByGnuHash(symbol) : LookupByElfHash(symbol);
  if (sym_vaddr != 0) {
    return reinterpret_cast<void *>(load_base + sym_vaddr);
  }

  if (only_dynsym) {
    return nullptr;
  }

  // Try lookup from symtab
  for (size_t index = 0; index < symtab_ent_count_; index++) {
    // Only care functions and objects
    if (ELF_ST_TYPE(symtab_[index].st_info) != STT_FUNC &&
        ELF_ST_TYPE(symtab_[index].st_info) != STT_OBJECT) {
      continue;
    }

    if (!strcmp(strtab_ + symtab_[index].st_name, symbol)) {
      return reinterpret_cast<void *>(load_base + symtab_[index].st_value);
    }
  }

  // Try lookup from compressed gnu_debugdata
  std::string decompressed_data;
  if (DecGnuDebugdata(decompressed_data)) {
    ElfReader elf_reader(std::make_shared<MemoryElfWrapper>(decompressed_data));
    if (elf_reader.Init()) {
      return elf_reader.LookupSymbol(symbol, load_base);
    }
  }
  return nullptr;
}

template <class T>
T *ElfReader::CheckedOffset(off_t offset, size_t size) {
  if (!IsValidRange(offset + size)) {
    KLOGE("illegal offset %ld, ELF start is %p", offset, elf_wrapper_->Start());
    return nullptr;
  }
  return reinterpret_cast<T *>(
      reinterpret_cast<ElfW(Addr)>(elf_wrapper_->Start()) + offset);
}

bool ElfReader::IsValidRange(off_t offset) {
  return static_cast<size_t>(offset) <= elf_wrapper_->Size();
}

void ElfReader::BuildHash(ElfW(Word) * hash_section) {
  if (!hash_section) {
    return;
  }

  elf_hash_.nbucket = hash_section[0];
  elf_hash_.nchain = hash_section[1];
  elf_hash_.bucket = hash_section + 2;
  elf_hash_.chain = hash_section + 2 + elf_hash_.nbucket;
  has_elf_hash_ = true;
}

void ElfReader::BuildGnuHash(ElfW(Word) * gnu_hash_section) {
  if (!gnu_hash_section) {
    return;
  }

  gnu_hash_.gnu_nbucket = gnu_hash_section[0];
  gnu_hash_.gnu_maskwords = gnu_hash_section[2];
  gnu_hash_.gnu_shift2 = gnu_hash_section[3];
  gnu_hash_.gnu_bloom_filter =
      reinterpret_cast<ElfW(Addr) *>(gnu_hash_section + 4);
  gnu_hash_.gnu_bucket = reinterpret_cast<ElfW(Word) *>(
      gnu_hash_.gnu_bloom_filter + gnu_hash_.gnu_maskwords);
  gnu_hash_.gnu_chain =
      gnu_hash_.gnu_bucket + gnu_hash_.gnu_nbucket - gnu_hash_section[1];
  gnu_hash_.gnu_maskwords--;
  has_gnu_hash_ = true;
}

// Hash Only search dynsym and we ignore symbol version check
ElfW(Addr) ElfReader::LookupByElfHash(const char *symbol) {
  if (!has_elf_hash_ || !dynsym_ || !dynstr_) {
    KLOGW("ELF Hash miss or check dynsym/dynstr");
    return 0;
  }
  uint32_t hash = elf_hash_.Hash(reinterpret_cast<const uint8_t *>(symbol));
  for (uint32_t n = elf_hash_.bucket[hash % elf_hash_.nbucket]; n != 0;
       n = elf_hash_.chain[n]) {
    const ElfW(Sym) *sym = dynsym_ + n;
    if (strcmp(dynstr_ + sym->st_name, symbol) == 0) {
      // TODO add log
      return sym->st_value;
    }
  }
  return 0;
}

// Gnu hash Only search dynsym and we ignore symbol version check
ElfW(Addr) ElfReader::LookupByGnuHash(const char *symbol) {
  if (!has_gnu_hash_ || !dynsym_ || !dynstr_) {
    return 0;
  }

  uint32_t hash = gnu_hash_.Hash(reinterpret_cast<const uint8_t *>(symbol));
  constexpr uint32_t kBloomMaskBits = sizeof(ElfW(Addr)) * 8;
  const uint32_t word_num = (hash / kBloomMaskBits) & gnu_hash_.gnu_maskwords;
  const ElfW(Addr) bloom_word = gnu_hash_.gnu_bloom_filter[word_num];
  const uint32_t h1 = hash % kBloomMaskBits;
  const uint32_t h2 = (hash >> gnu_hash_.gnu_shift2) % kBloomMaskBits;
  // test against bloom filter
  if ((1 & (bloom_word >> h1) & (bloom_word >> h2)) == 0) {
    return 0;
  }
  // bloom test says "probably yes"...
  uint32_t n = gnu_hash_.gnu_bucket[hash % gnu_hash_.gnu_nbucket];

  do {
    const ElfW(Sym) *sym = dynsym_ + n;
    if (((gnu_hash_.gnu_chain[n] ^ hash) >> 1) == 0 &&
        strcmp(dynstr_ + sym->st_name, symbol) == 0) {
      return sym->st_value;
    }
  } while ((gnu_hash_.gnu_chain[n++] & 1) == 0);
  return 0;
}

bool ElfReader::DecGnuDebugdata(std::string &decompressed_data) {
  if (!gnu_debugdata_ || gnu_debugdata_size_ <= 0) {
    KLOGW("%s null or size %d", kGnuDebugdata, gnu_debugdata_size_);
    return false;
  }
  ISzAlloc alloc;
  CXzUnpacker state;
  alloc.Alloc = [](ISzAllocPtr, size_t size) -> void * { return malloc(size); };
  alloc.Free = [](ISzAllocPtr, void *address) -> void { free(address); };
  XzUnpacker_Construct(&state, &alloc);
  CrcGenerateTable();
  Crc64GenerateTable();
  size_t src_offset = 0;
  size_t dst_offset = 0;
  std::string dst(gnu_debugdata_size_, ' ');

  ECoderStatus status = CODER_STATUS_NOT_FINISHED;
  while (status == CODER_STATUS_NOT_FINISHED) {
    dst.resize(dst.size() * 2);
    size_t src_remaining = gnu_debugdata_size_ - src_offset;
    size_t dst_remaining = dst.size() - dst_offset;
    int res = XzUnpacker_Code(
        &state, reinterpret_cast<Byte *>(&dst[dst_offset]), &dst_remaining,
        reinterpret_cast<const Byte *>(gnu_debugdata_ + src_offset),
        &src_remaining, true, CODER_FINISH_ANY, &status);
    if (res != SZ_OK) {
      KLOGE("LZMA decompression failed with error %d", res);
      XzUnpacker_Free(&state);
      return false;
    }
    src_offset += src_remaining;
    dst_offset += dst_remaining;
  }
  XzUnpacker_Free(&state);
  if (!XzUnpacker_IsStreamWasFinished(&state)) {
    KLOGE("LZMA decompresstion failed due to incomplete stream");
    return false;
  }
  dst.resize(dst_offset);
  decompressed_data = std::move(dst);
  return true;
}
}  // namespace linker
}  // namespace kwai