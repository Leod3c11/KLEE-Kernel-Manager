#include "hooks.h"
#include "plt_hook.h"
#include "trace.h"

#include <android/dlext.h>
#include <android/log.h>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <string>

#define TAG "KLEE-Hooks"
#define LOGI(...) kleetrace::logf(TAG, ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) kleetrace::logf(TAG, ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) kleetrace::logf(TAG, ANDROID_LOG_ERROR, __VA_ARGS__)

void *g_vk = nullptr;
VkInstance g_instance = VK_NULL_HANDLE;
PFN_vkGetInstanceProcAddr g_pfn_vkGetInstanceProcAddr = nullptr;
PFN_vkCreateInstance g_pfn_vkCreateInstance = nullptr;
static PFN_vkDestroyInstance g_pfn_vkDestroyInstance = nullptr;
static PFN_vkEnumerateInstanceVersion g_pfn_vkEnumerateInstanceVersion = nullptr;
static PFN_vkEnumerateInstanceExtensionProperties g_pfn_vkEnumerateInstanceExtensionProperties = nullptr;
static PFN_vkEnumerateInstanceLayerProperties g_pfn_vkEnumerateInstanceLayerProperties = nullptr;

static std::string s_pkgDataDir;
static std::string s_pkgCodeDir;
static std::string s_customDriverName;
static std::recursive_mutex s_refresh_mutex;

typedef void* (*android_dlopen_ext_fn)(const char *filename, int flag, const android_dlextinfo *extinfo);
static void *s_orig_loader_android_dlopen_ext = nullptr;

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL klee_vkGetInstanceProcAddr(VkInstance instance, const char *pName);
static VKAPI_ATTR VkResult VKAPI_CALL klee_vkCreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkInstance *pInstance
);
static VKAPI_ATTR void VKAPI_CALL klee_vkDestroyInstance(
    VkInstance instance,
    const VkAllocationCallbacks *pAllocator
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

static void* resolve_vulkan_dispatcher(const char *symbol) {
    if (!symbol) return nullptr;
    if (strcmp(symbol, "vkGetInstanceProcAddr") == 0) {
        return reinterpret_cast<void*>(klee_vkGetInstanceProcAddr);
    }
    if (strcmp(symbol, "vkCreateInstance") == 0) {
        return reinterpret_cast<void*>(klee_vkCreateInstance);
    }
    if (strcmp(symbol, "vkDestroyInstance") == 0) {
        return reinterpret_cast<void*>(klee_vkDestroyInstance);
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
    return nullptr;
}

// ---------- Vulkan Dispatchers ----------

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
    uint32_t reqApi = (pCreateInfo && pCreateInfo->pApplicationInfo)
        ? pCreateInfo->pApplicationInfo->apiVersion : 0;
    uint32_t extCnt = pCreateInfo ? pCreateInfo->enabledExtensionCount : 0;
    LOGI("klee_vkCreateInstance(api=%u.%u.%u, exts=%u)",
         VK_API_VERSION_MAJOR(reqApi), VK_API_VERSION_MINOR(reqApi), VK_API_VERSION_PATCH(reqApi), extCnt);

    if (!g_pfn_vkCreateInstance) {
        LOGE("g_pfn_vkCreateInstance is null!");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkResult res = g_pfn_vkCreateInstance(pCreateInfo, pAllocator, pInstance);
    if (res == VK_SUCCESS && pInstance && *pInstance != VK_NULL_HANDLE) {
        g_instance = *pInstance;
        LOGI("klee_vkCreateInstance SUCCEEDED! instance=%p", g_instance);
    } else {
        LOGE("klee_vkCreateInstance failed: VkResult=%d", static_cast<int>(res));
    }
    return res;
}

static VKAPI_ATTR void VKAPI_CALL klee_vkDestroyInstance(
    VkInstance instance,
    const VkAllocationCallbacks *pAllocator
) {
    LOGI("klee_vkDestroyInstance(instance=%p)", instance);
    if (instance == g_instance) {
        g_instance = VK_NULL_HANDLE;
    }
    if (g_pfn_vkDestroyInstance) {
        g_pfn_vkDestroyInstance(instance, pAllocator);
    }
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL klee_vkGetInstanceProcAddr(VkInstance instance, const char *pName) {
    if (!pName) return nullptr;
    if (void *disp = resolve_vulkan_dispatcher(pName)) {
        return reinterpret_cast<PFN_vkVoidFunction>(disp);
    }
    if (g_pfn_vkGetInstanceProcAddr) {
        // Always pass the caller's exact VkInstance — NEVER substitute a potentially destroyed g_instance!
        PFN_vkVoidFunction pfn = g_pfn_vkGetInstanceProcAddr(instance, pName);
        if (!pfn && instance != VK_NULL_HANDLE) {
            pfn = g_pfn_vkGetInstanceProcAddr(VK_NULL_HANDLE, pName);
        }
        return pfn;
    }
    return nullptr;
}

// ---------- Deterministic ELF64 GOT Proxies (Android 16 safe, zero ShadowHook dependency) ----------

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
    if (handle == g_vk) {
        if (void *disp = resolve_vulkan_dispatcher(symbol)) {
            return disp;
        }
        return dlsym(g_vk, symbol);
    }
    if (handle == RTLD_DEFAULT && strncmp(symbol, "vk", 2) == 0) {
        if (void *disp = resolve_vulkan_dispatcher(symbol)) {
            return disp;
        }
        if (void *sym = dlsym(g_vk, symbol)) {
            return sym;
        }
    }
    return dlsym(handle, symbol);
}

static void* plt_android_dlopen_ext(const char *filename, int flag, const android_dlextinfo *extinfo) {
    if (is_vulkan_loader_name(filename)) {
        LOGI("plt_android_dlopen_ext: INTERCEPTED %s -> custom g_vk=%p", filename, g_vk);
        return g_vk;
    }
    void *res = android_dlopen_ext(filename, flag, extinfo);
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

static int plt_dlclose(void *handle) {
    if (handle == g_vk) {
        return 0;
    }
    return dlclose(handle);
}

/**
 * Intercepts libnativeloader.so & libEGL.so calls to android_dlopen_ext so that every
 * System.loadLibrary() app .so (Qt, Vulkan Caps Viewer, Unity, Unreal, etc.) and ANGLE .so
 * is GOT-hooked IMMEDIATELY BEFORE JNI_OnLoad() or native main() runs!
 */
static void* loader_android_dlopen_ext(const char *filename, int flag, const android_dlextinfo *extinfo) {
    auto orig = s_orig_loader_android_dlopen_ext
        ? reinterpret_cast<android_dlopen_ext_fn>(s_orig_loader_android_dlopen_ext)
        : &android_dlopen_ext;
    void *res = orig(filename, flag, extinfo);
    if (res != nullptr && filename != nullptr) {
        static thread_local bool s_in_loader_refresh = false;
        if (!s_in_loader_refresh) {
            s_in_loader_refresh = true;
            refresh_hooks();
            s_in_loader_refresh = false;
        }
    }
    return res;
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

    g_pfn_vkDestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(dlsym(g_vk, "vkDestroyInstance"));

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

    // 1. Hook libnativeloader.so & libEGL.so android_dlopen_ext so every newly loaded app/ANGLE lib is hooked immediately!
    int loaderHooks = kleevk::hook_loader_libraries(
        "android_dlopen_ext",
        reinterpret_cast<void*>(loader_android_dlopen_ext),
        &s_orig_loader_android_dlopen_ext
    );
    LOGI("Deterministic Android 16 loader hooks installed: %d (libnativeloader/libEGL)", loaderHooks);

    // 2. Patch all currently loaded app + ANGLE libraries
    refresh_hooks();
    return true;
}

void refresh_hooks() {
    if (!g_vk) return;
    std::lock_guard<std::recursive_mutex> lock(s_refresh_mutex);

    const char *ign = s_customDriverName.empty() ? nullptr : s_customDriverName.c_str();
    int total = 0;
    total += kleevk::hook_all_matching_libraries("dlopen", reinterpret_cast<void*>(plt_dlopen), nullptr, ign);
    total += kleevk::hook_all_matching_libraries("dlsym", reinterpret_cast<void*>(plt_dlsym), nullptr, ign);
    total += kleevk::hook_all_matching_libraries("android_dlopen_ext", reinterpret_cast<void*>(plt_android_dlopen_ext), nullptr, ign);
    total += kleevk::hook_all_matching_libraries("dlclose", reinterpret_cast<void*>(plt_dlclose), nullptr, ign);
    total += kleevk::hook_all_matching_libraries("vkCreateInstance", reinterpret_cast<void*>(klee_vkCreateInstance), nullptr, ign);
    total += kleevk::hook_all_matching_libraries("vkDestroyInstance", reinterpret_cast<void*>(klee_vkDestroyInstance), nullptr, ign);
    total += kleevk::hook_all_matching_libraries("vkGetInstanceProcAddr", reinterpret_cast<void*>(klee_vkGetInstanceProcAddr), nullptr, ign);
    total += kleevk::hook_all_matching_libraries("vkEnumerateInstanceVersion", reinterpret_cast<void*>(klee_vkEnumerateInstanceVersion), nullptr, ign);
    total += kleevk::hook_all_matching_libraries("vkEnumerateInstanceExtensionProperties", reinterpret_cast<void*>(klee_vkEnumerateInstanceExtensionProperties), nullptr, ign);
    total += kleevk::hook_all_matching_libraries("vkEnumerateInstanceLayerProperties", reinterpret_cast<void*>(klee_vkEnumerateInstanceLayerProperties), nullptr, ign);

    // Direct PLT redirects for apps that link core Vulkan symbols directly from libvulkan.so (SHN_UNDEF only)
    static const char *kDirectVkSyms[] = {
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
            total += kleevk::hook_all_matching_libraries(sym, target, nullptr, ign);
        }
    }

    if (total > 0) {
        LOGI("refresh_hooks patched %d new GOT slot(s)", total);
    }
}

void shutdown_hooks() {
    // Deterministic GOT hooks stay live for process lifetime
}
