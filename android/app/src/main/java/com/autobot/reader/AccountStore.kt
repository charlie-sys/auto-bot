package com.autobot.reader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The account session is encrypted with a non-exportable Android Keystore key. */
class AccountStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    fun user(): JSONObject? = runCatching { JSONObject(prefs.getString("user", "")!!) }.getOrNull()
    fun id(): Int = user()?.optInt("id") ?: 0
    fun role(): String = user()?.optString("role").orEmpty()
    fun token(): String = runCatching {
        val configured = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("base_url", "").orEmpty().trim().trimEnd('/')
        if (prefs.getString("server_url", "") != configured) return ""
        val encoded = prefs.getString("session", "").orEmpty()
        if (encoded.isBlank()) return ""
        val pieces = encoded.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")

    fun save(response: JSONObject, serverUrl: String? = null) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(response.getString("access_token").toByteArray(Charsets.UTF_8))
        val address = serverUrl ?: prefs.getString("server_url", "").orEmpty()
        val instance = response.getString("server_id")
        require(instance.matches(Regex("[a-f0-9]{32}"))) { "服务端身份无效" }
        prefs.edit().putString("session", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putString("user", response.getJSONObject("user").toString()).putString("server_url", address.trim().trimEnd('/'))
            .putString("server_id", instance).apply()
    }

    fun updateUser(value: JSONObject) { prefs.edit().putString("user", value.toString()).apply() }
    fun clear() { prefs.edit().clear().apply() }
    private fun namespace() = prefs.getString("server_id", "none") + "_u${id()}"
    fun history() = context.getSharedPreferences("history_${namespace()}", Context.MODE_PRIVATE)
    fun photoFolder() = File(context.filesDir, "photos/${namespace()}")
    fun previewFolder() = File(context.cacheDir, "question-previews/${namespace()}")

    /** Pre-account history belongs only to the original, initial administrator. */
    fun migrateLegacyHistory() {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (user()?.optString("username") != "admin" || role() != "superadmin" || settings.getBoolean("history_account_migrated", false)) return
        val old = JSONArray(settings.getString("history", "[]"))
        val folder = photoFolder().apply { mkdirs() }
        val root = File(context.filesDir, "photos").canonicalFile
        val copied = mutableListOf<File>()
        for (i in 0 until old.length()) {
            val entry = old.getJSONObject(i)
            val path = entry.optString("photo")
            if (path.isNotBlank()) {
                val source = File(path).canonicalFile
                if (source.parentFile == root && source.isFile) {
                    val target = File(folder, "legacy_" + source.name)
                    source.copyTo(target, overwrite = true)
                    entry.put("photo", target.absolutePath)
                    copied.add(source)
                } else entry.remove("photo")
            }
        }
        if (old.length() > 0) check(history().edit().putString("history", old.toString()).commit()) { "无法保存账号历史，已保留旧数据" }
        check(settings.edit().remove("history").putBoolean("history_account_migrated", true).commit()) { "无法更新迁移状态，已保留旧照片" }
        copied.distinct().forEach { it.delete() }
    }

    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    companion object { private const val ALIAS = "reader_account_session_v1"; private val lock = Any() }
}
