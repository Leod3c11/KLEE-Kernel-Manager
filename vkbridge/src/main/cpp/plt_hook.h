#pragma once

#include <link.h>
#include <elf.h>
#include <cstddef>
#include <cstdint>

namespace kleevk {

bool hook_plt_symbol(
    uintptr_t base_addr,
    const Elf64_Phdr *phdr,
    size_t phnum,
    const char *target_symbol,
    void *new_func,
    void **old_func = nullptr
);

int hook_all_matching_libraries(
    const char *target_symbol,
    void *new_func,
    void **old_func,
    const char *ignore_driver_lib
);

} // namespace kleevk
