package com.oaurumo.davsrv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Credential storage. Users are serialized as a JSON array inside DataStore, so
 * there is a single atomic write per change and no database dependency.
 */
class UserStore(private val context: Context) {

    private val key = stringPreferencesKey("users_json")

    val flow: Flow<List<AuthUser>> = context.usersDataStore.data.map { parse(it[key]) }

    private fun parse(json: String?): List<AuthUser> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("u")
                val salt = o.optString("s")
                val hash = o.optString("h")
                if (name.isBlank() || salt.isBlank() || hash.isBlank()) null
                else AuthUser(name, salt, hash, o.optBoolean("ro", false))
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(users: List<AuthUser>): String {
        val arr = JSONArray()
        users.forEach { u ->
            arr.put(
                JSONObject()
                    .put("u", u.username)
                    .put("s", u.salt)
                    .put("h", u.hash)
                    .put("ro", u.readOnly)
            )
        }
        return arr.toString()
    }

    suspend fun upsert(user: AuthUser) = context.usersDataStore.edit { prefs ->
        val current = parse(prefs[key]).toMutableList()
        val idx = current.indexOfFirst { it.username.equals(user.username, ignoreCase = true) }
        if (idx >= 0) current[idx] = user else current.add(user)
        prefs[key] = encode(current)
    }

    suspend fun delete(username: String) = context.usersDataStore.edit { prefs ->
        prefs[key] = encode(parse(prefs[key]).filterNot { it.username == username })
    }

    suspend fun replaceAll(users: List<AuthUser>) = context.usersDataStore.edit { prefs ->
        prefs[key] = encode(users)
    }

    /** Seeds the default root account the first time the app runs. */
    suspend fun ensureDefault() = context.usersDataStore.edit { prefs ->
        if (parse(prefs[key]).isNotEmpty()) return@edit
        val salt = Passwords.newSalt()
        prefs[key] = encode(
            listOf(
                AuthUser(
                    username = "root",
                    salt = salt,
                    hash = Passwords.hash("1234", salt),
                    readOnly = false
                )
            )
        )
    }
}
