package dev.codexpocket

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.codexpocket.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All secrets and cached conversations stay encrypted in private, non-backed-up storage. */
internal class Vault(context: Context) : PocketStorage {
    private val prefs = context.getSharedPreferences("pocket_vault", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("pocket_aes", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pocket_aes", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }
    override fun get(name: String): String? {
        val value = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28) { "本地加密数据损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    override fun keys(): Set<String> = prefs.all.keys
    override fun remove(name: String) { prefs.edit().remove(name).apply() }
    override fun profiles(): List<HostProfile> = get("profiles")?.let { json.decodeFromString<List<HostProfile>>(it) }.orEmpty()
    override fun saveProfiles(profiles: List<HostProfile>) = put("profiles", json.encodeToString(profiles))
    override fun credentials(id: String): Credentials = get("secret:$id")?.let { json.decodeFromString<Credentials>(it) } ?: Credentials()
    override fun saveCredentials(id: String, credentials: Credentials) = put("secret:$id", json.encodeToString(credentials))
}
