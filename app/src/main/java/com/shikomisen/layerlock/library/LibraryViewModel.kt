package com.shikomisen.layerlock.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shikomisen.layerlock.canvas.SceneAssets
import com.shikomisen.layerlock.data.LayerLockGraph
import com.shikomisen.layerlock.data.SceneLibrary
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.SceneJson
import com.shikomisen.layerlock.scene.SceneValidator
import com.shikomisen.layerlock.scene.ScreenTarget
import com.shikomisen.layerlock.widgets.SceneWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val sceneRepository = LayerLockGraph.sceneRepository(application)
    private val entitlements = LayerLockGraph.entitlements(application)

    val assets = SceneAssets(application, viewModelScope)

    val library: StateFlow<SceneLibrary> = sceneRepository.library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SceneLibrary())

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            entitlements.status.collect { _isPro.value = it.isPro }
        }
        viewModelScope.launch {
            // Keep home-screen widgets in step with the library rather than waiting for the
            // platform's own half-hourly update.
            sceneRepository.library.collect { runCatching { SceneWidget.refresh(application) } }
        }
    }

    fun createScene(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val scene = sceneRepository.create()
            onCreated(scene.sceneId)
        }
    }

    fun duplicate(sceneId: String) {
        viewModelScope.launch { sceneRepository.duplicate(sceneId) }
    }

    fun delete(sceneId: String) {
        viewModelScope.launch { sceneRepository.delete(sceneId) }
    }

    fun setActive(sceneId: String, target: ScreenTarget) {
        viewModelScope.launch {
            sceneRepository.setActive(sceneId, target)
            _events.send(LibraryEvent.Message("Assigned to ${target.name.lowercase()}"))
        }
    }

    /** Import from a `.json` file — the §6 format, so scenes can be shared as plain text. */
    fun import(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }

            val scene = text?.let { SceneJson.decodeOrNull(it) }
            if (scene == null) {
                _events.send(LibraryEvent.Message("That file is not a LayerLock scene"))
                return@launch
            }

            val repaired = SceneValidator.sanitise(scene)
            val issues = SceneValidator.validate(repaired)
            if (issues.isNotEmpty()) {
                _events.send(LibraryEvent.Message("Could not import: ${issues.first().message}"))
                return@launch
            }

            val imported = sceneRepository.import(repaired)
            _events.send(LibraryEvent.Message("Imported \"${imported.name}\""))
        }
    }

    fun export(scene: Scene, uri: Uri) {
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(SceneJson.encode(scene).encodeToByteArray())
                    }
                }.isSuccess
            }
            _events.send(
                LibraryEvent.Message(if (written) "Scene exported" else "Could not write that file"),
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        assets.release()
    }
}

sealed interface LibraryEvent {
    data class Message(val text: String) : LibraryEvent
}
