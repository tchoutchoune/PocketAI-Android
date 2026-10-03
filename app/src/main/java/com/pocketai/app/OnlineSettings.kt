package com.pocketai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Provider credentials stay on the device, encrypted by an Android Keystore key. */
class OnlineSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var webSearchEnabled: Boolean
        get() = preferences.getBoolean("web_enabled", false)
        set(value) { preferences.edit().putBoolean("web_enabled", value).apply() }

    var imageBaseUrl: String
        get() = preferences.getString("image_base", "https://api.openai.com/v1")!!
        set(value) { preferences.edit().putString("image_base", value.trim().trimEnd('/')).apply() }

    var imageModel: String
        get() = preferences.getString("image_model", "gpt-image-1")!!
        set(value) { preferences.edit().putString("image_model", value.trim()).apply() }

    var videoModel: String
        get() = preferences.getString("video_model", "fal-ai/wan/v2.2-a14b/text-to-video")!!
        set(value) { preferences.edit().putString("video_model", value.trim().trim('/')).apply() }

    var braveApiKey: String
        get() = readSecret("brave_key")
        set(value) = writeSecret("brave_key", value)

    var imageApiKey: String
        get() = readSecret("image_key")
        set(value) = writeSecret("image_key", value)

    var falApiKey: String
        get() = readSecret("fal_key")
        set(value) = writeSecret("fal_key", value)

    val hasBraveKey: Boolean get() = preferences.contains("brave_key")
    val hasImageKey: Boolean get() = preferences.contains("image_key")
    val hasFalKey: Boolean get() = preferences.contains("fal_key")

    fun inferenceUrl(id: String): String = preferences.getString("hub_${id}_url", "").orEmpty()
    fun inferenceModel(id: String): String = preferences.getString("hub_${id}_model", null)
        ?: ModelHub.find(id).repository
    fun inferenceKey(id: String): String = readSecret("hub_${id}_key")
    fun configureInference(id: String, url: String, model: String, key: String?) {
        ModelHub.find(id)
        InferenceProtocol.baseUrl(url)
        require(model.isNotBlank() && model.length <= 200 && !model.any { it.isISOControl() }) {
            "Identifiant du modèle invalide."
        }
        if (key != null) writeSecret("hub_${id}_key", key)
        check(preferences.edit().putString("hub_${id}_url", url.trim().trimEnd('/'))
            .putString("hub_${id}_model", model.trim()).commit())
    }

    var embeddingEnabled: Boolean
        get() = preferences.getBoolean("hub_embedding_enabled", false)
        set(value) { preferences.edit().putBoolean("hub_embedding_enabled", value).apply() }
    var speechVoice: String
        get() = preferences.getString("hub_speech_voice", "ff_siwis").orEmpty()
        set(value) { preferences.edit().putString("hub_speech_voice", value.trim()).apply() }

    private fun readSecret(name: String): String {
        val stored = preferences.getString(name, null) ?: return ""
        try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            require(bytes.size > 12 + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            // Do not silently treat an invalidated Keystore binding as valid credentials.
            throw IllegalStateException("Clé sécurisée indisponible. Enregistrez à nouveau la clé du fournisseur.")
        }
    }

    private fun writeSecret(name: String, value: String) {
        val clean = value.trim()
        require(clean.length <= 4096 && !clean.any { it.isISOControl() }) { "Format de clé invalide." }
        if (clean.isEmpty()) {
            check(preferences.edit().remove(name).commit()) { "Impossible d'enregistrer les paramètres." }
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == 12)
            val encrypted = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            check(preferences.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
        } catch (error: Exception) {
            throw IllegalStateException("Impossible de protéger la clé avec Android Keystore.")
        }
    }

    private fun encryptionKey(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    companion object {
        const val PREFERENCES_NAME = "pocketai_online_settings"
        private const val KEY_ALIAS = "pocketai_online_credentials_v1"
        private val KEY_LOCK = Any()
    }
}
