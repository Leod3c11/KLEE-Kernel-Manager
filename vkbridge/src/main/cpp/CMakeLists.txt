cmake_minimum_required(VERSION 3.22.1)
project(klee_vk_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ADRENOTOOLS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libadrenotools")
add_subdirectory("${ADRENOTOOLS_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/adrenotools_build")

# Ensure all libadrenotools helper shared libs are 16KB page aligned for Android 15/16
foreach(_hook_tgt main_hook file_redirect_hook gsl_alloc_hook hook_impl)
    if(TARGET ${_hook_tgt})
        target_link_options(${_hook_tgt} PRIVATE "-Wl,-z,max-page-size=16384")
    endif()
endforeach()

add_library(klee_vk_bridge SHARED
    bridge.cpp
    hooks.cpp
    plt_hook.cpp
)

target_include_directories(klee_vk_bridge PRIVATE
    "${ADRENOTOOLS_DIR}/include"
    "${ADRENOTOOLS_DIR}/lib/linkernsbypass"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

target_link_options(klee_vk_bridge PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(klee_vk_bridge PRIVATE
    adrenotools
    android
    log
    dl
)
