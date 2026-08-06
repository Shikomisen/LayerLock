package com.shikomisen.layerlock.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * DataStore wiring for the two files the app persists.
 *
 * DataStore is used rather than raw file writes for one specific property: its writes are atomic and
 * serialised through a single actor, so a wallpaper engine reading the library while the editor
 * saves to it can never observe a half-written scene.
 */
internal val persistenceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal object SceneLibrarySerializer : Serializer<SceneLibrary> {
    override val defaultValue = SceneLibrary()

    override suspend fun readFrom(input: InputStream): SceneLibrary = try {
        persistenceJson.decodeFromString(
            SceneLibrary.serializer(),
            input.readBytes().decodeToString(),
        )
    } catch (e: SerializationException) {
        throw CorruptionException("Scene library is not readable JSON", e)
    }

    override suspend fun writeTo(t: SceneLibrary, output: OutputStream) {
        output.write(persistenceJson.encodeToString(SceneLibrary.serializer(), t).encodeToByteArray())
    }
}

internal object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        persistenceJson.decodeFromString(
            AppSettings.serializer(),
            input.readBytes().decodeToString(),
        )
    } catch (e: SerializationException) {
        throw CorruptionException("Settings are not readable JSON", e)
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(persistenceJson.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}

internal fun createSceneLibraryStore(context: Context): DataStore<SceneLibrary> =
    DataStoreFactory.create(
        serializer = SceneLibrarySerializer,
        // A corrupt library should cost the user their scenes, not the ability to open the app.
        corruptionHandler = ReplaceFileCorruptionHandler { SceneLibrary() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.dataStoreFile("scene-library.json") },
    )

internal fun createSettingsStore(context: Context): DataStore<AppSettings> =
    DataStoreFactory.create(
        serializer = AppSettingsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.dataStoreFile("settings.json") },
    )
