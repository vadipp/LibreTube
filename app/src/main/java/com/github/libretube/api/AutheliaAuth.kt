package com.github.libretube.api

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.github.libretube.LibreTubeApp
import com.github.libretube.helpers.PreferenceHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-encrypted cookie persistence, excluded from Android cloud/device backups. */
object AutheliaAuth {
    private const val KEY_ALIAS = "authelia_session"
    private val file get() = File(LibreTubeApp.instance.noBackupFilesDir, "authelia-session")

    val session: AutheliaSession by lazy {
        AutheliaSession(read(), ::write, updatePipedToken = PreferenceHelper::setToken)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun read(): AutheliaSession.Snapshot? = runCatching {
        if (!file.exists()) return null
        val envelope = JSONObject(file.readText())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        val data = JSONObject(String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
        fun strings(name: String): List<String> = data.getJSONArray(name).let { array ->
            (0 until array.length()).map(array::getString)
        }
        AutheliaSession.Snapshot(data.getString("api"), data.getString("portal"), strings("origins").toSet(), strings("cookies"),
            data.optString("username").takeIf { it.isNotEmpty() }, data.optString("password").takeIf { it.isNotEmpty() })
    }.getOrElse {
        file.delete()
        null
    }

    private fun write(snapshot: AutheliaSession.Snapshot?) {
        if (snapshot == null) {
            file.delete()
            return
        }
        val data = JSONObject().put("api", snapshot.api).put("portal", snapshot.portal)
            .put("origins", JSONArray(snapshot.origins.toList())).put("cookies", JSONArray(snapshot.cookies))
            .put("username", snapshot.username).put("password", snapshot.password)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val envelope = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(envelope.toString())
        check(temporary.renameTo(file)) { "Unable to save instance authentication session" }
    }
}
