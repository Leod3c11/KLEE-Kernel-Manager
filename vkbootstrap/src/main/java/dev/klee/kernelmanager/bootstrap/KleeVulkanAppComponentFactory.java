package dev.klee.kernelmanager.bootstrap;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Injected AppComponentFactory that boots PPSSPP's libadrenotools Vulkan driver loader
 * before the target application creates its Application object or loads native Vulkan libraries.
 *
 * OpenGL ES / libEGL / libMEOW remains 100% stock; only Vulkan calls are routed to the
 * custom driver via libadrenotools + ByteHook + ShadowHook.
 */
public class KleeVulkanAppComponentFactory extends AppComponentFactory {

    private static final String TAG = "KLEE-Bootstrap";
    private static final String META_ORIG_FACTORY = "dev.klee.kernelmanager.ORIGINAL_APP_COMPONENT_FACTORY";

    private static final AtomicBoolean sInitAttempted = new AtomicBoolean(false);
    private static final AtomicBoolean sToastShown = new AtomicBoolean(false);
    private static final AtomicBoolean sCrashGuardCleared = new AtomicBoolean(false);

    private static volatile boolean sNativeLoaded = false;
    private static volatile boolean sHookActive = false;
    private static volatile String sActiveDriverTitle = "";
    private static volatile String sPkgDataDir = "";

    private AppComponentFactory mOriginalFactory = null;

    private static native boolean nativeInit(
        String pkgDataDir,
        String pkgCodeDir,
        String tempDir,
        String hookLibDir,
        String customDriverDir,
        String customDriverName,
        String fileRedirectDir
    );

    private static native void nativeRefreshHooks();

    private static native void nativeClearCrashGuard(String pkgDataDir);

    private AppComponentFactory resolveOriginalFactory(ClassLoader cl, ApplicationInfo aInfo) {
        if (mOriginalFactory != null) {
            return mOriginalFactory;
        }
        if (aInfo == null || aInfo.metaData == null) {
            return null;
        }
        String origName = aInfo.metaData.getString(META_ORIG_FACTORY);
        if (origName == null || origName.trim().isEmpty()) {
            return null;
        }
        origName = origName.trim();
        if (KleeVulkanAppComponentFactory.class.getName().equals(origName)) {
            return null;
        }
        try {
            Class<?> cls = cl.loadClass(origName);
            Object instance = cls.getDeclaredConstructor().newInstance();
            if (instance instanceof AppComponentFactory) {
                mOriginalFactory = (AppComponentFactory) instance;
                Log.i(TAG, "Chaining to original AppComponentFactory: " + origName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to instantiate original AppComponentFactory " + origName + ": " + t.getMessage());
        }
        return mOriginalFactory;
    }

    private void ensureInitialized(ApplicationInfo aInfo) {
        if (aInfo == null || !sInitAttempted.compareAndSet(false, true)) {
            return;
        }

        try {
            sPkgDataDir = aInfo.dataDir != null ? aInfo.dataDir : "";
            String pkgName = aInfo.packageName != null ? aInfo.packageName : "";
            String hookLibDir = aInfo.nativeLibraryDir != null ? aInfo.nativeLibraryDir : "";
            String codeDir = aInfo.sourceDir != null ? aInfo.sourceDir : "";

            Log.i(TAG, "Bootstrap init for " + pkgName + " (nativeLibDir=" + hookLibDir + ")");

            // Locate config.json written by KLEE Kernel Manager
            File privateBase = new File(sPkgDataDir, "files/klee_adrenotools");
            File configFile = new File(privateBase, "config.json");
            if (!configFile.exists() && !pkgName.isEmpty()) {
                File tmpConfig = new File("/data/local/tmp/klee_adrenotools/" + pkgName + "/config.json");
                if (tmpConfig.exists() && tmpConfig.canRead()) {
                    configFile = tmpConfig;
                }
            }

            if (!configFile.exists() || !configFile.canRead()) {
                Log.i(TAG, "No active KLEE AdrenoTools config found -> using stock Mali Vulkan");
                return;
            }

            String jsonText = readFileText(configFile);
            if (jsonText == null || jsonText.trim().isEmpty()) {
                Log.w(TAG, "Empty KLEE config.json -> using stock Mali Vulkan");
                return;
            }

            JSONObject cfg = new JSONObject(jsonText);
            String mode = cfg.optString("mode", "SYSTEM");
            if (!"CUSTOM".equalsIgnoreCase(mode)) {
                Log.i(TAG, "Config mode=" + mode + " -> using stock Mali Vulkan");
                return;
            }

            String driverLib = cfg.optString("driverLib", "").trim();
            String driverTitle = cfg.optString("driverTitle", driverLib).trim();
            String driverSourceDir = cfg.optString("driverSourceDir", "").trim();

            if (driverLib.isEmpty()) {
                Log.w(TAG, "Config missing driverLib -> using stock Mali Vulkan");
                return;
            }

            File driverDir = new File(privateBase, "driver");
            File tempDir = new File(privateBase, "temp");
            File redirectDir = new File(privateBase, "vk_file_redirect");
            driverDir.mkdirs();
            tempDir.mkdirs();
            redirectDir.mkdirs();

            File targetDriverFile = new File(driverDir, driverLib);
            if (!targetDriverFile.exists() && !driverSourceDir.isEmpty()) {
                copyDriverDirectory(new File(driverSourceDir), driverDir);
            }

            if (!targetDriverFile.exists() || !targetDriverFile.canRead()) {
                Log.e(TAG, "Driver .so missing or unreadable: " + targetDriverFile.getAbsolutePath());
                return;
            }

            // Load native libklee_vk_bridge.so from app's nativeLibraryDir
            File bridgeSo = new File(hookLibDir, "libklee_vk_bridge.so");
            if (bridgeSo.exists()) {
                System.load(bridgeSo.getAbsolutePath());
            } else {
                System.loadLibrary("klee_vk_bridge");
            }
            sNativeLoaded = true;

            boolean ok = nativeInit(
                sPkgDataDir,
                codeDir,
                tempDir.getAbsolutePath(),
                hookLibDir,
                driverDir.getAbsolutePath(),
                driverLib,
                redirectDir.getAbsolutePath()
            );

            if (ok) {
                sHookActive = true;
                sActiveDriverTitle = driverTitle;
                Log.i(TAG, "KLEE AdrenoTools ACTIVE: " + driverTitle + " (" + driverLib + ")");
            } else {
                Log.w(TAG, "nativeInit returned false -> stock Mali fallback active");
            }
        } catch (Throwable t) {
            Log.e(TAG, "KLEE Bootstrap error (falling back to stock driver): " + t.getMessage(), t);
        }
    }

    private static String readFileText(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "Failed reading " + f.getAbsolutePath() + ": " + t.getMessage());
            return null;
        }
    }

    private static void copyDriverDirectory(File srcDir, File dstDir) {
        if (!srcDir.exists() || !srcDir.isDirectory()) return;
        File[] files = srcDir.listFiles();
        if (files == null) return;
        byte[] buf = new byte[32768];
        for (File src : files) {
            if (!src.isFile() || !src.getName().endsWith(".so")) continue;
            File dst = new File(dstDir, src.getName());
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                dst.setReadable(true, false);
                dst.setExecutable(true, false);
            } catch (Throwable t) {
                Log.w(TAG, "Failed copying " + src.getName() + ": " + t.getMessage());
            }
        }
    }

    private static void safeRefreshHooks() {
        if (sNativeLoaded && sHookActive) {
            try {
                nativeRefreshHooks();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        ensureInitialized(aInfo);
        AppComponentFactory orig = resolveOriginalFactory(cl, aInfo);
        if (orig != null) {
            return orig.instantiateClassLoader(cl, aInfo);
        }
        return super.instantiateClassLoader(cl, aInfo);
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
        throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        safeRefreshHooks();
        Application app = (mOriginalFactory != null)
            ? mOriginalFactory.instantiateApplication(cl, className)
            : super.instantiateApplication(cl, className);

        if (app != null) {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    if (!sInitAttempted.get() && activity.getApplicationInfo() != null) {
                        ensureInitialized(activity.getApplicationInfo());
                    }
                    safeRefreshHooks();
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    safeRefreshHooks();
                    if (sNativeLoaded && sHookActive && sCrashGuardCleared.compareAndSet(false, true)) {
                        try {
                            nativeClearCrashGuard(sPkgDataDir);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (sHookActive && sToastShown.compareAndSet(false, true)) {
                        final String label = sActiveDriverTitle;
                        final Application targetApp = app;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                Toast.makeText(
                                    targetApp,
                                    "KLEE AdrenoTools: " + label,
                                    Toast.LENGTH_SHORT
                                ).show();
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                }

                @Override public void onActivityResumed(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        }
        return app;
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
        throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        safeRefreshHooks();
        if (mOriginalFactory != null) {
            return mOriginalFactory.instantiateActivity(cl, className, intent);
        }
        return super.instantiateActivity(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
        throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (mOriginalFactory != null) {
            return mOriginalFactory.instantiateReceiver(cl, className, intent);
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
        throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        safeRefreshHooks();
        if (mOriginalFactory != null) {
            return mOriginalFactory.instantiateService(cl, className, intent);
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
        throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (mOriginalFactory != null) {
            return mOriginalFactory.instantiateProvider(cl, className);
        }
        return super.instantiateProvider(cl, className);
    }
}
