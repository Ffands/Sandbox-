package com.sandboxforge.app.clone

import com.android.apksig.ApkSigner
import com.sandboxforge.app.logging.AppLogger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Generates and persists a unique per-device RSA-2048 signing keypair + self-signed X.509 cert
 * used to sign cloned APKs (base + all split APKs) with APK Signature Scheme v1/v2/v3.
 */
class LocalKeystoreManager(baseFilesDir: File) {
    companion object {
        private const val KEYSTORE_FILE = "sandboxforge_clone.p12"
        private const val KEY_ALIAS = "sandboxforge_local"
        private val STORE_PASSWORD = charArrayOf('s', 'f', '_', 'l', 'o', 'c', 'a', 'l', '_', 'k', 'e', 'y')
    }

    private val keysDir = File(baseFilesDir, "keys").apply { mkdirs() }
    private val keystoreFile = File(keysDir, KEYSTORE_FILE)
    private val bcProvider = BouncyCastleProvider()

    @Synchronized
    fun getSignerConfig(): ApkSigner.SignerConfig {
        val (privateKey, cert) = loadOrCreateKeyPair()
        return ApkSigner.SignerConfig.Builder(KEY_ALIAS, privateKey, listOf(cert)).build()
    }

    private fun loadOrCreateKeyPair(): Pair<PrivateKey, X509Certificate> {
        if (keystoreFile.exists() && keystoreFile.length() > 0L) {
            runCatching {
                val ks = KeyStore.getInstance("PKCS12")
                FileInputStream(keystoreFile).use { fis ->
                    ks.load(fis, STORE_PASSWORD)
                }
                val key = ks.getKey(KEY_ALIAS, STORE_PASSWORD) as PrivateKey
                val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
                return key to cert
            }.onFailure { err ->
                AppLogger.w("Keystore", "Повреждён локальный ключ, пересоздаю", err)
                keystoreFile.delete()
            }
        }

        AppLogger.i("Keystore", "Генерация локального RSA-2048 ключа для подписи клонов...")
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60L * 60L * 1000L)
        val notAfter = Date(now + 25L * 365L * 24L * 60L * 60L * 1000L)
        val serial = BigInteger(64, SecureRandom()).abs()
        val subject = X500Name("CN=SandboxForge Local Clone, O=SandboxForge, C=XX")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)
        val certHolder = certBuilder.build(contentSigner)
        val certificate = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certHolder)

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(KEY_ALIAS, keyPair.private, STORE_PASSWORD, arrayOf(certificate))
        FileOutputStream(keystoreFile).use { fos ->
            ks.store(fos, STORE_PASSWORD)
        }
        AppLogger.i("Keystore", "Локальный ключ создан: ${keystoreFile.name}")
        return keyPair.private to certificate
    }
}
