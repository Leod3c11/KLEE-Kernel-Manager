package dev.klee.kernelmanager.patcher

import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

object ApkSignerUtil {

    private const val KEYSTORE_RESOURCE = "/klee_patch_signing.p12"
    private const val KEY_ALIAS = "klee_patch"
    private const val STORE_PASS = "kleekernel"

    fun signApk(inputApk: File, outputApk: File, minSdkVersion: Int = 28) {
        val ksStream = ApkSignerUtil::class.java.getResourceAsStream(KEYSTORE_RESOURCE)
            ?: throw IllegalStateException("Keystore resource $KEYSTORE_RESOURCE não encontrado")

        val keyStore = KeyStore.getInstance("PKCS12")
        ksStream.use { keyStore.load(it, STORE_PASS.toCharArray()) }

        val privateKey = keyStore.getKey(KEY_ALIAS, STORE_PASS.toCharArray()) as? PrivateKey
            ?: throw IllegalStateException("Chave privada '$KEY_ALIAS' não encontrada no keystore")
        val certChain = keyStore.getCertificateChain(KEY_ALIAS)
            ?.mapNotNull { it as? X509Certificate }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Certificado '$KEY_ALIAS' não encontrado no keystore")

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "KLEE_PATCH",
            privateKey,
            certChain
        ).build()

        outputApk.parentFile?.mkdirs()
        if (outputApk.exists()) outputApk.delete()

        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(minSdkVersion.coerceIn(21, 35))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setOtherSignersSignaturesPreserved(false)
            .build()

        signer.sign()
    }
}
