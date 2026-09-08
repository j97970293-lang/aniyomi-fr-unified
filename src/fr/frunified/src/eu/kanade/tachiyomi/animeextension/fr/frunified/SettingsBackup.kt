package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import org.json.JSONObject

/** Sauvegarde portable des réglages FR Unifié (sans caches ni date de restauration). */
object SettingsBackup {
    private val excluded = setOf(
        FrSettings.KEY_STREMIO_CATALOG_CACHE,
        FrSettings.KEY_NUVIO_LAST_UPDATE,
        FrSettings.KEY_STREMIO_LAST_UPDATE,
        FrSettings.KEY_BACKUP_LAST_RESTORE,
    )

    fun export(preferences: SharedPreferences): String = JSONObject().apply {
        put("format", "fr-unified-backup")
        put("version", 1)
        put("createdAt", System.currentTimeMillis())
        put(
            "settings",
            JSONObject().apply {
                preferences.all.toSortedMap().forEach { (key, value) ->
                    if (key !in excluded) {
                        when (value) {
                            is String, is Boolean, is Int, is Long, is Float, is Double -> put(key, value)
                            is Set<*> -> put(key, value.filterIsInstance<String>().joinToString("\n"))
                        }
                    }
                }
            },
        )
    }.toString(2)

    fun restore(preferences: SharedPreferences, raw: String): Int {
        val root = JSONObject(raw)
        require(root.optString("format") == "fr-unified-backup") { "Format de sauvegarde inconnu" }
        val settings = root.getJSONObject("settings")
        val editor = preferences.edit()
        var count = 0
        settings.keys().forEach { key ->
            if (key in excluded) return@forEach
            when (val value = settings.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Number -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                else -> return@forEach
            }
            count++
        }
        check(editor.commit()) { "Impossible d'enregistrer les réglages" }
        FrDns.clearCache()
        return count
    }

    suspend fun autoRestoreIfDue(preferences: SharedPreferences, now: Long = System.currentTimeMillis()): Int? {
        if (!FrSettings.backupAutoRestore || !FrSettings.backupUrl.startsWith("https://")) return null
        val last = FrSettings.backupLastRestore
        if (last in 1..now && now - last < FrSettings.DAILY_INTERVAL_MS) return null
        val count = restore(preferences, FrRuntime.getText(FrSettings.backupUrl))
        FrSettings.saveBackupLastRestore(now)
        return count
    }
}
