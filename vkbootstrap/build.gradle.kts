plugins {
    id("com.android.library")
}

android {
    namespace = "dev.klee.kernelmanager.vkbootstrap"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.register("generateBootstrapDex") {
    dependsOn("compileReleaseJavaWithJavac")

    val classesDir = layout.buildDirectory.dir("intermediates/javac/release/compileReleaseJavaWithJavac/classes")
    val outDexDir = layout.buildDirectory.dir("intermediates/bootstrap_dex")
    val patcherResourcesDir = file("${project.rootDir}/vkpatcher/src/main/resources")

    doLast {
        val sdkDirPath = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "/opt/android-sdk"
        val sdkDir = file(sdkDirPath)
        val d8Bin = file("${sdkDir.absolutePath}/build-tools/35.0.0/d8")
        val androidJar = file("${sdkDir.absolutePath}/platforms/android-35/android.jar")

        if (!d8Bin.exists()) {
            throw GradleException("d8 not found at ${d8Bin.absolutePath} (ANDROID_HOME=$sdkDirPath)")
        }
        if (!androidJar.exists()) {
            throw GradleException("android.jar not found at ${androidJar.absolutePath}")
        }

        val classFiles = classesDir.get().asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.absolutePath }
            .toList()

        if (classFiles.isEmpty()) {
            throw GradleException("No compiled .class files found in ${classesDir.get().asFile.absolutePath}")
        }

        val tempOutDir = outDexDir.get().asFile
        tempOutDir.deleteRecursively()
        tempOutDir.mkdirs()

        val cmd = mutableListOf(
            d8Bin.absolutePath,
            "--release",
            "--min-api", "28",
            "--lib", androidJar.absolutePath,
            "--output", tempOutDir.absolutePath
        )
        cmd.addAll(classFiles)

        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw GradleException("d8 failed (exit=$exitCode):\n$output")
        }

        val builtDex = File(tempOutDir, "classes.dex")
        if (!builtDex.exists()) {
            throw GradleException("d8 succeeded but classes.dex not found in ${tempOutDir.absolutePath}")
        }

        patcherResourcesDir.mkdirs()
        val targetDex = File(patcherResourcesDir, "bootstrap.dex")
        builtDex.copyTo(targetDex, overwrite = true)
        logger.lifecycle("Generated bootstrap.dex (${targetDex.length()} bytes) -> ${targetDex.absolutePath}")
    }
}
