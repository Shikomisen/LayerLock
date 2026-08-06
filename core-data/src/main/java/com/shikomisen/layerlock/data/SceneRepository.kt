package com.shikomisen.layerlock.data

import android.content.Context
import androidx.datastore.core.DataStore
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.ScenePresets
import com.shikomisen.layerlock.scene.ScreenTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Reads and writes the user's scene library. */
class SceneRepository(private val store: DataStore<SceneLibrary>) {

    constructor(context: Context) : this(createSceneLibraryStore(context.applicationContext))

    val library: Flow<SceneLibrary> = store.data

    val scenes: Flow<List<Scene>> = store.data.map { it.scenes }

    fun scene(sceneId: String): Flow<Scene?> = store.data.map { it.scene(sceneId) }

    /** The scene currently assigned to a surface — what the wallpaper engine and lock UI render. */
    fun activeScene(target: ScreenTarget): Flow<Scene?> = store.data.map { library ->
        when (target) {
            ScreenTarget.LOCK -> library.activeLockScene
            ScreenTarget.HOME -> library.activeHomeScene
            ScreenTarget.BOTH -> library.activeLockScene ?: library.activeHomeScene
        }
    }

    suspend fun snapshot(): SceneLibrary = store.data.first()

    suspend fun upsert(scene: Scene) {
        val stamped = scene.copy(updatedAt = System.currentTimeMillis())
        store.updateData { library ->
            val existing = library.scenes.indexOfFirst { it.sceneId == scene.sceneId }
            val scenes = if (existing >= 0) {
                library.scenes.toMutableList().apply { this[existing] = stamped }
            } else {
                library.scenes + stamped
            }
            library.copy(scenes = scenes)
        }
    }

    suspend fun delete(sceneId: String) {
        store.updateData { library ->
            library.copy(
                scenes = library.scenes.filterNot { it.sceneId == sceneId },
                activeLockSceneId = library.activeLockSceneId.takeIf { it != sceneId },
                activeHomeSceneId = library.activeHomeSceneId.takeIf { it != sceneId },
            )
        }
    }

    suspend fun duplicate(sceneId: String): Scene? {
        val source = snapshot().scene(sceneId) ?: return null
        val copy = source.copy(
            sceneId = UUID.randomUUID().toString(),
            name = "${source.name} copy",
            updatedAt = System.currentTimeMillis(),
        )
        upsert(copy)
        return copy
    }

    suspend fun create(name: String = "Untitled scene"): Scene {
        val scene = ScenePresets.blank(UUID.randomUUID().toString(), name)
        upsert(scene)
        return scene
    }

    /** Imports a scene from JSON, giving it a fresh id so it can never collide with an existing one. */
    suspend fun import(scene: Scene): Scene {
        val imported = scene.copy(sceneId = UUID.randomUUID().toString())
        upsert(imported)
        return imported
    }

    suspend fun setActive(sceneId: String?, target: ScreenTarget) {
        store.updateData { library ->
            when (target) {
                ScreenTarget.LOCK -> library.copy(activeLockSceneId = sceneId)
                ScreenTarget.HOME -> library.copy(activeHomeSceneId = sceneId)
                ScreenTarget.BOTH -> library.copy(
                    activeLockSceneId = sceneId,
                    activeHomeSceneId = sceneId,
                )
            }
        }
    }

    /** Seeds the starter presets on first run so the editor never opens onto an empty library. */
    suspend fun seedIfEmpty() {
        store.updateData { library ->
            if (library.scenes.isNotEmpty()) {
                library
            } else {
                val seeded = ScenePresets.all { UUID.randomUUID().toString() }
                library.copy(
                    scenes = seeded,
                    activeLockSceneId = seeded.firstOrNull()?.sceneId,
                )
            }
        }
    }
}
