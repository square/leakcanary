#ifndef LEAKCANARY_ELF_WRAPPER_H
#define LEAKCANARY_ELF_WRAPPER_H

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <unistd.h>

#include "klog.h"

#define LOG_TAG "LeakCanary-elw"

namespace kwai {
namespace linker {
class ElfWrapper {
 public:
  ElfWrapper() : start_(nullptr), size_(0) {}
  virtual ~ElfWrapper() {}

  virtual bool IsValid() { return false; }
  ElfW(Ehdr) * Start() { return reinterpret_cast<ElfW(Ehdr) *>(start_); }
  size_t Size() { return size_; }

 protected:
  void *start_;
  size_t size_;
};

/**
 * Read ELF from so file.
 */
class FileElfWrapper : public ElfWrapper {
 public:
  explicit FileElfWrapper(const char *name) : fd_(-1) {
    if (!name) {
      return;
    }
    fd_ = open(name, O_RDONLY);
    if (fd_ < 0) {
      KLOGE("open %s fail, errno %d", name, errno);
      return;
    }

    size_ = lseek(fd_, 0, SEEK_END);
    if (size_ <= 0) {
      KLOGE("lseek fail or size %zu errno %d", size_, errno);
      return;
    }

    start_ = reinterpret_cast<ElfW(Ehdr) *>(
        mmap(0, size_, PROT_READ, MAP_SHARED, fd_, 0));
    if (start_ == MAP_FAILED) {
      KLOGE("mmap size %zu fail, errno %d", size_, errno);
      return;
    }
  }

  ~FileElfWrapper() {
    if (start_ != MAP_FAILED && size_ > 0) {
      munmap(reinterpret_cast<void *>(start_), size_);
    }
    if (fd_ >= 0) {
      close(fd_);
    }
  }

  bool IsValid() { return fd_ >= 0 && start_ != MAP_FAILED && size_ > 0; }

 private:
  int fd_;
};

/**
 * Read ELF from memory data.
 */
class MemoryElfWrapper : public ElfWrapper {
 public:
  explicit MemoryElfWrapper(std::string &elf_data) {
    if (elf_data.empty()) {
      return;
    }
    elf_data_ = std::move(elf_data);
    start_ = (void *)(elf_data_.data());
    size_ = elf_data_.size();
  }

  bool IsValid() { return start_ && size_ > 0; }

 private:
  std::string elf_data_;
};
}  // namespace linker
}  // namespace kwai
#endif  // LEAKCANARY_ELF_WRAPPER_H
