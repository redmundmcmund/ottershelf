package io.github.ottershelf.core.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the session's tokens before they are written to disk. */
interface TokenCipher {
    fun encrypt(plain: String): String

    /** Null if [stored] can't be decrypted (e.g. the key is gone because the app's keys were reset). */
    fun decrypt(stored: String): String?

    /**
     * Throws the key away, so the next [encrypt] makes a new one: for a key that can't encrypt any
     * more. Nothing it encrypted can be read afterwards.
     */
    fun replaceKey() {}
}

/**
 * AES-256/GCM with a key that lives in the Android Keystore and never leaves it. The key needs no
 * user authentication, because background sync and downloads must work with the screen locked.
 * Stored form: `v1:` + base64(12-byte IV + ciphertext with its 16-byte tag).
 *
 * The alias `bookorbit.session` is older than the Ottershelf name and stays: the Keystore finds the
 * key by it, so a new alias would mean a new key and tokens nobody can decrypt (a sign-out).
 */
class KeystoreTokenCipher(private val alias: String = "bookorbit.session") : TokenCipher {

    @Volatile private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
        cachedKey = key
        return key
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the keystore picks a fresh random IV
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV size ${iv.size}" }
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(stored: String): String? = runCatching {
        require(stored.startsWith(PREFIX))
        val bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    /** Deletes the Keystore entry; [key] generates a new one under the same alias. */
    @Synchronized
    override fun replaceKey() {
        cachedKey = null
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
