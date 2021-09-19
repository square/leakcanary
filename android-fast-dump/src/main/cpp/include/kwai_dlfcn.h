#ifndef KWAI_DLFCN_H
#define KWAI_DLFCN_H

#include <link.h>

#include <string>

namespace kwai {
namespace linker {

class DlFcn {
 public:
  struct SoDlInfo {
    /**
     * The full path name of so
     */
    std::string full_name;
    /**
     * The load base address. For example:
     * phdr0: the PT_LOAD segment
     * phdr0_load_address: the segment map start address.
     * phdr0->p_vaddr: the segment virtual address.
     *
     * load_base = phdr0_load_address - PAGE_START(phdr0->p_vaddr)
     */
    ElfW(Addr) load_base;
  };

  /**
   * Android N+ dlopen bypass
   */
  static void *dlopen(const char *lib_name, int flags);

  /**
   * Android N+ dlsym bypass
   */
  static void *dlsym(void *handle, const char *name);

  /**
   * Android N+ dlclose bypass
   */
  static int dlclose(void *handle);
  /**
   * Inspired by https://github.com/avs333/Nougat_dlfunctions/
   *
   * Parse ELF file based on /proc/<pid>/mappings and store
   * .dynsym、.dynstr、.symtab、.strtab information.
   *
   * It's much less effective than DlFcn::dlopen, do not use this in low
   * memory state or high performance sensitive scenario!
   *
   * It's more powerful than DlFcn::dlopen which can only get symbols in
   * .dynsym(GLOBAL), it can also get symbols in .symtab(LOCAL).
   */
  static void *dlopen_elf(const char *lib_name, int flags);

  /**
   * Since dlopen_elf consumes more memory, when fetching multiple symbols in a
   * so, try to open it only once, get all symbol addresses and cache them and
   * then close it.
   */
  static void *dlsym_elf(void *handle, const char *name);

  /**
   * Release memroy.
   */
  static int dlclose_elf(void *handle);

  struct dl_iterate_data {
    dl_phdr_info info_;
  };

  static int android_api_;

 private:
  static void init_api();
};

}  // namespace linker
}  // namespace kwai

#endif  // KWAI_DLFCN_H