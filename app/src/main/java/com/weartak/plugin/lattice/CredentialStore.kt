// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.weartak.plugin.lattice.core.ConnectionConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

class SavedConfiguration(val connection: ConnectionConfig, val caPem: String) {
    override fun toString() = "SavedConfiguration(redacted)"
}

class CredentialStore(context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "lattice-credentials")
    private val atomic = android.util.AtomicFile(file)
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun save(value: SavedConfiguration) {
        require(value.caPem.toByteArray().size <= MAX_CA_BYTES)
        val plaintext = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use {
                it.writeUTF(value.connection.origin.toASCIIString())
                it.writeUTF(value.connection.token)
                it.writeUTF(value.connection.sandboxToken)
                it.writeInt(value.caPem.toByteArray().size)
                it.write(value.caPem.toByteArray())
            }
            buffer.toByteArray()
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val encrypted = cipher.doFinal(plaintext)
            val output = atomic.startWrite()
            try {
                DataOutputStream(output).apply {
                    writeInt(cipher.iv.size)
                    write(cipher.iv)
                    write(encrypted)
                    flush()
                }
                atomic.finishWrite(output)
            } catch (error: IOException) {
                atomic.failWrite(output)
                throw error
            }
        } finally {
            plaintext.fill(0)
        }
        val restored = load()
        if (restored == null || restored.connection.origin != value.connection.origin ||
            restored.connection.token != value.connection.token || restored.connection.sandboxToken != value.connection.sandboxToken ||
            restored.caPem != value.caPem) throw IOException("Credential persistence verification failed.")
    }

    @Synchronized
    fun load(): SavedConfiguration? {
        if (!atomic.baseFile.exists() && !java.io.File("${file.path}.bak").exists()) return null
        val bytes = atomic.openRead().use {
            val data = it.readNBytesCompat(MAX_FILE_BYTES + 1)
            require(data.size <= MAX_FILE_BYTES) { "Invalid credential file." }
            data
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 12) { "Invalid credential file." }
        val iv = ByteArray(12).also { input.readFully(it) }
        val encrypted = ByteArray(input.available()).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val existing = keys.getKey(KEY_ALIAS, null) as? SecretKey ?: throw IOException("Credential key unavailable.")
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(encrypted)
        try {
            return DataInputStream(ByteArrayInputStream(plaintext)).use {
                val config = ConnectionConfig(it.readUTF(), it.readUTF(), it.readUTF())
                val length = it.readInt()
                require(length in 0..MAX_CA_BYTES && length == it.available())
                val ca = ByteArray(length).also(it::readFully).toString(Charsets.UTF_8)
                SavedConfiguration(config, ca)
            }
        } finally {
            plaintext.fill(0)
            bytes.fill(0)
        }
    }

    @Synchronized
    fun forget() {
        atomic.delete()
        check(!file.exists() && !java.io.File("${file.path}.bak").exists()) { "Credential removal failed." }
        if (keys.containsAlias(KEY_ALIAS)) keys.deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey {
        (keys.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    companion object {
        const val MAX_CA_BYTES = 16_384
        private const val MAX_FILE_BYTES = 40_000
        private const val KEY_ALIAS = "lattice-configuration-v1"
        private val AAD = "com.weartak.plugin.lattice/configuration/v1".toByteArray()

        fun tls(caPem: String): SSLSocketFactory? {
            if (caPem.isBlank()) return null
            require(caPem.toByteArray().size <= MAX_CA_BYTES)
            val certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(caPem.toByteArray()))
            if (certificates.isEmpty()) throw java.security.cert.CertificateException("No CA certificates found.")
            require(certificates.size <= 8) { "Enter at most eight approved CA certificates." }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            certificates.forEachIndexed { index, certificate ->
                require(certificate is X509Certificate && certificate.basicConstraints >= 0)
                certificate.checkValidity()
                trust.setCertificateEntry("ca-$index", certificate)
            }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (read < 0) break
        if (read == 0) throw IOException("Credential read stalled.")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
