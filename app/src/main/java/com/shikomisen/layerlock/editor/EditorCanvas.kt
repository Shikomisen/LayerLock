package com.shikomisen.layerlock.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.shikomisen.layerlock.canvas.LayerGeometry
import com.shikomisen.layerlock.canvas.SceneAssets
import com.shikomisen.layerlock.canvas.SceneCanvasRenderer
import com.shikomisen.layerlock.canvas.SceneMapping
import com.shikomisen.layerlock.canvas.SceneSurface
import com.shikomisen.layerlock.scene.Scene
import kotlin.math.hypot

/**
 * Zoom and pan of the editor viewport.
 *
 * A view concern only — none of it is ever written to the scene. It exists because the canvas has to
 * share the screen with the panels, which leaves the preview too small to place anything precisely
 * near the top or bottom edge.
 */
data class CanvasView(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val isDefault: Boolean get() = scale == 1f && offsetX == 0f && offsetY == 0f

    /**
     * Zooms about the centre of the viewport, for the toolbar buttons.
     *
     * The offset is scaled with it so whatever was under the middle of the frame stays there, and
     * zooming back out to 1x lands on a centred canvas rather than an arbitrary corner.
     */
    fun zoomedBy(factor: Float): CanvasView {
        val nextScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (nextScale == 1f) return CanvasView()
        val ratio = nextScale / scale
        return copy(scale = nextScale, offsetX = offsetX * ratio, offsetY = offsetY * ratio)
    }

    companion object {
        /**
         * Below 1x deliberately.
         *
         * A layer stretched past the edge of the scene has its handles outside the canvas frame,
         * and the frame clips hit-testing as well as drawing — so without the ability to shrink the
         * whole canvas inside its frame, those handles would be permanently ungrabbable and the only
         * way to undo the stretch would be the inspector.
         */
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 6f
    }
}

/**
 * The editable canvas.
 *
 * Gestures are interpreted in *scene* coordinates rather than view coordinates — a drag of 10 view
 * pixels becomes a scene-space delta through [SceneMapping], the same mapping the renderer uses. That
 * is what keeps dragging accurate on a preview that is not shown at 1:1 scale.
 *
 * Viewport zoom is applied with a `graphicsLayer` rather than by changing that mapping, which is
 * what keeps the two independent: Compose reports pointer positions in the node's own untransformed
 * space, so every coordinate calculation below is identical whether the user is zoomed in or not.
 */
@Composable
fun EditorCanvas(
    scene: Scene,
    assets: SceneAssets,
    state: EditorUiState,
    view: CanvasView,
    panMode: Boolean,
    /** True while the Background tab is open, when canvas drags reframe the background. */
    backgroundMode: Boolean,
    onSelect: (String?) -> Unit,
    onPanBackground: (dx: Float, dy: Float, zoomBy: Float) -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onTransform: (dx: Float, dy: Float, scaleBy: Float, rotateBy: Float) -> Unit,
    onResize: (LayerGeometry.Handle, sceneX: Float, sceneY: Float) -> Unit,
    onRotateTowards: (sceneX: Float, sceneY: Float) -> Unit,
    onView: (CanvasView) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Which dimension actually binds. `aspectRatio` derives the other one from whichever it is
        // told to match first, and getting that backwards is what cropped the top and bottom off a
        // portrait scene: width-first turned an 1080x2400 canvas into a box more than twice the
        // height of the slot it had to fit in, and the overflow was simply clipped away.
        val matchHeightFirst = maxHeight.value > 0f &&
            (maxWidth.value / maxHeight.value) > scene.canvas.aspectRatio

        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        val mapping = remember(scene, viewSize) {
            SceneMapping.of(scene, viewSize.width.toFloat(), viewSize.height.toFloat())
        }

        // Gesture blocks outlive the compositions that created them, so anything they read has to be
        // kept current explicitly — otherwise a resize drag would be measuring against the layer's
        // position from before the drag started.
        val currentScene by rememberUpdatedState(scene)
        val currentMapping by rememberUpdatedState(mapping)
        val currentState by rememberUpdatedState(state)
        val currentView by rememberUpdatedState(view)
        val currentSize by rememberUpdatedState(viewSize)
        val handleRadiusPx = with(LocalDensity.current) { HANDLE_TOUCH_RADIUS.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(scene.canvas.aspectRatio, matchHeightConstraintsFirst = matchHeightFirst)
                .clip(RoundedCornerShape(CANVAS_CORNER_RADIUS))
                .background(Color.Black),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = view.scale
                        scaleY = view.scale
                        translationX = view.offsetX
                        translationY = view.offsetY
                    }
                    .onSizeChanged { viewSize = it }
                    // Observed on the initial pass so a gesture is recorded for undo before it is
                    // interpreted, without consuming the event the other detectors need.
                    .pointerInput(scene.sceneId) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            onGestureStart()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                            onGestureEnd()
                        }
                    }
                    .pointerInput(scene, state.widgets, panMode, backgroundMode) {
                        if (panMode || backgroundMode) return@pointerInput
                        detectTapGestures { offset ->
                            val layer = LayerGeometry.layerAt(
                                scene = currentScene,
                                canvasX = currentMapping.viewToSceneX(offset.x),
                                canvasY = currentMapping.viewToSceneY(offset.y),
                                assets = assets,
                                widgets = currentState.widgets,
                                timeMillis = System.currentTimeMillis(),
                                touchSlop = TOUCH_SLOP_SCENE_PX,
                            )
                            onSelect(layer?.id)
                        }
                    }
                    .pointerInput(scene.sceneId, state.selectedLayerId, panMode, backgroundMode) {
                        detectTransformGestures(panZoomLock = false) { _, pan, zoom, rotation ->
                            // Pan mode hands the whole canvas over to the viewport, including
                            // one-finger drags. Otherwise a pinch scales the selected layer — and
                            // with nothing selected there is no layer to scale, so it falls through
                            // to the viewport there too.
                            if (backgroundMode && !panMode) {
                                onPanBackground(
                                    currentMapping.viewToSceneDistance(pan.x),
                                    currentMapping.viewToSceneDistance(pan.y),
                                    zoom,
                                )
                            } else if (panMode || currentState.selectedLayerId == null) {
                                onView(currentView.panZoomed(pan, zoom, currentSize))
                            } else {
                                onTransform(
                                    currentMapping.viewToSceneDistance(pan.x),
                                    currentMapping.viewToSceneDistance(pan.y),
                                    zoom,
                                    rotation,
                                )
                            }
                        }
                    }
                    // Declared last so it is the innermost detector and sees the down first. A grab
                    // that lands on a handle is consumed here, which is what stops the tap detector
                    // above from treating it as a selection change.
                    .pointerInput(scene.sceneId, state.selectedLayerId, panMode, backgroundMode) {
                        if (panMode || backgroundMode) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)

                            // Checked first: it floats clear of the box, so it can never be the
                            // handle the user meant to grab when a resize handle is also in range.
                            val rotationHandle = selectedRotationHandle(
                                currentScene,
                                currentState,
                                assets,
                                currentMapping,
                            )
                            if (rotationHandle != null &&
                                hypot(
                                    down.position.x - rotationHandle.first,
                                    down.position.y - rotationHandle.second,
                                ) <= handleRadiusPx
                            ) {
                                down.consume()
                                drag(down.id) { change ->
                                    change.consume()
                                    onRotateTowards(
                                        currentMapping.viewToSceneX(change.position.x),
                                        currentMapping.viewToSceneY(change.position.y),
                                    )
                                }
                                return@awaitEachGesture
                            }

                            val grabbed = selectedHandles(
                                currentScene,
                                currentState,
                                assets,
                                currentMapping,
                            ).filter { (_, position) ->
                                hypot(
                                    down.position.x - position.first,
                                    down.position.y - position.second,
                                ) <= handleRadiusPx
                            }.minByOrNull { (_, position) ->
                                hypot(
                                    down.position.x - position.first,
                                    down.position.y - position.second,
                                )
                            }?.first ?: return@awaitEachGesture

                            down.consume()
                            drag(down.id) { change ->
                                change.consume()
                                onResize(
                                    grabbed,
                                    currentMapping.viewToSceneX(change.position.x),
                                    currentMapping.viewToSceneY(change.position.y),
                                )
                            }
                        }
                    },
            ) {
                SceneSurface(
                    scene = scene,
                    assets = assets,
                    modifier = Modifier.fillMaxSize(),
                    widgets = state.widgets,
                    editor = SceneCanvasRenderer.EditorOverlay(
                        // Layer handles are inert while the background is being reframed, so
                        // showing them would only invite drags that do something else.
                        selectedLayerId = state.selectedLayerId.takeUnless { backgroundMode },
                        showGrid = state.showGrid,
                        showBounds = state.showBounds,
                        gridSize = scene.gridSize,
                        guides = state.activeGuides,
                    ),
                    playVideo = state.previewPlaying,
                    watermark = !state.isPro,
                )
            }

            // Only once zoomed out. At 1x the frame edge already is the boundary, and past it the
            // boundary is off-screen — in between is the only time it tells the user anything.
            if (view.scale < 1f) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val width = size.width * view.scale
                    val height = size.height * view.scale
                    drawRect(
                        color = RENDER_BOUNDS_COLOR,
                        topLeft = Offset(
                            (size.width - width) / 2f + view.offsetX,
                            (size.height - height) / 2f + view.offsetY,
                        ),
                        size = Size(width, height),
                        style = Stroke(
                            width = RENDER_BOUNDS_STROKE.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(
                                    RENDER_BOUNDS_DASH.toPx(),
                                    RENDER_BOUNDS_DASH.toPx(),
                                ),
                            ),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Applies a viewport pinch, keeping the canvas from being pushed off its own frame.
 *
 * Pan is multiplied by the scale because `graphicsLayer` translation happens in the parent's space
 * while the gesture is reported in the child's, and the clamp is what stops a stray two-finger drag
 * from leaving an empty black rectangle with the scene somewhere off-screen.
 */
private fun CanvasView.panZoomed(pan: Offset, zoom: Float, size: IntSize): CanvasView {
    val nextScale = (scale * zoom).coerceIn(CanvasView.MIN_SCALE, CanvasView.MAX_SCALE)
    // Zoomed out there is no hidden content to pan to, and the bound would go negative — which
    // `coerceIn` treats as an error rather than an empty range.
    val maxX = (size.width * (nextScale - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (size.height * (nextScale - 1f) / 2f).coerceAtLeast(0f)
    return CanvasView(
        scale = nextScale,
        offsetX = (offsetX + pan.x * nextScale).coerceIn(-maxX, maxX),
        offsetY = (offsetY + pan.y * nextScale).coerceIn(-maxY, maxY),
    )
}

/** The rotation handle of the selected layer, in view pixels. */
private fun selectedRotationHandle(
    scene: Scene,
    state: EditorUiState,
    assets: SceneAssets,
    mapping: SceneMapping,
): Pair<Float, Float>? {
    val layer = scene.layers.firstOrNull { it.id == state.selectedLayerId } ?: return null
    val point = LayerGeometry.rotationHandlePosition(
        layer,
        scene,
        assets,
        state.widgets,
        System.currentTimeMillis(),
    )
    return mapping.sceneToViewX(point.x) to mapping.sceneToViewY(point.y)
}

/**
 * The eight resize handles of the selection box, in view pixels.
 *
 * Positions come from [LayerGeometry.handlePositions] — the same function the renderer draws from —
 * so a handle is grabbable exactly where it is painted, rotation included, rather than at the corner
 * of some invisible upright box.
 */
private fun selectedHandles(
    scene: Scene,
    state: EditorUiState,
    assets: SceneAssets,
    mapping: SceneMapping,
): List<Pair<LayerGeometry.Handle, Pair<Float, Float>>> {
    val layer = scene.layers.firstOrNull { it.id == state.selectedLayerId } ?: return emptyList()
    return LayerGeometry.handlePositions(
        layer,
        scene,
        assets,
        state.widgets,
        System.currentTimeMillis(),
    ).map { (handle, point) ->
        handle to (mapping.sceneToViewX(point.x) to mapping.sceneToViewY(point.y))
    }
}

/** Extra grab radius so small layers stay tappable. Scene px, not view px. */
private const val TOUCH_SLOP_SCENE_PX = 24f

/**
 * The dashed outline of what actually gets rendered, shown while zoomed out.
 *
 * A layer's centre may sit outside the scene and a stretched one can extend well past it, so once
 * the canvas is small enough to leave margin inside its frame, that overhang becomes visible with
 * nothing to say it will be cropped away on the real wallpaper. Drawn outside the zoom
 * `graphicsLayer` on purpose: the dashes stay the same size on screen at any zoom, which is what
 * keeps them reading as an annotation rather than as part of the scene.
 *
 * Square-cornered, unlike the frame — the rounding is editor decoration, the render really is a
 * rectangle.
 */
private val RENDER_BOUNDS_COLOR = Color.White.copy(alpha = 0.7f)
private val RENDER_BOUNDS_STROKE = 1.5.dp
private val RENDER_BOUNDS_DASH = 6.dp

/**
 * Finger-sized, rather than the radius the handle is drawn at — 14 scene px is far too small.
 *
 * Eight handles now sit around a selection instead of four, so this is also what decides how small a
 * layer can get before its handles overlap. The nearest one wins, which keeps that survivable.
 */
private val HANDLE_TOUCH_RADIUS = 22.dp

private val CANVAS_CORNER_RADIUS = androidx.compose.ui.unit.Dp(18f)
