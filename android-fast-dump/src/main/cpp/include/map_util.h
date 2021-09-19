#ifndef LEAKCANARY_MAP_UTIL_H
#define LEAKCANARY_MAP_UTIL_H

#include <inttypes.h>
#include <link.h>
#include <sys/mman.h>

#include <string>
#include <vector>

#include "macros.h"

#undef LOG_TAG
#define LOG_TAG "LeakCanary-mu"

#if __ANDROID_API__ < 21
extern "C" __attribute__((weak)) int dl_iterate_phdr(
    int (*)(struct dl_phdr_info *, size_t, void *), void *);
#endif

namespace kwai {
namespace linker {
class MapUtil {
 public:
  /**
  TECHNICAL NOTE ON ELF LOADING.

  An ELF file's program header table contains one or more PT_LOAD
  segments, which corresponds to portions of the file that need to
  be mapped into the process' address space.

  Each loadable segment has the following important properties:

    p_offset  -> segment file offset
    p_filesz  -> segment file size
    p_memsz   -> segment memory size (always >= p_filesz)
    p_vaddr   -> segment's virtual address
    p_flags   -> segment flags (e.g. readable, writable, executable)
    p_align   -> segment's in-memory and in-file alignment

  We will ignore the p_paddr field of ElfW(Phdr) for now.

  The loadable segments can be seen as a list of [p_vaddr ... p_vaddr+p_memsz)
  ranges of virtual addresses. A few rules apply:

  - the virtual address ranges should not overlap.

  - if a segment's p_filesz is smaller than its p_memsz, the extra bytes
    between them should always be initialized to 0.

  - ranges do not necessarily start or end at page boundaries. Two distinct
    segments can have their start and end on the same page. In this case, the
    page inherits the mapping flags of the latter segment.

  Finally, the real load addrs of each segment is not p_vaddr. Instead the
  loader decides where to load the first segment, then will load all others
  relative to the first one to respect the initial range layout.

  For example, consider the following list:

    [ offset:0,      filesz:0x4000, memsz:0x4000, vaddr:0x30000 ],
    [ offset:0x4000, filesz:0x2000, memsz:0x8000, vaddr:0x40000 ],

  This corresponds to two segments that cover these virtual address ranges:

       0x30000...0x34000
       0x40000...0x48000

  If the loader decides to load the first segment at address 0xa0000000
  then the segments' load address ranges will be:

       0xa0030000...0xa0034000
       0xa0040000...0xa0048000

  In other words, all segments must be loaded at an address that has the same
  constant offset from their p_vaddr value. This offset is computed as the
  difference between the first segment's load address, and its p_vaddr value.

  However, in practice, segments do _not_ start at page boundaries. Since we
  can only memory-map at page boundaries, this means that the bias is
  computed as:

       load_bias = phdr0_load_address - PAGE_START(phdr0->p_vaddr)

  (NOTE: The value must be used as a 32-bit unsigned integer, to deal with
          possible wrap around UINT32_MAX for possible large p_vaddr values).

  And that the phdr0_load_address must start at a page boundary, with
  the segment's real content starting at:

       phdr0_load_address + PAGE_OFFSET(phdr0->p_vaddr)

  Note that ELF requires the following condition to make the mmap()-ing work:

      PAGE_OFFSET(phdr0->p_vaddr) == PAGE_OFFSET(phdr0->p_offset)

  The load_bias must be added to any p_vaddr value read from the ELF file to
  determine the corresponding memory address.

  **/
  /**
   * Get the base address(load_bias) of a loaded so, what is the load_bias?
   * See above ELF LOADING detail.
   *
   * Note: You should using full path name because some libraries have same
   * name.
   */
  static bool GetLoadInfo(const std::string &name, ElfW(Addr) * load_base,
                          std::string &so_full_name, int android_api) {
    // Actually Android 5.x, we can using "dl_iterate_phdr",
    // but we need lock "g_dl_mutex" by self, so we just using maps in
    // Android 5.x.
    auto get_load_info = android_api > __ANDROID_API_L_MR1__
                             ? GetLoadInfoByDl
                             : GetLoadInfoByMaps;
    return get_load_info(name, load_base, so_full_name);
  }

 private:
  struct MapEntry {
    std::string name;
    uintptr_t start;
    uintptr_t end;
    uintptr_t offset;
    int flags;
  };
  using MapEntry = struct MapEntry;

  template <typename T>
  static inline bool GetVal(MapEntry &entry, uintptr_t addr, T *store) {
    if (!(entry.flags & PROT_READ) || addr < entry.start ||
        addr + sizeof(T) > entry.end) {
      return false;
    }
    // Make sure the address is aligned properly.
    if (addr & (sizeof(T) - 1)) {
      return false;
    }
    *store = *reinterpret_cast<T *>(addr);
    return true;
  }

  static bool ReadLoadBias(MapEntry &entry, ElfW(Addr) * load_bias) {
    uintptr_t addr = entry.start;
    ElfW(Ehdr) ehdr;
    if (!GetVal<ElfW(Half)>(entry, addr + offsetof(ElfW(Ehdr), e_phnum),
                            &ehdr.e_phnum)) {
      return false;
    }
    if (!GetVal<ElfW(Off)>(entry, addr + offsetof(ElfW(Ehdr), e_phoff),
                           &ehdr.e_phoff)) {
      return false;
    }
    addr += ehdr.e_phoff;
    for (size_t i = 0; i < ehdr.e_phnum; i++) {
      ElfW(Phdr) phdr;
      if (!GetVal<ElfW(Word)>(entry, addr + offsetof(ElfW(Phdr), p_type),
                              &phdr.p_type)) {
        return false;
      }
      if (!GetVal<ElfW(Word)>(entry, addr + offsetof(ElfW(Phdr), p_flags),
                              &phdr.p_flags)) {
        return false;
      }
      if (!GetVal<ElfW(Off)>(entry, addr + offsetof(ElfW(Phdr), p_offset),
                             &phdr.p_offset)) {
        return false;
      }

      if ((phdr.p_type == PT_LOAD) && (phdr.p_flags & PF_X)) {
        if (!GetVal<ElfW(Addr)>(entry, addr + offsetof(ElfW(Phdr), p_vaddr),
                                &phdr.p_vaddr)) {
          return false;
        }
        *load_bias = phdr.p_vaddr;
        return true;
      }
      addr += sizeof(phdr);
    }
    return false;
  }

  static bool EndsWith(const char *target, const char *suffix) {
    if (!target || !suffix) {
      return false;
    }
    const char *sub_str = strstr(target, suffix);
    return sub_str && strlen(sub_str) == strlen(suffix);
  }

  static bool GetLoadInfoByMaps(const std::string &name, ElfW(Addr) * load_base,
                                std::string &full_name) {
    FILE *fp = fopen("/proc/self/maps", "re");
    auto ret = false;
    if (fp == nullptr) {
      return ret;
    }

    auto parse_line = [](char *map_line, MapEntry &curr_entry,
                         int &name_pos) -> bool {
      char permissions[5];
      if (sscanf(map_line,
                 "%" PRIxPTR "-%" PRIxPTR " %4s %" PRIxPTR " %*x:%*x %*d %n",
                 &curr_entry.start, &curr_entry.end, permissions,
                 &curr_entry.offset, &name_pos) < 4) {
        return false;
      }
      curr_entry.flags = 0;
      if (permissions[0] == 'r') {
        curr_entry.flags |= PROT_READ;
      }
      if (permissions[2] == 'x') {
        curr_entry.flags |= PROT_EXEC;
      }
      return true;
    };
    std::vector<char> buffer(1024);
    MapEntry prev_entry = {};
    while (fgets(buffer.data(), buffer.size(), fp) != nullptr) {
      MapEntry curr_entry = {};
      int name_pos;

      if (!parse_line(buffer.data(), curr_entry, name_pos)) {
        continue;
      }

      const char *map_name = buffer.data() + name_pos;
      size_t name_len = strlen(map_name);
      if (name_len && map_name[name_len - 1] == '\n') {
        name_len -= 1;
      }

      curr_entry.name = std::string(map_name, name_len);
      if (curr_entry.flags == PROT_NONE) {
        continue;
      }

      // If an (readable-)executable map offset NOT equal 0, need check previous
      // readable map
      if ((curr_entry.flags & PROT_EXEC) == PROT_EXEC &&
          EndsWith(curr_entry.name.c_str(), name.c_str())) {
        ElfW(Addr) load_bias;
        if (curr_entry.offset == 0) {
          ret = ReadLoadBias(curr_entry, &load_bias);
        } else {
          if (EndsWith(prev_entry.name.c_str(), name.c_str()) &&
              prev_entry.offset == 0 && prev_entry.flags == PROT_READ) {
            ret = ReadLoadBias(prev_entry, &load_bias);
          }
        }

        if (ret) {
          *load_base = curr_entry.start - load_bias;
          full_name = curr_entry.name;
          break;
        }
      }
      prev_entry = curr_entry;
    }
    fclose(fp);
    return ret;
  }

  static bool GetLoadInfoByDl(const std::string &name, ElfW(Addr) * load_base,
                              std::string &so_full_name) {
    struct PhdrInfo {
      const char *name;
      std::string full_name;
      ElfW(Addr) load_base;
      off_t load_bias;
    };
    PhdrInfo phdr_info = {
        .name = name.c_str(), .full_name = "", .load_base = 0};
    auto iterate_phdr_callback = [](struct dl_phdr_info *phdr_info,
                                    size_t size ATTRIBUTE_UNUSED,
                                    void *data) -> int {
      PhdrInfo *info = reinterpret_cast<PhdrInfo *>(data);
      if (!phdr_info->dlpi_name) {
        KLOGW("dlpi_name nullptr");
        return 0;
      }
      const char *sub_str = strstr(phdr_info->dlpi_name, info->name);
      if (sub_str && strlen(sub_str) == strlen(info->name)) {
        info->load_base = phdr_info->dlpi_addr;
        info->full_name = phdr_info->dlpi_name;
        return 1;
      }
      return 0;
    };

    dl_iterate_phdr(iterate_phdr_callback,
                    reinterpret_cast<void *>(&phdr_info));
    if (!phdr_info.load_base) {
      return false;
    }

    *load_base = phdr_info.load_base;
    so_full_name = phdr_info.full_name;
    return true;
  }
};
}  // namespace linker
}  // namespace kwai

#endif  // LEAKCANARY_MAP_UTIL_H
