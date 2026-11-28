import java.util.zip.ZipFile

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

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.bytedance:bytehook:1.1.1")
    implementation("com.bytedance.android:shadowhook:1.1.1")
}

tasks.register("packageNativeLibsToPatcher") {
    dependsOn("mergeReleaseNativeLibs", "stripReleaseDebugSymbols")

    val strippedLibsDir = layout.buildDirectory.dir("intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a")
    val mergedLibsDir = layout.buildDirectory.dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/arm64-v8a")
    val patcherArm64Dir = file("${project.rootDir}/vkpatcher/src/main/resources/arm64-v8a")

    doLast {
        patcherArm64Dir.mkdirs()

        val requiredLibs = listOf(
            "libklee_vk_bridge.so",
            "libbytehook.so",
            "libshadowhook.so",
            "libhook_impl.so",
            "libfile_redirect_hook.so",
            "libmain_hook.so",
            "libgsl_alloc_hook.so"
        )

        val candidateDirs = listOf(
            strippedLibsDir.get().asFile,
            mergedLibsDir.get().asFile
        )

        // Also locate AAR/exploded jni libs from releaseRuntimeClasspath for bytehook & shadowhook
        val aarFiles = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .map { it.file }

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
                // Search inside resolved AARs or exploded gradle transform dirs
                for (artifactFile in aarFiles) {
                    if (artifactFile.isFile && (artifactFile.extension == "aar" || artifactFile.extension == "zip")) {
                        ZipFile(artifactFile).use { zip ->
                            val entry = zip.getEntry("jni/arm64-v8a/$lib")
                            if (entry != null) {
                                zip.getInputStream(entry).use { input ->
                                    dest.outputStream().use { output -> input.copyTo(output) }
                                }
                                copied = true
                            }
                        }
                    }
                    if (copied) break
                }
            }

            if (!copied) {
                // Final fallback: search AGP's prefab / exploded transforms under build dir or gradle cache
                val found = project.rootDir.resolve("vkbridge/build")
                    .walkTopDown()
                    .firstOrNull { it.isFile && it.name == lib && it.parentFile?.name == "arm64-v8a" }
                if (found != null) {
                    found.copyTo(dest, overwrite = true)
                    copied = true
                }
            }

            if (!copied) {
                throw GradleException("Required native hook lib missing: $lib (searched ${candidateDirs.map { it.absolutePath }} and runtime AARs)")
            }
            logger.lifecycle("Packaged $lib (${dest.length()} bytes) -> ${dest.absolutePath}")
        }
    }
}
