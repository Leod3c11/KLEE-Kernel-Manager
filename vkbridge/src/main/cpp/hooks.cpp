#include "hooks.h"
#include "plt_hook.h"
#include "trace.h"

#include <android/dlext.h>
#include <android/log.h>
#include <bytehook.h>
#include <cstring>
#include <dlfcn.h>
#include <string>
#include <vector>

#define TAG "KLEE-Hooks"
#define LOGI(...) kleetrace::logf(TAG, ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) kleetrace::logf(TAG, ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) kleetrace::logf(TAG, ANDROID_LOG_ERROR, __VA_ARGS__)

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
static std::vector<bytehook_stub_t> s_stubs;

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

static bool is_ignored_lib(const char *path) {
    if (!path || !path[0]) return true;
    // Never touch Android system/vendor/APEX/HWUI/EGL libraries
    if (strncmp(path, "/system/", 8) == 0 ||
        strncmp(path, "/vendor/", 8) == 0 ||
        strncmp(path, "/apex/", 6) == 0 ||
        strncmp(path, "/odm/", 5) == 0 ||
        strncmp(path, "/product/", 9) == 0 ||
        strncmp(path, "/system_ext/", 12) == 0) {
        return true;
    }
    // Never touch KLEE hook infrastructure or the custom Vulkan driver itself
    if (strstr(path, "libklee_vk_bridge.so") ||
        strstr(path, "libbytehook.so") ||
        strstr(path, "libshadowhook.so") ||
        strstr(path, "libhook_impl.so") ||
        strstr(path, "libmain_hook.so") ||
        strstr(path, "libfile_redirect_hook.so") ||
        strstr(path, "libgsl_alloc_hook.so") ||
        strstr(path, "klee_adrenotools/driver") ||
        strstr(path, "libvulkan_panfrost.so") ||
        strstr(path, "libvulkan_freedreno.so")) {
        return true;
    }
    if (!s_customDriverName.empty() && strstr(path, s_customDriverName.c_str()) != nullptr) {
        return true;
    }
    return false;
}

/**
 * Only hook libraries belonging to the target application (/data/app, /data/user, /data/data, .apk).
 * Keeps Android framework (HWUI, RenderThread, libEGL, libMEOW, ART) 100% untouched.
 */
static bool klee_caller_allow_filter(const char *caller_path_name, void * /* arg */) {
    if (is_ignored_lib(caller_path_name)) return false;
    return strstr(caller_path_name, "/data/app/") != nullptr ||
           strstr(caller_path_name, "/data/user/") != nullptr ||
           strstr(caller_path_name, "/data/data/") != nullptr ||
           strstr(caller_path_name, ".apk") != nullptr;
}

static void on_bytehook_hooked(
    bytehook_stub_t /* task_stub */,
    int status_code,
    const char *caller_path_name,
    const char *sym_name,
    void * /* new_func */,
    void * /* prev_func */,
    void * /* arg */
) {
    if (status_code == 0 && caller_path_name && sym_name) {
        // Log key entrypoints to keep boot_trace compact
        if (strcmp(sym_name, "dlopen") == 0 ||
            strcmp(sym_name, "vkCreateInstance") == 0 ||
            strcmp(sym_name, "vkGetInstanceProcAddr") == 0) {
            const char *base = strrchr(caller_path_name, '/');
            LOGI("HOOKED %s :: %s", base ? base + 1 : caller_path_name, sym_name);
        }
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
    LOGI("klee_vkCreateInstance called!");
    if (!g_pfn_vkCreateInstance) {
        LOGE("g_pfn_vkCreateInstance is null!");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkResult res = g_pfn_vkCreateInstance(pCreateInfo, pAllocator, pInstance);
    if (res == VK_SUCCESS && pInstance && *pInstance != VK_NULL_HANDLE) {
        g_instance = *pInstance;
        LOGI("klee_vkCreateInstance SUCCEEDED! g_instance=%p", g_instance);
    } else {
        LOGE("klee_vkCreateInstance failed: VkResult=%d", static_cast<int>(res));
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

// ---------- ByteHook PLT Proxies (safe BYTEHOOK_CALL_PREV, zero BTI risk) ----------

static void* bh_dlopen(const char *filename, int flags) {
    BYTEHOOK_STACK_SCOPE();
    if (is_vulkan_loader_name(filename)) {
        LOGI("bh_dlopen: INTERCEPTED %s -> custom g_vk=%p", filename, g_vk);
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
            // Safe: libklee_vk_bridge.so is ignored by ByteHook so dlsym calls bionic directly
            return dlsym(g_vk, symbol);
        }
    }
    return BYTEHOOK_CALL_PREV(bh_dlsym, handle, symbol);
}

static void* bh_android_dlopen_ext(const char *filename, int flag, const void *extinfo) {
    BYTEHOOK_STACK_SCOPE();
    if (is_vulkan_loader_name(filename)) {
        LOGI("bh_android_dlopen_ext: INTERCEPTED %s -> custom g_vk=%p", filename, g_vk);
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

// ---------- Fallback Direct PLT/GOT Proxies (only if ByteHook unavailable) ----------

static void* plt_dlopen(const char *filename, int flags) {
    if (is_vulkan_loader_name(filename)) {
        LOGI("plt_dlopen: INTERCEPTED %s -> custom g_vk=%p", filename, g_vk);
        return g_vk;
    }
    void *res = dlopen(filename, flags);
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
    return dlsym(handle, symbol);
}

static void* plt_android_dlopen_ext(const char *filename, int flag, const void *extinfo) {
    if (is_vulkan_loader_name(filename)) {
        LOGI("plt_android_dlopen_ext: INTERCEPTED %s -> custom g_vk=%p", filename, g_vk);
        return g_vk;
    }
    void *res = android_dlopen_ext(filename, flag, static_cast<const android_dlextinfo*>(extinfo));
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

static void add_partial_hook(const char *callee, const char *sym, void *new_fn) {
    if (!new_fn) return;
    bytehook_stub_t st = bytehook_hook_partial(
        klee_caller_allow_filter,
        nullptr,
        callee,
        sym,
        new_fn,
        on_bytehook_hooked,
        nullptr
    );
    if (st) s_stubs.push_back(st);
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
        // Hook dynamic loader calls ONLY in app libraries (never system/HWUI/EGL)
        add_partial_hook(nullptr, "dlopen", reinterpret_cast<void*>(bh_dlopen));
        add_partial_hook(nullptr, "dlsym", reinterpret_cast<void*>(bh_dlsym));
        add_partial_hook(nullptr, "android_dlopen_ext", reinterpret_cast<void*>(bh_android_dlopen_ext));
        add_partial_hook(nullptr, "dlclose", reinterpret_cast<void*>(bh_dlclose));

        // Hook Vulkan core entrypoints ONLY in app libraries
        add_partial_hook(nullptr, "vkGetInstanceProcAddr", reinterpret_cast<void*>(bh_vkGetInstanceProcAddr));
        add_partial_hook(nullptr, "vkCreateInstance", reinterpret_cast<void*>(bh_vkCreateInstance));
        add_partial_hook(nullptr, "vkEnumerateInstanceVersion", reinterpret_cast<void*>(bh_vkEnumerateInstanceVersion));
        add_partial_hook(nullptr, "vkEnumerateInstanceExtensionProperties", reinterpret_cast<void*>(bh_vkEnumerateInstanceExtensionProperties));
        add_partial_hook(nullptr, "vkEnumerateInstanceLayerProperties", reinterpret_cast<void*>(bh_vkEnumerateInstanceLayerProperties));

        // Direct PLT redirects for apps that link core Vulkan symbols directly from libvulkan.so
        static const char *kDirectVkSyms[] = {
            "vkDestroyInstance",
            "vkEnumeratePhysicalDevices",
            "vkGetPhysicalDeviceProperties",
            "vkGetPhysicalDeviceProperties2",
            "vkGetPhysicalDeviceFeatures",
            "vkGetPhysicalDeviceFeatures2",
            "vkGetPhysicalDeviceQueueFamilyProperties",
            "vkGetPhysicalDeviceQueueFamilyProperties2",
            "vkGetPhysicalDeviceMemoryProperties",
            "vkGetPhysicalDeviceMemoryProperties2",
            "vkEnumerateDeviceExtensionProperties",
            "vkEnumerateDeviceLayerProperties",
            "vkCreateDevice",
            "vkDestroyDevice",
            "vkGetDeviceProcAddr",
            "vkCreateAndroidSurfaceKHR",
            "vkDestroySurfaceKHR"
        };
        for (const char *sym : kDirectVkSyms) {
            void *target = dlsym(g_vk, sym);
            if (target) {
                add_partial_hook("libvulkan.so", sym, target);
            }
        }
        LOGI("ByteHook partial app-only hooks installed (%zu stubs, HWUI/EGL untouched)", s_stubs.size());
    } else {
        LOGW("bytehook_init returned %d — falling back to direct ELF64 PLT/GOT hooks", bh_res);
        s_bytehook_active = false;
        refresh_hooks();
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
    for (bytehook_stub_t st : s_stubs) {
        if (st) bytehook_unhook(st);
    }
    s_stubs.clear();
}
