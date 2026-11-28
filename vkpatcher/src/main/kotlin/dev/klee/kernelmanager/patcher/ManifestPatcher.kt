package dev.klee.kernelmanager.patcher

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream

object ManifestPatcher {

    const val KLEE_FACTORY_CLASS = "dev.klee.kernelmanager.bootstrap.KleeVulkanAppComponentFactory"
    const val META_ORIGINAL_FACTORY = "dev.klee.kernelmanager.ORIGINAL_APP_COMPONENT_FACTORY"

    private const val ATTR_NAME_ID = 0x01010003
    private const val ATTR_VALUE_ID = 0x01010024
    private const val ATTR_EXTRACT_NATIVE_LIBS_ID = 0x010104ea
    private const val ATTR_APP_COMPONENT_FACTORY_ID = 0x0101057a

    data class PatchManifestResult(
        val patchedBytes: ByteArray,
        val originalFactory: String?,
        val alreadyPatched: Boolean,
        val minSdkVersion: Int
    )

    fun isPatched(manifestBytes: ByteArray): Boolean {
        return try {
            val block = AndroidManifestBlock()
            block.readBytes(ByteArrayInputStream(manifestBytes))
            val appEl = block.applicationElement ?: return false
            val attr = appEl.searchAttributeByResourceId(ATTR_APP_COMPONENT_FACTORY_ID)
                ?: appEl.searchAttributeByName("appComponentFactory")
            attr?.valueAsString == KLEE_FACTORY_CLASS
        } catch (_: Throwable) {
            false
        }
    }

    fun patchManifestBytes(manifestBytes: ByteArray): PatchManifestResult {
        val block = AndroidManifestBlock()
        block.readBytes(ByteArrayInputStream(manifestBytes))

        val minSdk = runCatching { block.minSdkVersion ?: 28 }.getOrDefault(28).coerceAtLeast(21)
        val appEl = block.applicationElement
            ?: throw IllegalStateException("AndroidManifest.xml sem elemento <application>")

        val existingFactoryAttr: ResXmlAttribute? =
            appEl.searchAttributeByResourceId(ATTR_APP_COMPONENT_FACTORY_ID)
                ?: appEl.searchAttributeByName("appComponentFactory")
        val existingFactory = existingFactoryAttr?.valueAsString?.takeIf { it.isNotBlank() }

        if (existingFactory == KLEE_FACTORY_CLASS) {
            // Ensure extractNativeLibs=true is still enforced
            val extractAttr = appEl.getOrCreateAndroidAttribute("extractNativeLibs", ATTR_EXTRACT_NATIVE_LIBS_ID)
            extractAttr.setValueAsBoolean(true)
            block.refresh()
            return PatchManifestResult(
                patchedBytes = block.bytes,
                originalFactory = findOriginalFactoryMeta(appEl),
                alreadyPatched = true,
                minSdkVersion = minSdk
            )
        }

        // 1. Preserve original appComponentFactory in <meta-data> if present
        if (existingFactory != null) {
            val existingMeta = findMetaElement(appEl, META_ORIGINAL_FACTORY)
            val metaEl = existingMeta ?: appEl.createChildElement("meta-data")
            val nameAttr = metaEl.getOrCreateAndroidAttribute("name", ATTR_NAME_ID)
            nameAttr.setValueAsString(META_ORIGINAL_FACTORY)
            val valAttr = metaEl.getOrCreateAndroidAttribute("value", ATTR_VALUE_ID)
            valAttr.setValueAsString(existingFactory)
        }

        // 2. Replace android:appComponentFactory with KLEE bootstrapper
        val factoryAttr = appEl.getOrCreateAndroidAttribute("appComponentFactory", ATTR_APP_COMPONENT_FACTORY_ID)
        factoryAttr.setValueAsString(KLEE_FACTORY_CLASS)

        // 3. Force android:extractNativeLibs="true" so libadrenotools hook .so files exist in nativeLibraryDir
        val extractAttr = appEl.getOrCreateAndroidAttribute("extractNativeLibs", ATTR_EXTRACT_NATIVE_LIBS_ID)
        extractAttr.setTypeAndData(ValueType.BOOLEAN, -1)

        block.refresh()
        return PatchManifestResult(
            patchedBytes = block.bytes,
            originalFactory = existingFactory,
            alreadyPatched = false,
            minSdkVersion = minSdk
        )
    }

    private fun findMetaElement(appEl: com.reandroid.arsc.chunk.xml.ResXmlElement, metaName: String): com.reandroid.arsc.chunk.xml.ResXmlElement? {
        val it = appEl.getElements("meta-data")
        while (it.hasNext()) {
            val el = it.next()
            val nameAttr = el.searchAttributeByResourceId(ATTR_NAME_ID) ?: el.searchAttributeByName("name")
            if (nameAttr?.valueAsString == metaName) {
                return el
            }
        }
        return null
    }

    private fun findOriginalFactoryMeta(appEl: com.reandroid.arsc.chunk.xml.ResXmlElement): String? {
        val el = findMetaElement(appEl, META_ORIGINAL_FACTORY) ?: return null
        val valAttr = el.searchAttributeByResourceId(ATTR_VALUE_ID) ?: el.searchAttributeByName("value")
        return valAttr?.valueAsString
    }
}
