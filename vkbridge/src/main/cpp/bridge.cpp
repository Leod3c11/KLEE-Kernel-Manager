#include "hooks.h"
#include "trace.h"

#include <adrenotools/driver.h>
#include <android/log.h>
#include <android_linker_ns.h>
#include <cerrno>
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
#define LOGI(...) kleetrace::logf(TAG, ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) kleetrace::logf(TAG, ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) kleetrace::logf(TAG, ANDROID_LOG_ERROR, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * /* vm */, void * /* reserved */) {
    return JNI_VERSION_1_6;
}

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

static bool file_exists_log(const std::string &path, const char *label) {
    struct stat st{};
    if (stat(path.c_str(), &st) == 0) {
        return true;
    }
    LOGE("Missing/inaccessible %s (%s): errno=%d (%s)", label, path.c_str(), errno, strerror(errno));
    return false;
}

static bool check_and_set_crash_guard(const std::string &pkgDataDir) {
    std::string filesDir = pkgDataDir + "/files";
    mkdir(filesDir.c_str(), 0755);
    std::string flagPath = filesDir + "/klee_vk_crash_flag";
    struct stat st{};
    if (stat(flagPath.c_str(), &st) == 0) {
        LOGW("Crash guard triggered (%s) — falling back to stock Mali for this launch.", flagPath.c_str());
        unlink(flagPath.c_str());
        return false;
    }
    FILE *fp = fopen(flagPath.c_str(), "w");
    if (fp) {
        fputs("init", fp);
        fclose(fp);
    }
    return true;
}

static void clear_crash_guard_file(const std::string &pkgDataDir) {
    if (!pkgDataDir.empty()) {
        unlink((pkgDataDir + "/files/klee_vk_crash_flag").c_str());
    }
}

static void *open_with_adrenotools(
    const std::string &tempDir,
    const std::string &hookLibDir,
    const std::string &driverDir,
    const std::string &driverName,
    const std::string &fileRedirectDir,
    bool verboseLog
) {
    std::string tDir = ensureTrailingSlash(tempDir);
    std::string dDir = ensureTrailingSlash(driverDir);
    std::string rDir = ensureTrailingSlash(fileRedirectDir);

    if (verboseLog) {
        bool nsOk = linkernsbypass_load_status();
        LOGI("precheck: linkernsbypass=%d driver=%s%s hookLibDir=%s redirectDir=%s",
             nsOk ? 1 : 0, dDir.c_str(), driverName.c_str(), hookLibDir.c_str(), rDir.c_str());
        file_exists_log(dDir + driverName, "customDriver");
        file_exists_log(hookLibDir + "/libhook_impl.so", "libhook_impl.so");
        file_exists_log(hookLibDir + "/libmain_hook.so", "libmain_hook.so");
        file_exists_log(hookLibDir + "/libfile_redirect_hook.so", "libfile_redirect_hook.so");
        if (!rDir.empty()) file_exists_log(rDir, "fileRedirectDir");
    }

    void *handle = nullptr;

    // 1. Exact PPSSPP call: ADRENOTOOLS_DRIVER_CUSTOM | ADRENOTOOLS_DRIVER_FILE_REDIRECT
    if (!rDir.empty()) {
        handle = adrenotools_open_libvulkan(
            RTLD_NOW | RTLD_LOCAL,
            ADRENOTOOLS_DRIVER_CUSTOM | ADRENOTOOLS_DRIVER_FILE_REDIRECT,
            tDir.empty() ? nullptr : tDir.c_str(),
            hookLibDir.c_str(),
            dDir.c_str(),
            driverName.c_str(),
            rDir.c_str(),
            nullptr
        );
    }

    // 2. Fallback: ADRENOTOOLS_DRIVER_CUSTOM without file redirect
    if (!handle) {
        const char *e1 = dlerror();
        if (verboseLog) {
            LOGW("adrenotools_open_libvulkan(CUSTOM|FILE_REDIRECT) returned null (%s), trying CUSTOM-only...",
                 e1 ? e1 : "no dlerror");
        }
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

    kleetrace::init(pkgDataDirStr);
    LOGI("nativeInit starting (pid=%d, uid=%d)", static_cast<int>(getpid()), static_cast<int>(getuid()));

    if (!check_and_set_crash_guard(pkgDataDirStr)) {
        return JNI_FALSE;
    }

    void *vkHandle = open_with_adrenotools(
        tempDirStr,
        hookLibDirStr,
        driverDirStr,
        driverNameStr,
        redirectDirStr,
        true
    );

    if (!vkHandle) {
        const char *err = dlerror();
        LOGE("adrenotools_open_libvulkan FAILED for %s/%s: %s",
             driverDirStr.c_str(), driverNameStr.c_str(), err ? err : "unknown");
        clear_crash_guard_file(pkgDataDirStr);
        return JNI_FALSE;
    }

    LOGI("adrenotools_open_libvulkan SUCCEEDED! handle=%p", vkHandle);

    // Pre-warm Vulkan ICD so libvulkan_panfrost.so is loaded immediately into process maps
    auto pfn_gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vkHandle, "vkGetInstanceProcAddr"));
    if (pfn_gipa) {
        auto pfn_enum = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
            pfn_gipa(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties")
        );
        if (pfn_enum) {
            uint32_t count = 0;
            pfn_enum(nullptr, &count, nullptr);
            LOGI("Pre-warmed custom Vulkan ICD: %u instance extensions", count);
        }
        auto pfn_ver = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            pfn_gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion")
        );
        if (pfn_ver) {
            uint32_t ver = 0;
            if (pfn_ver(&ver) == VK_SUCCESS) {
                LOGI("Custom Vulkan instance version: %u.%u.%u",
                     VK_API_VERSION_MAJOR(ver), VK_API_VERSION_MINOR(ver), VK_API_VERSION_PATCH(ver));
            }
        }
    } else {
        LOGE("vkGetInstanceProcAddr NOT found in vkHandle!");
    }

    if (!init_hooks(vkHandle, pkgDataDirStr.c_str(), pkgCodeDirStr.c_str(), driverNameStr.c_str())) {
        LOGE("init_hooks FAILED!");
        clear_crash_guard_file(pkgDataDirStr);
        return JNI_FALSE;
    }

    // Clear crash guard once init + hook installation succeed so non-Activity background
    // processes never falsely lock out the main Activity launch.
    clear_crash_guard_file(pkgDataDirStr);
    LOGI("KLEE AdrenoTools pipeline ACTIVE for %s", driverNameStr.c_str());
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
    clear_crash_guard_file(dir);
}

/**
 * In-app native PPSSPP libadrenotools driver tester.
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

    __android_log_print(ANDROID_LOG_INFO, TAG, "AdrenoToolsProbe: testing %s/%s (hookLibDir=%s)",
                        driverDirStr.c_str(), driverNameStr.c_str(), hookLibDirStr.c_str());

    void *vkHandle = open_with_adrenotools(
        tempDirStr,
        hookLibDirStr,
        driverDirStr,
        driverNameStr,
        redirectDirStr,
        false
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
    __android_log_print(ANDROID_LOG_INFO, TAG, "AdrenoToolsProbe result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}
