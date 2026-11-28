#include "hooks.h"

#include <adrenotools/driver.h>
#include <android/log.h>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include <vulkan/vulkan.h>

#define TAG "KLEE-Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string jstrToStd(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static std::string ensureTrailingSlash(std::string p) {
    if (!p.empty() && p.back() != '/') p.push_back('/');
    return p;
}

static std::string sanitizeField(const char *s) {
    if (!s) return "";
    std::string out(s);
    for (char &ch : out) {
        if (ch == '|' || ch == '\n' || ch == '\r') ch = ' ';
    }
    return out;
}

static bool check_and_set_crash_guard(const std::string &pkgDataDir) {
    std::string filesDir = pkgDataDir + "/files";
    mkdir(filesDir.c_str(), 0755);
    std::string flagPath = filesDir + "/klee_vk_crash_flag";
    struct stat st{};
    if (stat(flagPath.c_str(), &st) == 0) {
        LOGW("Crash guard triggered (%s) — falling back to stock Mali driver for this launch.", flagPath.c_str());
        unlink(flagPath.c_str());
        return false;
    }
    FILE *fp = fopen(flagPath.c_str(), "w");
    if (fp) {
        fputs("active", fp);
        fclose(fp);
    }
    return true;
}

static void *open_with_adrenotools(
    const std::string &tempDir,
    const std::string &hookLibDir,
    const std::string &driverDir,
    const std::string &driverName,
    const std::string &fileRedirectDir
) {
    std::string tDir = ensureTrailingSlash(tempDir);
    std::string dDir = ensureTrailingSlash(driverDir);
    std::string rDir = ensureTrailingSlash(fileRedirectDir);

    void *handle = nullptr;

    // 1. Exact PPSSPP call: ADRENOTOOLS_DRIVER_CUSTOM | ADRENOTOOLS_DRIVER_FILE_REDIRECT
    if (!tDir.empty() && !rDir.empty()) {
        handle = adrenotools_open_libvulkan(
            RTLD_NOW | RTLD_LOCAL,
            ADRENOTOOLS_DRIVER_CUSTOM | ADRENOTOOLS_DRIVER_FILE_REDIRECT,
            tDir.c_str(),
            hookLibDir.c_str(),
            dDir.c_str(),
            driverName.c_str(),
            rDir.c_str(),
            nullptr
        );
    }

    // 2. Fallback: ADRENOTOOLS_DRIVER_CUSTOM without file redirect
    if (!handle) {
        LOGW("adrenotools_open_libvulkan with FILE_REDIRECT returned null (%s), trying CUSTOM-only...",
             dlerror() ? dlerror() : "no dlerror");
        handle = adrenotools_open_libvulkan(
            RTLD_NOW | RTLD_LOCAL,
            ADRENOTOOLS_DRIVER_CUSTOM,
            tDir.empty() ? nullptr : tDir.c_str(),
            hookLibDir.c_str(),
            dDir.c_str(),
            driverName.c_str(),
            nullptr,
            nullptr
        );
    }

    return handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_klee_kernelmanager_bootstrap_KleeVulkanAppComponentFactory_nativeInit(
    JNIEnv *env,
    jclass /* clazz */,
    jstring pkgDataDir,
    jstring pkgCodeDir,
    jstring tempDir,
    jstring hookLibDir,
    jstring customDriverDir,
    jstring customDriverName,
    jstring fileRedirectDir
) {
    std::string pkgDataDirStr = jstrToStd(env, pkgDataDir);
    std::string pkgCodeDirStr = jstrToStd(env, pkgCodeDir);
    std::string tempDirStr = jstrToStd(env, tempDir);
    std::string hookLibDirStr = jstrToStd(env, hookLibDir);
    std::string driverDirStr = jstrToStd(env, customDriverDir);
    std::string driverNameStr = jstrToStd(env, customDriverName);
    std::string redirectDirStr = jstrToStd(env, fileRedirectDir);

    LOGI("KleeVulkanAppComponentFactory nativeInit starting");
    LOGI("  driver=%s/%s hookLibDir=%s", driverDirStr.c_str(), driverNameStr.c_str(), hookLibDirStr.c_str());

    if (!check_and_set_crash_guard(pkgDataDirStr)) {
        return JNI_FALSE;
    }

    void *vkHandle = open_with_adrenotools(
        tempDirStr,
        hookLibDirStr,
        driverDirStr,
        driverNameStr,
        redirectDirStr
    );

    if (!vkHandle) {
        LOGE("adrenotools_open_libvulkan FAILED for %s/%s: %s",
             driverDirStr.c_str(), driverNameStr.c_str(), dlerror() ? dlerror() : "unknown");
        unlink((pkgDataDirStr + "/files/klee_vk_crash_flag").c_str());
        return JNI_FALSE;
    }

    LOGI("adrenotools_open_libvulkan SUCCEEDED! handle=%p", vkHandle);

    // Pre-warm Vulkan ICD
    auto pfn_gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vkHandle, "vkGetInstanceProcAddr"));
    if (pfn_gipa) {
        auto pfn_enum = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
            pfn_gipa(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties")
        );
        if (pfn_enum) {
            uint32_t count = 0;
            pfn_enum(nullptr, &count, nullptr);
            LOGI("Pre-warmed custom Vulkan driver: %u instance extensions", count);
        }
    }

    if (!init_hooks(vkHandle, pkgDataDirStr.c_str(), pkgCodeDirStr.c_str(), driverNameStr.c_str())) {
        LOGE("init_hooks FAILED!");
        unlink((pkgDataDirStr + "/files/klee_vk_crash_flag").c_str());
        return JNI_FALSE;
    }

    LOGI("KLEE AdrenoTools pipeline active for %s", driverNameStr.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_klee_kernelmanager_bootstrap_KleeVulkanAppComponentFactory_nativeRefreshHooks(
    JNIEnv * /* env */,
    jclass /* clazz */
) {
    refresh_hooks();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_klee_kernelmanager_bootstrap_KleeVulkanAppComponentFactory_nativeClearCrashGuard(
    JNIEnv *env,
    jclass /* clazz */,
    jstring pkgDataDir
) {
    std::string dir = jstrToStd(env, pkgDataDir);
    if (!dir.empty()) {
        unlink((dir + "/files/klee_vk_crash_flag").c_str());
        LOGI("Crash guard cleared");
    }
}

/**
 * In-app native PPSSPP libadrenotools driver tester.
 * Loads the chosen custom Vulkan driver inside KLEE and queries real VkPhysicalDeviceProperties
 * + VkPhysicalDeviceDriverProperties + device extension count.
 *
 * Output format:
 *   OK|deviceName|apiVersion|driverVersion|driverName|driverInfo|driverId|extCount|elapsedMs
 *   ERR|reason
 */
extern "C" JNIEXPORT jstring JNICALL
Java_dev_klee_kernelmanager_AdrenoToolsProbe_nativeProbeDriver(
    JNIEnv *env,
    jobject /* thiz */,
    jstring tempDir,
    jstring hookLibDir,
    jstring customDriverDir,
    jstring customDriverName,
    jstring fileRedirectDir
) {
    auto t0 = std::chrono::steady_clock::now();
    std::string tempDirStr = jstrToStd(env, tempDir);
    std::string hookLibDirStr = jstrToStd(env, hookLibDir);
    std::string driverDirStr = jstrToStd(env, customDriverDir);
    std::string driverNameStr = jstrToStd(env, customDriverName);
    std::string redirectDirStr = jstrToStd(env, fileRedirectDir);

    LOGI("AdrenoToolsProbe: testing %s/%s (hookLibDir=%s)",
         driverDirStr.c_str(), driverNameStr.c_str(), hookLibDirStr.c_str());

    void *vkHandle = open_with_adrenotools(
        tempDirStr,
        hookLibDirStr,
        driverDirStr,
        driverNameStr,
        redirectDirStr
    );
    if (!vkHandle) {
        const char *err = dlerror();
        std::string msg = std::string("ERR|adrenotools_open_libvulkan falhou: ") + (err ? err : "handle nulo");
        return env->NewStringUTF(msg.c_str());
    }

    auto pfn_gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vkHandle, "vkGetInstanceProcAddr"));
    if (!pfn_gipa) {
        return env->NewStringUTF("ERR|vkGetInstanceProcAddr não encontrado na biblioteca");
    }

    auto pfn_create = reinterpret_cast<PFN_vkCreateInstance>(pfn_gipa(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!pfn_create) {
        return env->NewStringUTF("ERR|vkCreateInstance não encontrado na biblioteca");
    }

    uint32_t loaderApi = VK_API_VERSION_1_1;
    auto pfn_instVer = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(pfn_gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    if (pfn_instVer) {
        uint32_t ver = 0;
        if (pfn_instVer(&ver) == VK_SUCCESS && ver >= VK_API_VERSION_1_0) {
            loaderApi = ver;
        }
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "KLEE-AdrenoTools-Probe";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "KLEE";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = loaderApi;

    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult res = pfn_create(&ci, nullptr, &instance);
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        // Retry with Vulkan 1.0 in case driver rejects newer instance apiVersion
        appInfo.apiVersion = VK_API_VERSION_1_0;
        res = pfn_create(&ci, nullptr, &instance);
    }
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        std::ostringstream oss;
        oss << "ERR|vkCreateInstance falhou (VkResult=" << static_cast<int>(res) << ")";
        return env->NewStringUTF(oss.str().c_str());
    }

    auto pfn_destroy = reinterpret_cast<PFN_vkDestroyInstance>(pfn_gipa(instance, "vkDestroyInstance"));
    auto pfn_enumDevs = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(pfn_gipa(instance, "vkEnumeratePhysicalDevices"));
    auto pfn_getProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(pfn_gipa(instance, "vkGetPhysicalDeviceProperties"));
    auto pfn_getProps2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(pfn_gipa(instance, "vkGetPhysicalDeviceProperties2"));
    auto pfn_enumDevExt = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(pfn_gipa(instance, "vkEnumerateDeviceExtensionProperties"));

    if (!pfn_enumDevs || !pfn_getProps) {
        if (pfn_destroy) pfn_destroy(instance, nullptr);
        return env->NewStringUTF("ERR|vkEnumeratePhysicalDevices/vkGetPhysicalDeviceProperties ausentes");
    }

    uint32_t devCount = 0;
    res = pfn_enumDevs(instance, &devCount, nullptr);
    if (res != VK_SUCCESS || devCount == 0) {
        if (pfn_destroy) pfn_destroy(instance, nullptr);
        std::ostringstream oss;
        oss << "ERR|Nenhuma GPU física encontrada pelo driver (VkResult=" << static_cast<int>(res) << ", count=" << devCount << ")";
        return env->NewStringUTF(oss.str().c_str());
    }

    std::vector<VkPhysicalDevice> devs(devCount);
    pfn_enumDevs(instance, &devCount, devs.data());
    VkPhysicalDevice gpu = devs[0];

    VkPhysicalDeviceProperties props{};
    pfn_getProps(gpu, &props);

    std::string drvName;
    std::string drvInfo;
    uint32_t drvId = 0;

    if (pfn_getProps2 && props.apiVersion >= VK_API_VERSION_1_1) {
        VkPhysicalDeviceDriverProperties drvProps{};
        drvProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
        VkPhysicalDeviceProperties2 props2{};
        props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        props2.pNext = &drvProps;
        pfn_getProps2(gpu, &props2);
        drvName = sanitizeField(drvProps.driverName);
        drvInfo = sanitizeField(drvProps.driverInfo);
        drvId = static_cast<uint32_t>(drvProps.driverID);
    }

    uint32_t extCount = 0;
    if (pfn_enumDevExt) {
        pfn_enumDevExt(gpu, nullptr, &extCount, nullptr);
    }

    if (pfn_destroy) {
        pfn_destroy(instance, nullptr);
    }

    auto t1 = std::chrono::steady_clock::now();
    long long elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    uint32_t apiMaj = VK_API_VERSION_MAJOR(props.apiVersion);
    uint32_t apiMin = VK_API_VERSION_MINOR(props.apiVersion);
    uint32_t apiPat = VK_API_VERSION_PATCH(props.apiVersion);

    std::ostringstream oss;
    oss << "OK|"
        << sanitizeField(props.deviceName) << "|"
        << apiMaj << "." << apiMin << "." << apiPat << "|"
        << props.driverVersion << "|"
        << drvName << "|"
        << drvInfo << "|"
        << drvId << "|"
        << extCount << "|"
        << elapsedMs;

    std::string result = oss.str();
    LOGI("AdrenoToolsProbe result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}
