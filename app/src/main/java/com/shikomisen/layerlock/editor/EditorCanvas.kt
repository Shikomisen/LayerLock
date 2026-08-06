package com.shikomisen.layerlock.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.shikomisen.layerlock.canvas.LayerGeometry
import com.shikomisen.layerlock.canvas.SceneAssets
import com.shikomisen.layerlock.canvas.SceneCanvasRenderer
import com.shikomisen.layerlock.canvas.SceneMapping
import com.shikomisen.layerlock.canvas.SceneSurface
import com.shikomisen.layerlock.canvas.WidgetSnapshot
import com.shikomisen.layerlock.scene.Scene

/**
 * The editable canvas.
 *
 * Gestures are interpreted in *scene* coordinates rather than view coordinates — a drag of 10 view
 * pixels becomes a scene-space delta through [SceneMapping], the same mapping the renderer uses. That
 * is what keeps dragging accurate on a preview that is not shown at 1:1 scale.
 */
@Composable
fun EditorCanvas(
    scene: Scene,
    assets: SceneAssets,
    state: EditorUiState,
    onSelect: (String?) -> Unit,
    onGestureStart: () -> Unit,
    onTransform: (dx: Float, dy: Float, scaleBy: Float, rotateBy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        val mapping = remember(scene, viewSize) {
            SceneMapping.of(scene, viewSize.width.toFloat(), viewSize.height.toFloat())
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(scene.canvas.aspectRatio)
                .clip(RoundedCornerShape(CANVAS_CORNER_RADIUS))
                .background(Color.Black)
                .onSizeChanged { viewSize = it }
                // Observed on the initial pass so a gesture is recorded for undo before it is
                // interpreted, without consuming the event the transform detector needs.
                .pointerInput(scene.sceneId) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        onGestureStart()
                    }
                }
                .pointerInput(scene, state.widgets) {
                    detectTapGestures { offset ->
                        val layer = LayerGeometry.layerAt(
                            scene = scene,
                            canvasX = mapping.viewToSceneX(offset.x),
                            canvasY = mapping.viewToSceneY(offset.y),
                            assets = assets,
                            widgets = state.widgets,
                            timeMillis = System.currentTimeMillis(),
                            touchSlop = TOUCH_SLOP_SCENE_PX,
                        )
                        onSelect(layer?.id)
                    }
                }
                .pointerInput(scene.sceneId, state.selectedLayerId) {
                    detectTransformGestures(panZoomLock = false) { _, pan, zoom, rotation ->
                        if (state.selectedLayerId == null) return@detectTransformGestures
                        onTransform(
                            mapping.viewToSceneDistance(pan.x),
                            mapping.viewToSceneDistance(pan.y),
                            zoom,
                            rotation,
                        )
                    }
                },
        ) {
            SceneSurface(
                scene = scene,
                assets = assets,
                modifier = Modifier.fillMaxSize(),
                widgets = state.widgets,
                editor = SceneCanvasRenderer.EditorOverlay(
                    selectedLayerId = state.selectedLayerId,
                    showGrid = state.showGrid,
                    showBounds = state.showBounds,
                    gridSize = scene.gridSize,
                ),
                playVideo = state.previewPlaying,
                watermark = !state.isPro,
            )
        }
    }
}

/** Extra grab radius so small layers stay tappable. Scene px, not view px. */
private const val TOUCH_SLOP_SCENE_PX = 24f
private val CANVAS_CORNER_RADIUS = androidx.compose.ui.unit.Dp(18f)
