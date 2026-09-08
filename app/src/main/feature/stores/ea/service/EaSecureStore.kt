package com.winlator.cmod.feature.stores.ea.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object EaSecureStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wn_ea_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun storeFile(context: Context): File {
        val dir = File(context.filesDir, "ea").also { if (!it.exists()) it.mkdirs() }
        return File(dir, "credentials.bin")
    }

    fun exists(context: Context): Boolean = storeFile(context).exists()

    fun clear(context: Context) {
        runCatching { storeFile(context).delete() }
            .onFailure { Timber.tag("EA").w(it, "Failed clearing credentials") }
    }

    fun write(context: Context, plaintext: String): Boolean =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val output = ByteArray(cipher.iv.size + encrypted.size)
            System.arraycopy(cipher.iv, 0, output, 0, cipher.iv.size)
            System.arraycopy(encrypted, 0, output, cipher.iv.size, encrypted.size)
            storeFile(context).writeBytes(output)
            true
        } catch (e: Exception) {
            Timber.tag("EA").e(e, "Failed writing credentials")
            clear(context)
            false
        }

    fun read(context: Context): String? =
        try {
            val file = storeFile(context)
            if (!file.exists()) {
                null
            } else {
                val raw = file.readBytes()
                if (raw.size <= IV_LENGTH) {
                    clear(context)
                    null
                } else {
                    val iv = raw.copyOfRange(0, IV_LENGTH)
                    val payload = raw.copyOfRange(IV_LENGTH, raw.size)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
                    String(cipher.doFinal(payload), Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Timber.tag("EA").w(e, "Failed reading credentials, forcing re-login")
            clear(context)
            null
        }
}
