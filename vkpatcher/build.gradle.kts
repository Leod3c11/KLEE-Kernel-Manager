plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.klee.kernelmanager.vkpatcher"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("io.github.reandroid:ARSCLib:1.3.5")
    implementation("com.android.tools.build:apksig:8.7.3")
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(":vkbootstrap:generateBootstrapDex")
        dependsOn(":vkbridge:packageNativeLibsToPatcher")
    }
}
