#pragma once

#include <cstddef>
#include <cstdint>
#include <link.h>

namespace kleevk {

bool hook_plt_symbol(
    uintptr_t base_addr,
    const Elf64_Phdr *phdr,
    size_t phnum,
    const char *target_symbol,
    void *new_func,
    void **old_func
);

int hook_all_matching_libraries(
    const char *target_symbol,
    void *new_func,
    void **old_func,
    const char *ignore_driver_lib = nullptr
);

int hook_loader_libraries(
    const char *target_symbol,
    void *new_func,
    void **old_func
);

} // namespace kleevk
