package com.goveon.app.lighting

import android.content.Context
import java.security.KeyStore

/** App-private customization. Readings and active connections never survive a restart. */
internal class LocalStore(context: Context) {
    private val preferences = context.getSharedPreferences("goveon_local", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("goveon_credentials", Context.MODE_PRIVATE)
    private var lastSaved: String? = null
    fun load(): AppState {
        val raw = preferences.getString("state", "{}") ?: "{}"
        val state = LocalStateCodec.decode(raw)
        // Rewrite the old format once, dropping retired cloud records and metadata.
        if (state.error == null && !LocalStateCodec.isCurrent(raw)) save(state)
        return state
    }
    fun save(state: AppState) {
        val encoded = LocalStateCodec.encode(state)
        // RSSI, notifications and session transitions are runtime data, not customization edits.
        if (encoded != lastSaved) { preferences.edit().putString("state", encoded).apply(); lastSaved = encoded }
    }

    /** Upgrade cleanup only: never read/decrypt a retired developer credential. Runs on IO. */
    fun removeLegacyCredentials() {
        if (preferences.getBoolean("legacy_credentials_removed", false)) return
        try {
            check(legacy.edit().clear().commit()) { "Credential cleanup could not be persisted." }
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val retiredAlias = "goveon.developer-key.v1"
            if (keys.containsAlias(retiredAlias)) keys.deleteEntry(retiredAlias)
            check(preferences.edit().putBoolean("legacy_credentials_removed", true).commit()) { "Upgrade cleanup could not be persisted." }
        } catch (error: Exception) {
            Diagnostics.failure("Upgrade cleanup", error)
            // Retry on the next launch; a Keystore failure must not block local control.
        }
    }
}
