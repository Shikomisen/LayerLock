package com.shikomisen.layerlock.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneOpsTest {

    private fun scene() = ScenePresets.midnight("scene-1")

    @Test
    fun `added layers land on top of the stack`() {
        val added = SceneOps.addLayer(
            scene(),
            TextLayer(id = "new", transform = Transform(0f, 0f)),
        )
        assertEquals("new", added.drawOrder.last().id)
    }

    @Test
    fun `reorder renumbers z so the stored order matches the layer panel`() {
        val original = scene()
        // The panel lists front-to-back, so index 0 is the frontmost layer.
        val frontId = original.drawOrder.last().id
        val reordered = SceneOps.reorder(original, fromIndex = 0, toIndex = 2)

        assertEquals(frontId, reordered.drawOrder.first().id)
        assertEquals(original.layers.size, reordered.layers.size)
        assertTrue(reordered.layers.map { it.z }.distinct().size == reordered.layers.size)
    }

    @Test
    fun `bring to front and send to back are inverses`() {
        val original = scene()
        val id = original.drawOrder.first().id
        val fronted = SceneOps.bringToFront(original, id)
        assertEquals(id, fronted.drawOrder.last().id)

        val backed = SceneOps.sendToBack(fronted, id)
        assertEquals(id, backed.drawOrder.first().id)
    }

    @Test
    fun `snapping quantises position but leaves it an ordinary absolute coordinate`() {
        val original = scene().copy(gridSize = 50)
        val moved = SceneOps.transformLayer(
            original,
            layerId = "clock",
            dx = 13f,
            dy = 27f,
            snapToGrid = true,
        )
        val clock = SceneOps.findLayer(moved, "clock")!!

        assertEquals(0f, clock.transform.x % 50f, 0.001f)
        assertEquals(0f, clock.transform.y % 50f, 0.001f)
        assertTrue(clock.gridSnapped)
    }

    @Test
    fun `free placement keeps sub-grid precision`() {
        val moved = SceneOps.transformLayer(scene(), layerId = "clock", dx = 13f, snapToGrid = false)
        val clock = SceneOps.findLayer(moved, "clock")!!

        assertEquals(553f, clock.transform.x, 0.001f)
        assertFalse(clock.gridSnapped)
    }

    @Test
    fun `scale is clamped to a usable range`() {
        val huge = SceneOps.transformLayer(scene(), layerId = "clock", scaleBy = 1000f)
        assertEquals(SceneOps.MAX_SCALE, SceneOps.findLayer(huge, "clock")!!.transform.scale, 0.001f)

        val tiny = SceneOps.transformLayer(scene(), layerId = "clock", scaleBy = 0.00001f)
        assertEquals(SceneOps.MIN_SCALE, SceneOps.findLayer(tiny, "clock")!!.transform.scale, 0.001f)
    }

    @Test
    fun `rotation wraps into 0-360`() {
        val rotated = SceneOps.transformLayer(scene(), layerId = "clock", rotateBy = -90f)
        assertEquals(270f, SceneOps.findLayer(rotated, "clock")!!.transform.rotation, 0.001f)
    }

    @Test
    fun `presets validate cleanly`() {
        assertTrue(SceneValidator.validate(ScenePresets.midnight("a")).isEmpty())
        assertTrue(SceneValidator.validate(ScenePresets.stacked("b")).isEmpty())
        assertTrue(SceneValidator.validate(ScenePresets.blank("c")).isEmpty())
    }

    @Test
    fun `sanitise repairs duplicate ids and out of range values`() {
        val broken = scene().copy(
            name = "  ",
            layers = listOf(
                TextLayer(id = "dup", transform = Transform(0f, 0f, scale = 900f)),
                TextLayer(id = "dup", transform = Transform(0f, 0f), opacity = 4f),
            ),
        )
        val fixed = SceneValidator.sanitise(broken)

        assertEquals(2, fixed.layers.map { it.id }.distinct().size)
        assertEquals("Untitled scene", fixed.name)
        assertTrue(fixed.layers.all { it.opacity in 0f..1f })
        assertTrue(fixed.layers.all { it.transform.scale <= SceneOps.MAX_SCALE })
    }

    @Test
    fun `a video background makes the scene animated`() {
        val still = Scene(sceneId = "s", name = "s", layers = emptyList())
        assertFalse(still.isAnimated)

        val video = still.copy(
            background = Background(type = BackgroundType.VIDEO, sourceUri = "content://v"),
        )
        assertTrue(video.isAnimated)
    }
}
