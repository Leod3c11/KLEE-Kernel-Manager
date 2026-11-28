#include "hooks.h"
#include "plt_hook.h"

#include <android/dlext.h>
#include <android/log.h>
#include <bytehook.h>
#include <shadowhook.h>
#include <cstring>
#include <dlfcn.h>
#include <string>

#define TAG "KLEE-Hooks"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

void *g_vk = nullptr;
VkInstance g_instance = VK_NULL_HANDLE;
PFN_vkGetInstanceProcAddr g_pfn_vkGetInstanceProcAddr = nullptr;
PFN_vkCreateInstance g_pfn_vkCreateInstance = nullptr;
static PFN_vkEnumerateInstanceVersion g_pfn_vkEnumerateInstanceVersion = nullptr;
static PFN_vkEnumerateInstanceExtensionProperties g_pfn_vkEnumerateInstanceExtensionProperties = nullptr;
static PFN_vkEnumerateInstanceLayerProperties g_pfn_vkEnumerateInstanceLayerProperties = nullptr;

static std::string s_pkgDataDir;
static std::string s_pkgCodeDir;
static std::string s_customDriverName;
static bool s_bytehook_active = false;

typedef void* (*loader_dlopen_fn)(const char *filename, int flags, const void *caller_addr);
typedef void* (*loader_dlsym_fn)(void *handle, const char *symbol, const void *caller_addr);
typedef void* (*loader_android_dlopen_ext_fn)(const char *filename, int flag, const void *extinfo, const void *caller_addr);

static loader_dlopen_fn s_loader_dlopen = nullptr;
static loader_dlsym_fn s_loader_dlsym = nullptr;
static loader_android_dlopen_ext_fn s_loader_android_dlopen_ext = nullptr;

static bytehook_stub_t s_stub_dlopen = nullptr;
static bytehook_stub_t s_stub_dlsym = nullptr;
static bytehook_stub_t s_stub_android_dlopen_ext = nullptr;
static bytehook_stub_t s_stub_dlclose = nullptr;
static bytehook_stub_t s_stub_vkGetInstanceProcAddr = nullptr;
static bytehook_stub_t s_stub_vkCreateInstance = nullptr;
static bytehook_stub_t s_stub_vkEnumerateInstanceVersion = nullptr;
static bytehook_stub_t s_stub_vkEnumerateInstanceExtensionProperties = nullptr;
static bytehook_stub_t s_stub_vkEnumerateInstanceLayerProperties = nullptr;

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL klee_vkGetInstanceProcAddr(VkInstance instance, const char *pName);
static VKAPI_ATTR VkResult VKAPI_CALL klee_vkCreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkInstance *pInstance
);
static VKAPI_ATTR VkResult VKAPI_CALL klee_vkEnumerateInstanceVersion(uint32_t *pApiVersion);
static VKAPI_ATTR VkResult VKAPI_CALL klee_vkEnumerateInstanceExtensionProperties(
    const char *pLayerName,
    uint32_t *pPropertyCount,
    VkExtensionProperties *pProperties
);
static VKAPI_ATTR VkResult VKAPI_CALL klee_vkEnumerateInstanceLayerProperties(
    uint32_t *pPropertyCount,
    VkLayerProperties *pProperties
);

static bool is_vulkan_loader_name(const char *filename) {
    if (!filename) return false;
    return strstr(filename, "libvulkan.so") != nullptr;
}

static void init_bionic_loader_ptrs() {
    void *libdl = dlopen("libdl.so", RTLD_NOW);
    void *libc = dlopen("libc.so", RTLD_NOW);
    if (libdl) {
        s_loader_dlopen = reinterpret_cast<loader_dlopen_fn>(dlsym(libdl, "__loader_dlopen"));
        s_loader_dlsym = reinterpret_cast<loader_dlsym_fn>(dlsym(libdl, "__loader_dlsym"));
        s_loader_android_dlopen_ext = reinterpret_cast<loader_android_dlopen_ext_fn>(
            dlsym(libdl, "__loader_android_dlopen_ext")
        );
    }
    if (!s_loader_dlopen && libc) {
        s_loader_dlopen = reinterpret_cast<loader_dlopen_fn>(dlsym(libc, "__loader_dlopen"));
        s_loader_dlsym = reinterpret_cast<loader_dlsym_fn>(dlsym(libc, "__loader_dlsym"));
        s_loader_android_dlopen_ext = reinterpret_cast<loader_android_dlopen_ext_fn>(
            dlsym(libc, "__loader_android_dlopen_ext")
        );
    }
}

static void* resolve_vulkan_symbol(const char *symbol) {
    if (!symbol) return nullptr;
    if (strcmp(symbol, "vkGetInstanceProcAddr") == 0) {
        return reinterpret_cast<void*>(klee_vkGetInstanceProcAddr);
    }
    if (strcmp(symbol, "vkCreateInstance") == 0) {
        return reinterpret_cast<void*>(klee_vkCreateInstance);
    }
    if (strcmp(symbol, "vkEnumerateInstanceVersion") == 0) {
        return reinterpret_cast<void*>(klee_vkEnumerateInstanceVersion);
    }
    if (strcmp(symbol, "vkEnumerateInstanceExtensionProperties") == 0) {
        return reinterpret_cast<void*>(klee_vkEnumerateInstanceExtensionProperties);
    }
    if (strcmp(symbol, "vkEnumerateInstanceLayerProperties") == 0) {
        return reinterpret_cast<void*>(klee_vkEnumerateInstanceLayerProperties);
    }
    if (strncmp(symbol, "vk", 2) == 0 && g_pfn_vkGetInstanceProcAddr) {
        PFN_vkVoidFunction pfn = nullptr;
        if (g_instance != VK_NULL_HANDLE) {
            pfn = g_pfn_vkGetInstanceProcAddr(g_instance, symbol);
        }
        if (!pfn) {
            pfn = g_pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, symbol);
        }
        if (pfn) return reinterpret_cast<void*>(pfn);
    }
    return nullptr;
}

// ---------- Common Vulkan Dispatchers ----------

static VKAPI_ATTR VkResult VKAPI_CALL klee_vkEnumerateInstanceVersion(uint32_t *pApiVersion) {
    if (g_pfn_vkEnumerateInstanceVersion) {
        return g_pfn_vkEnumerateInstanceVersion(pApiVersion);
    }
    if (pApiVersion) {
        *pApiVersion = VK_API_VERSION_1_0;
    }
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL klee_vkEnumerateInstanceExtensionProperties(
    const char *pLayerName,
    uint32_t *pPropertyCount,
    VkExtensionProperties *pProperties
) {
    if (g_pfn_vkEnumerateInstanceExtensionProperties) {
        return g_pfn_vkEnumerateInstanceExtensionProperties(pLayerName, pPropertyCount, pProperties);
    }
    if (pPropertyCount) *pPropertyCount = 0;
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL klee_vkEnumerateInstanceLayerProperties(
    uint32_t *pPropertyCount,
    VkLayerProperties *pProperties
) {
    if (g_pfn_vkEnumerateInstanceLayerProperties) {
        return g_pfn_vkEnumerateInstanceLayerProperties(pPropertyCount, pProperties);
    }
    if (pPropertyCount) *pPropertyCount = 0;
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL klee_vkCreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkInstance *pInstance
) {
    LOGI("klee_vkCreateInstance called");
    if (!g_pfn_vkCreateInstance) {
        LOGE("g_pfn_vkCreateInstance is null!");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkResult res = g_pfn_vkCreateInstance(pCreateInfo, pAllocator, pInstance);
    if (res == VK_SUCCESS && pInstance && *pInstance != VK_NULL_HANDLE) {
        g_instance = *pInstance;
        LOGI("klee_vkCreateInstance succeeded! g_instance=%p", g_instance);
    } else {
        LOGE("klee_vkCreateInstance failed: %d", res);
    }
    return res;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL klee_vkGetInstanceProcAddr(VkInstance instance, const char *pName) {
    if (!pName) return nullptr;
    if (strcmp(pName, "vkGetInstanceProcAddr") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(klee_vkGetInstanceProcAddr);
    }
    if (strcmp(pName, "vkCreateInstance") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(klee_vkCreateInstance);
    }
    if (strcmp(pName, "vkEnumerateInstanceVersion") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(klee_vkEnumerateInstanceVersion);
    }
    if (strcmp(pName, "vkEnumerateInstanceExtensionProperties") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(klee_vkEnumerateInstanceExtensionProperties);
    }
    if (strcmp(pName, "vkEnumerateInstanceLayerProperties") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(klee_vkEnumerateInstanceLayerProperties);
    }
    if (g_pfn_vkGetInstanceProcAddr) {
        VkInstance inst = (instance != VK_NULL_HANDLE) ? instance : g_instance;
        PFN_vkVoidFunction pfn = g_pfn_vkGetInstanceProcAddr(inst, pName);
        if (!pfn && inst != VK_NULL_HANDLE) {
            pfn = g_pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, pName);
        }
        if (pfn) return pfn;
    }
    return nullptr;
}

// ---------- ByteHook PLT Proxies (safe BYTEHOOK_CALL_PREV) ----------

static void* bh_dlopen(const char *filename, int flags) {
    BYTEHOOK_STACK_SCOPE();
    if (is_vulkan_loader_name(filename)) {
        LOGI("bh_dlopen: intercepted %s -> custom handle %p", filename, g_vk);
        return g_vk;
    }
    return BYTEHOOK_CALL_PREV(bh_dlopen, filename, flags);
}

static void* bh_dlsym(void *handle, const char *symbol) {
    BYTEHOOK_STACK_SCOPE();
    if (!symbol) return nullptr;
    if (handle == g_vk || handle == RTLD_DEFAULT) {
        void *sym = resolve_vulkan_symbol(symbol);
        if (sym) return sym;
        if (handle == g_vk) {
            return s_loader_dlsym
                ? s_loader_dlsym(g_vk, symbol, __builtin_return_address(0))
                : BYTEHOOK_CALL_PREV(bh_dlsym, g_vk, symbol);
        }
    }
    return BYTEHOOK_CALL_PREV(bh_dlsym, handle, symbol);
}

static void* bh_android_dlopen_ext(const char *filename, int flag, const void *extinfo) {
    BYTEHOOK_STACK_SCOPE();
    if (is_vulkan_loader_name(filename)) {
        LOGI("bh_android_dlopen_ext: intercepted %s -> custom handle %p", filename, g_vk);
        return g_vk;
    }
    return BYTEHOOK_CALL_PREV(bh_android_dlopen_ext, filename, flag, extinfo);
}

static int bh_dlclose(void *handle) {
    BYTEHOOK_STACK_SCOPE();
    if (handle == g_vk) {
        return 0;
    }
    return BYTEHOOK_CALL_PREV(bh_dlclose, handle);
}

static VKAPI_ATTR VkResult VKAPI_CALL bh_vkCreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkInstance *pInstance
) {
    BYTEHOOK_STACK_SCOPE();
    return klee_vkCreateInstance(pCreateInfo, pAllocator, pInstance);
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL bh_vkGetInstanceProcAddr(VkInstance instance, const char *pName) {
    BYTEHOOK_STACK_SCOPE();
    PFN_vkVoidFunction pfn = klee_vkGetInstanceProcAddr(instance, pName);
    if (pfn) return pfn;
    return BYTEHOOK_CALL_PREV(bh_vkGetInstanceProcAddr, instance, pName);
}

static VKAPI_ATTR VkResult VKAPI_CALL bh_vkEnumerateInstanceVersion(uint32_t *pApiVersion) {
    BYTEHOOK_STACK_SCOPE();
    return klee_vkEnumerateInstanceVersion(pApiVersion);
}

static VKAPI_ATTR VkResult VKAPI_CALL bh_vkEnumerateInstanceExtensionProperties(
    const char *pLayerName,
    uint32_t *pPropertyCount,
    VkExtensionProperties *pProperties
) {
    BYTEHOOK_STACK_SCOPE();
    return klee_vkEnumerateInstanceExtensionProperties(pLayerName, pPropertyCount, pProperties);
}

static VKAPI_ATTR VkResult VKAPI_CALL bh_vkEnumerateInstanceLayerProperties(
    uint32_t *pPropertyCount,
    VkLayerProperties *pProperties
) {
    BYTEHOOK_STACK_SCOPE();
    return klee_vkEnumerateInstanceLayerProperties(pPropertyCount, pProperties);
}

// ---------- Fallback Direct PLT/GOT Proxies (used only if ByteHook unavailable) ----------

static void* plt_dlopen(const char *filename, int flags) {
    if (is_vulkan_loader_name(filename)) {
        LOGI("plt_dlopen: intercepted %s -> custom handle %p", filename, g_vk);
        return g_vk;
    }
    void *res = s_loader_dlopen
        ? s_loader_dlopen(filename, flags, __builtin_return_address(0))
        : dlopen(filename, flags);
    if (res != nullptr && filename != nullptr) {
        static thread_local bool s_in_refresh = false;
        if (!s_in_refresh) {
            s_in_refresh = true;
            refresh_hooks();
            s_in_refresh = false;
        }
    }
    return res;
}

static void* plt_dlsym(void *handle, const char *symbol) {
    if (!symbol) return nullptr;
    if (handle == g_vk || handle == RTLD_DEFAULT) {
        void *sym = resolve_vulkan_symbol(symbol);
        if (sym) return sym;
    }
    return s_loader_dlsym
        ? s_loader_dlsym(handle, symbol, __builtin_return_address(0))
        : dlsym(handle, symbol);
}

static void* plt_android_dlopen_ext(const char *filename, int flag, const void *extinfo) {
    if (is_vulkan_loader_name(filename)) {
        LOGI("plt_android_dlopen_ext: intercepted %s -> custom handle %p", filename, g_vk);
        return g_vk;
    }
    void *res = s_loader_android_dlopen_ext
        ? s_loader_android_dlopen_ext(filename, flag, extinfo, __builtin_return_address(0))
        : android_dlopen_ext(filename, flag, static_cast<const android_dlextinfo*>(extinfo));
    if (res != nullptr && filename != nullptr) {
        static thread_local bool s_in_refresh_ext = false;
        if (!s_in_refresh_ext) {
            s_in_refresh_ext = true;
            refresh_hooks();
            s_in_refresh_ext = false;
        }
    }
    return res;
}

// ---------- ShadowHook Inline Proxies for system libvulkan.so ----------

static VKAPI_ATTR VkResult VKAPI_CALL sh_vkCreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkInstance *pInstance
) {
    SHADOWHOOK_STACK_SCOPE();
    if (g_pfn_vkCreateInstance) {
        return klee_vkCreateInstance(pCreateInfo, pAllocator, pInstance);
    }
    return SHADOWHOOK_CALL_PREV(sh_vkCreateInstance, pCreateInfo, pAllocator, pInstance);
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL sh_vkGetInstanceProcAddr(VkInstance instance, const char *pName) {
    SHADOWHOOK_STACK_SCOPE();
    PFN_vkVoidFunction pfn = klee_vkGetInstanceProcAddr(instance, pName);
    if (pfn) return pfn;
    return SHADOWHOOK_CALL_PREV(sh_vkGetInstanceProcAddr, instance, pName);
}

static VKAPI_ATTR VkResult VKAPI_CALL sh_vkEnumerateInstanceVersion(uint32_t *pApiVersion) {
    SHADOWHOOK_STACK_SCOPE();
    return klee_vkEnumerateInstanceVersion(pApiVersion);
}

static VKAPI_ATTR VkResult VKAPI_CALL sh_vkEnumerateInstanceExtensionProperties(
    const char *pLayerName,
    uint32_t *pPropertyCount,
    VkExtensionProperties *pProperties
) {
    SHADOWHOOK_STACK_SCOPE();
    return klee_vkEnumerateInstanceExtensionProperties(pLayerName, pPropertyCount, pProperties);
}

// ---------- Public Lifecycle ----------

bool init_hooks(
    void *vkHandle,
    const char *pkgDataDir,
    const char *pkgCodeDir,
    const char *customDriverName
) {
    g_vk = vkHandle;
    s_pkgDataDir = pkgDataDir ? pkgDataDir : "";
    s_pkgCodeDir = pkgCodeDir ? pkgCodeDir : "";
    s_customDriverName = customDriverName ? customDriverName : "";

    init_bionic_loader_ptrs();

    g_pfn_vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(g_vk, "vkGetInstanceProcAddr")
    );
    if (!g_pfn_vkGetInstanceProcAddr) {
        LOGE("vkGetInstanceProcAddr missing in custom Vulkan handle");
        return false;
    }

    g_pfn_vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(
        g_pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance")
    );
    if (!g_pfn_vkCreateInstance) {
        g_pfn_vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(dlsym(g_vk, "vkCreateInstance"));
    }

    g_pfn_vkEnumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        g_pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion")
    );
    if (!g_pfn_vkEnumerateInstanceVersion) {
        g_pfn_vkEnumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            dlsym(g_vk, "vkEnumerateInstanceVersion")
        );
    }

    g_pfn_vkEnumerateInstanceExtensionProperties = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
        g_pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties")
    );
    if (!g_pfn_vkEnumerateInstanceExtensionProperties) {
        g_pfn_vkEnumerateInstanceExtensionProperties = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
            dlsym(g_vk, "vkEnumerateInstanceExtensionProperties")
        );
    }

    g_pfn_vkEnumerateInstanceLayerProperties = reinterpret_cast<PFN_vkEnumerateInstanceLayerProperties>(
        g_pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceLayerProperties")
    );
    if (!g_pfn_vkEnumerateInstanceLayerProperties) {
        g_pfn_vkEnumerateInstanceLayerProperties = reinterpret_cast<PFN_vkEnumerateInstanceLayerProperties>(
            dlsym(g_vk, "vkEnumerateInstanceLayerProperties")
        );
    }

    // 1. ByteHook PLT hooks across all current & future loaded libraries
    bytehook_add_ignore("libklee_vk_bridge.so");
    bytehook_add_ignore("libhook_impl.so");
    bytehook_add_ignore("libmain_hook.so");
    bytehook_add_ignore("libfile_redirect_hook.so");
    bytehook_add_ignore("libgsl_alloc_hook.so");
    bytehook_add_ignore("libshadowhook.so");
    bytehook_add_ignore("libbytehook.so");
    bytehook_add_ignore("libvulkan_panfrost.so");
    bytehook_add_ignore("libvulkan_freedreno.so");
    if (!s_customDriverName.empty()) {
        bytehook_add_ignore(s_customDriverName.c_str());
    }

    int bh_res = bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);
    if (bh_res == 0) {
        s_bytehook_active = true;
        s_stub_dlopen = bytehook_hook_all(nullptr, "dlopen", reinterpret_cast<void*>(bh_dlopen), nullptr, nullptr);
        s_stub_dlsym = bytehook_hook_all(nullptr, "dlsym", reinterpret_cast<void*>(bh_dlsym), nullptr, nullptr);
        s_stub_android_dlopen_ext = bytehook_hook_all(nullptr, "android_dlopen_ext", reinterpret_cast<void*>(bh_android_dlopen_ext), nullptr, nullptr);
        s_stub_dlclose = bytehook_hook_all(nullptr, "dlclose", reinterpret_cast<void*>(bh_dlclose), nullptr, nullptr);
        s_stub_vkGetInstanceProcAddr = bytehook_hook_all(nullptr, "vkGetInstanceProcAddr", reinterpret_cast<void*>(bh_vkGetInstanceProcAddr), nullptr, nullptr);
        s_stub_vkCreateInstance = bytehook_hook_all(nullptr, "vkCreateInstance", reinterpret_cast<void*>(bh_vkCreateInstance), nullptr, nullptr);
        s_stub_vkEnumerateInstanceVersion = bytehook_hook_all(nullptr, "vkEnumerateInstanceVersion", reinterpret_cast<void*>(bh_vkEnumerateInstanceVersion), nullptr, nullptr);
        s_stub_vkEnumerateInstanceExtensionProperties = bytehook_hook_all(nullptr, "vkEnumerateInstanceExtensionProperties", reinterpret_cast<void*>(bh_vkEnumerateInstanceExtensionProperties), nullptr, nullptr);
        s_stub_vkEnumerateInstanceLayerProperties = bytehook_hook_all(nullptr, "vkEnumerateInstanceLayerProperties", reinterpret_cast<void*>(bh_vkEnumerateInstanceLayerProperties), nullptr, nullptr);
        LOGI("ByteHook PLT hooks installed (mode=AUTOMATIC)");
    } else {
        LOGW("bytehook_init returned %d — falling back to direct ELF64 PLT/GOT hooks", bh_res);
        s_bytehook_active = false;
        refresh_hooks();
    }

    // 2. ShadowHook inline hooks on system /system/lib64/libvulkan.so (ONLY if distinct from g_vk!)
    void *sysVk = dlopen("/system/lib64/libvulkan.so", RTLD_NOLOAD);
    if (!sysVk) {
        sysVk = s_loader_dlopen
            ? s_loader_dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL, __builtin_return_address(0))
            : nullptr;
    }
    void *sysGipa = sysVk ? dlsym(sysVk, "vkGetInstanceProcAddr") : nullptr;
    if (sysVk && sysGipa && sysGipa != reinterpret_cast<void*>(g_pfn_vkGetInstanceProcAddr)) {
        int sh_res = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
        LOGI("shadowhook_init=%d (sysVk=%p != g_vk=%p)", sh_res, sysVk, g_vk);
        if (sh_res == 0) {
            void *sysCreate = dlsym(sysVk, "vkCreateInstance");
            void *sysVer = dlsym(sysVk, "vkEnumerateInstanceVersion");
            void *sysExt = dlsym(sysVk, "vkEnumerateInstanceExtensionProperties");
            if (sysCreate && sysCreate != reinterpret_cast<void*>(g_pfn_vkCreateInstance)) {
                shadowhook_hook_func_addr(sysCreate, reinterpret_cast<void*>(sh_vkCreateInstance), nullptr);
            }
            shadowhook_hook_func_addr(sysGipa, reinterpret_cast<void*>(sh_vkGetInstanceProcAddr), nullptr);
            if (sysVer && sysVer != reinterpret_cast<void*>(g_pfn_vkEnumerateInstanceVersion)) {
                shadowhook_hook_func_addr(sysVer, reinterpret_cast<void*>(sh_vkEnumerateInstanceVersion), nullptr);
            }
            if (sysExt && sysExt != reinterpret_cast<void*>(g_pfn_vkEnumerateInstanceExtensionProperties)) {
                shadowhook_hook_func_addr(sysExt, reinterpret_cast<void*>(sh_vkEnumerateInstanceExtensionProperties), nullptr);
            }
            LOGI("ShadowHook inline hooks installed on system libvulkan.so");
        }
    } else {
        LOGI("Skipping ShadowHook inline hook on libvulkan.so (shared mapping or unavailable)");
    }

    return true;
}

void refresh_hooks() {
    if (!g_vk || s_bytehook_active) return;
    const char *ign = s_customDriverName.empty() ? nullptr : s_customDriverName.c_str();
    int n_dlopen = kleevk::hook_all_matching_libraries("dlopen", reinterpret_cast<void*>(plt_dlopen), nullptr, ign);
    int n_dlsym = kleevk::hook_all_matching_libraries("dlsym", reinterpret_cast<void*>(plt_dlsym), nullptr, ign);
    int n_ext = kleevk::hook_all_matching_libraries("android_dlopen_ext", reinterpret_cast<void*>(plt_android_dlopen_ext), nullptr, ign);
    int n_create = kleevk::hook_all_matching_libraries("vkCreateInstance", reinterpret_cast<void*>(klee_vkCreateInstance), nullptr, ign);
    int n_gipa = kleevk::hook_all_matching_libraries("vkGetInstanceProcAddr", reinterpret_cast<void*>(klee_vkGetInstanceProcAddr), nullptr, ign);
    int n_ver = kleevk::hook_all_matching_libraries("vkEnumerateInstanceVersion", reinterpret_cast<void*>(klee_vkEnumerateInstanceVersion), nullptr, ign);
    int n_iext = kleevk::hook_all_matching_libraries("vkEnumerateInstanceExtensionProperties", reinterpret_cast<void*>(klee_vkEnumerateInstanceExtensionProperties), nullptr, ign);
    if (n_dlopen + n_dlsym + n_ext + n_create + n_gipa + n_ver + n_iext > 0) {
        LOGI("refresh_hooks (fallback PLT): dlopen=%d dlsym=%d dlopen_ext=%d vkCreate=%d vkGipa=%d vkVer=%d vkExt=%d",
             n_dlopen, n_dlsym, n_ext, n_create, n_gipa, n_ver, n_iext);
    }
}

void shutdown_hooks() {
    if (s_stub_dlopen) { bytehook_unhook(s_stub_dlopen); s_stub_dlopen = nullptr; }
    if (s_stub_dlsym) { bytehook_unhook(s_stub_dlsym); s_stub_dlsym = nullptr; }
    if (s_stub_android_dlopen_ext) { bytehook_unhook(s_stub_android_dlopen_ext); s_stub_android_dlopen_ext = nullptr; }
    if (s_stub_dlclose) { bytehook_unhook(s_stub_dlclose); s_stub_dlclose = nullptr; }
    if (s_stub_vkGetInstanceProcAddr) { bytehook_unhook(s_stub_vkGetInstanceProcAddr); s_stub_vkGetInstanceProcAddr = nullptr; }
    if (s_stub_vkCreateInstance) { bytehook_unhook(s_stub_vkCreateInstance); s_stub_vkCreateInstance = nullptr; }
    if (s_stub_vkEnumerateInstanceVersion) { bytehook_unhook(s_stub_vkEnumerateInstanceVersion); s_stub_vkEnumerateInstanceVersion = nullptr; }
    if (s_stub_vkEnumerateInstanceExtensionProperties) { bytehook_unhook(s_stub_vkEnumerateInstanceExtensionProperties); s_stub_vkEnumerateInstanceExtensionProperties = nullptr; }
    if (s_stub_vkEnumerateInstanceLayerProperties) { bytehook_unhook(s_stub_vkEnumerateInstanceLayerProperties); s_stub_vkEnumerateInstanceLayerProperties = nullptr; }
}
