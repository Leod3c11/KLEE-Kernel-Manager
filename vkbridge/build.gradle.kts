plugins {
    id("com.android.library")
}

android {
    namespace = "dev.klee.kernelmanager.vkbridge"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O2", "-fvisibility=hidden")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.register("packageNativeLibsToPatcher") {
    dependsOn("mergeReleaseNativeLibs", "stripReleaseDebugSymbols")

    val strippedLibsDir = layout.buildDirectory.dir("intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a")
    val mergedLibsDir = layout.buildDirectory.dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/arm64-v8a")
    val patcherArm64Dir = file("${project.rootDir}/vkpatcher/src/main/resources/arm64-v8a")

    doLast {
        patcherArm64Dir.deleteRecursively()
        patcherArm64Dir.mkdirs()

        val requiredLibs = listOf(
            "libklee_vk_bridge.so",
            "libhook_impl.so",
            "libfile_redirect_hook.so",
            "libmain_hook.so",
            "libgsl_alloc_hook.so"
        )

        val candidateDirs = listOf(
            strippedLibsDir.get().asFile,
            mergedLibsDir.get().asFile
        )

        for (lib in requiredLibs) {
            val dest = File(patcherArm64Dir, "$lib.bin")
            var copied = false

            for (dir in candidateDirs) {
                val src = File(dir, lib)
                if (src.exists()) {
                    src.copyTo(dest, overwrite = true)
                    copied = true
                    break
                }
            }

            if (!copied) {
                throw GradleException("Required native hook lib missing: $lib (searched ${candidateDirs.map { it.absolutePath }})")
            }
            logger.lifecycle("Packaged $lib (${dest.length()} bytes) -> ${dest.absolutePath}")
        }
    }
}
