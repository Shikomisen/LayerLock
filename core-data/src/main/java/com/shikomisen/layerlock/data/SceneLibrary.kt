package com.shikomisen.layerlock.data

import com.shikomisen.layerlock.scene.Scene
import kotlinx.serialization.Serializable

/**
 * Everything the app has saved: the user's scenes, plus which of them is currently assigned to each
 * surface.
 *
 * The wallpaper engine, the lock Activity and the Glance widgets all read this same object, which is
 * why it lives below them in the module graph rather than inside `:app`.
 */
@Serializable
data class SceneLibrary(
    val scenes: List<Scene> = emptyList(),
    val activeLockSceneId: String? = null,
    val activeHomeSceneId: String? = null,
    /** Bumped when the on-disk shape changes, so a future build can migrate rather than guess. */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    fun scene(sceneId: String?): Scene? = sceneId?.let { id -> scenes.firstOrNull { it.sceneId == id } }

    val activeLockScene: Scene? get() = scene(activeLockSceneId)
    val activeHomeScene: Scene? get() = scene(activeHomeSceneId)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
