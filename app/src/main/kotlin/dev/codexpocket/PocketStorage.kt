package dev.codexpocket

import dev.codexpocket.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

internal interface PocketStorage {
    fun put(name: String, value: String)
    fun get(name: String): String?
    fun keys(): Set<String> = emptySet()
    fun remove(name: String)
    fun profiles(): List<HostProfile> = get("profiles")?.let { json.decodeFromString<List<HostProfile>>(it) }.orEmpty()
    fun saveProfiles(profiles: List<HostProfile>) = put("profiles", json.encodeToString(profiles))
    fun credentials(id: String): Credentials = get("secret:$id")?.let { json.decodeFromString<Credentials>(it) } ?: Credentials()
    fun saveCredentials(id: String, credentials: Credentials) = put("secret:$id", json.encodeToString(credentials))
}
