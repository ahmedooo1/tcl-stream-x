package fr.ahmad.tclstreamx.data.xtream

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (account logins) before they are written to the database. */
interface SecretBox {
    fun seal(plain: String): String

    /** Null when the value cannot be decrypted (key lost after a backup restore, tampering). */
    fun open(sealed: String): String?
}

/**
 * AES-256-GCM with a key generated inside the Android Keystore: the key never leaves the secure
 * hardware (or the keystore daemon on devices without it) and is not part of app backups, so a
 * copied database does not reveal the logins.
 */
class KeystoreSecretBox : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return VERSION + Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? {
        if (!sealed.startsWith(VERSION)) return null
        return try {
            val raw = Base64.decode(sealed.removePrefix(VERSION), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "tclstreamx.accounts.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val VERSION = "k1:"
    }
}
