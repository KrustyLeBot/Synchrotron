package app.synchrotron.rclone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Proton session (user name + session tokens, never the password) encrypted at rest
 * with an AES-256-GCM key that lives in the Android Keystore and cannot be extracted.
 * The decrypted values are kept in memory after the first read: Keystore operations cost tens of ms.
 */
class ConfigVault(context: Context) {
    private val file = File(context.noBackupFilesDir, "session.bin")

    @Volatile private var loaded = false
    @Volatile private var cached: Map<String, String>? = null

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    val exists: Boolean get() = load() != null

    @Synchronized
    fun save(values: Map<String, String>) {
        if (loaded && cached == values) return
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(JSONObject(values).toString().toByteArray())
        val tmp = File(file.parentFile, "session.tmp")
        tmp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ct)
        tmp.renameTo(file)
        cached = values
        loaded = true
    }

    /** Returns null when there is no session, or when the Keystore key is gone (restored device). */
    @Synchronized
    fun load(): Map<String, String>? {
        if (loaded) return cached
        cached = decrypt()
        loaded = true
        return cached
    }

    private fun decrypt(): Map<String, String>? {
        if (!file.exists()) return null
        return try {
            val raw = file.readBytes()
            val ivLen = raw[0].toInt()
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            val json = JSONObject(String(cipher.doFinal(raw, 1 + ivLen, raw.size - 1 - ivLen)))
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        cached = null
        loaded = true
    }

    private companion object {
        const val ALIAS = "synchrotron_session"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
