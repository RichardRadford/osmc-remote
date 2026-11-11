package dev.osmcremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

data class DeviceConfig(
    val host: String = "",
    val port: Int = 22,
    val user: String = "osmc",
    val password: String = "",
)

/**
 * Remembers the last device. The password is kept in EncryptedSharedPreferences
 * (Android Keystore backed); if that isn't available it is never stored.
 */
class DeviceStore(context: Context) {
    private val plain: SharedPreferences =
        context.getSharedPreferences("osmc_remote", Context.MODE_PRIVATE)

    private val secure: SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            "osmc_remote_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    val canRememberPassword: Boolean get() = secure != null

    fun load(): Pair<DeviceConfig, Boolean> {
        val remember = plain.getBoolean("remember", true)
        val pw = if (remember) secure?.getString("password", null).orEmpty() else ""
        val cfg = DeviceConfig(
            host = plain.getString("host", "").orEmpty(),
            port = plain.getInt("port", 22),
            user = plain.getString("user", "osmc").orEmpty().ifBlank { "osmc" },
            password = pw,
        )
        return cfg to remember
    }

    fun save(cfg: DeviceConfig, rememberPassword: Boolean) {
        plain.edit()
            .putString("host", cfg.host.trim())
            .putInt("port", cfg.port)
            .putString("user", cfg.user.trim())
            .putBoolean("remember", rememberPassword)
            .apply()
        secure?.let { sp ->
            val ed = sp.edit()
            if (rememberPassword) ed.putString("password", cfg.password) else ed.remove("password")
            ed.apply()
        }
    }

    fun fingerprint(host: String, port: Int): String? =
        plain.getString(fpKey(host, port), null)

    fun saveFingerprint(host: String, port: Int, fp: String) {
        plain.edit().putString(fpKey(host, port), fp).apply()
    }

    private fun fpKey(host: String, port: Int) = "fp:" + host.trim().lowercase() + ":" + port
}
