package com.shikomisen.layerlock.scene

import kotlinx.serialization.json.Json

/**
 * Canonical JSON encoding for scenes — the import/export format from §6, and the on-disk format
 * used by the scene library.
 *
 * [ignoreUnknownKeys] is deliberate: a scene exported by a newer build of the app should still open
 * in an older one, minus whatever it does not understand, rather than failing outright.
 */
object SceneJson {

    val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        isLenient = true
        explicitNulls = false
    }

    fun encode(scene: Scene): String = format.encodeToString(Scene.serializer(), scene)

    fun decode(text: String): Scene = format.decodeFromString(Scene.serializer(), text)

    /** Import path for user-supplied files, where malformed input is expected rather than a bug. */
    fun decodeOrNull(text: String): Scene? = runCatching { decode(text) }.getOrNull()
}
