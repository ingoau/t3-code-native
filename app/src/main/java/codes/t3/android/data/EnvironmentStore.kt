package codes.t3.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import codes.t3.android.data.model.T3Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val Context.environmentStore: DataStore<Preferences> by preferencesDataStore(name = "environments")

/** A paired T3 Code server. The access token is stored encrypted (see [TokenCipher]). */
@Serializable
data class SavedEnvironment(
    val environmentId: String,
    val label: String,
    val httpBaseUrl: String,
    val encryptedToken: String,
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val wsBaseUrl: String
        get() = when {
            httpBaseUrl.startsWith("https://") -> "wss://" + httpBaseUrl.removePrefix("https://")
            else -> "ws://" + httpBaseUrl.removePrefix("http://")
        }
    val displayHost: String get() = httpBaseUrl.substringAfter("://").trimEnd('/')
}

interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(cipherText: String): String
}

/** AES-GCM with a non-exportable key in the Android Keystore. */
class KeystoreTokenCipher : TokenCipher {
    private val alias = "t3code.environment-tokens"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        val bytes = Base64.decode(cipherText, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }
}

/** Plain passthrough, for tests and environments without a Keystore. */
object PlainTokenCipher : TokenCipher {
    override fun encrypt(plain: String) = plain
    override fun decrypt(cipherText: String) = cipherText
}

class EnvironmentRepository(private val store: DataStore<Preferences>, val cipher: TokenCipher) {
    private val key = stringPreferencesKey("environments.v1")

    val environments: Flow<List<SavedEnvironment>> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { T3Json.decodeFromString<List<SavedEnvironment>>(it) }.getOrNull() }.orEmpty()
    }

    suspend fun upsert(env: SavedEnvironment) = edit { list -> list.filterNot { it.environmentId == env.environmentId } + env }

    suspend fun remove(environmentId: String) = edit { list -> list.filterNot { it.environmentId == environmentId } }

    suspend fun update(environmentId: String, transform: (SavedEnvironment) -> SavedEnvironment) =
        edit { list -> list.map { if (it.environmentId == environmentId) transform(it) else it } }

    private suspend fun edit(transform: (List<SavedEnvironment>) -> List<SavedEnvironment>) {
        store.edit { prefs ->
            val current = prefs[key]?.let { runCatching { T3Json.decodeFromString<List<SavedEnvironment>>(it) }.getOrNull() }.orEmpty()
            prefs[key] = T3Json.encodeToString(transform(current))
        }
    }
}
