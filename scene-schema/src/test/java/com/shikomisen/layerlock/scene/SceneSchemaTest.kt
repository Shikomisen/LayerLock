package com.shikomisen.layerlock.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneSchemaTest {

    /** The exact JSON from §6 of the concept doc, which the schema is contractually required to read. */
    private val specJson = """
        {
          "sceneId": "3f1a9e2c-uuid",
          "name": "Sunset Lock",
          "target": "lock",
          "canvas": { "width": 1080, "height": 2400 },
          "background": {
            "type": "video",
            "sourceUri": "content://media/external/video/123",
            "loop": true,
            "muted": true
          },
          "layers": [
            {
              "id": "layer-1",
              "type": "clock",
              "z": 10,
              "transform": { "x": 540, "y": 300, "scale": 1.4, "rotation": 0 },
              "style": { "fontFamily": "Poppins-Bold", "color": "#FFFFFF", "shadow": true }
            },
            {
              "id": "layer-2",
              "type": "cutout",
              "z": 20,
              "sourceUri": "content://media/external/images/456",
              "transform": { "x": 540, "y": 1400, "scale": 1.0, "rotation": 0 }
            },
            {
              "id": "layer-3",
              "type": "widget",
              "widgetKind": "weather",
              "z": 5,
              "transform": { "x": 180, "y": 2000, "scale": 1.0, "rotation": 0 },
              "gridSnapped": true
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses the scene JSON from the spec`() {
        val scene = SceneJson.decode(specJson)

        assertEquals("Sunset Lock", scene.name)
        assertEquals(ScreenTarget.LOCK, scene.target)
        assertEquals(1080, scene.canvas.width)
        assertEquals(BackgroundType.VIDEO, scene.background.type)
        assertEquals("content://media/external/video/123", scene.background.sourceUri)
        assertEquals(3, scene.layers.size)

        val clock = scene.layers.filterIsInstance<ClockLayer>().single()
        assertEquals("Poppins-Bold", clock.style.fontFamily)
        assertEquals(1.4f, clock.transform.scale, 0.001f)
        assertTrue(clock.style.shadow)

        val widget = scene.layers.filterIsInstance<WidgetLayer>().single()
        assertEquals(WidgetKind.WEATHER, widget.widgetKind)
        assertTrue(widget.gridSnapped)
    }

    @Test
    fun `higher z renders in front`() {
        val scene = SceneJson.decode(specJson)
        assertEquals(listOf("layer-3", "layer-1", "layer-2"), scene.drawOrder.map { it.id })
    }

    @Test
    fun `round trips through JSON without losing anything`() {
        val original = ScenePresets.stacked("scene-1")
        val restored = SceneJson.decode(SceneJson.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun `unknown keys from a newer build are ignored rather than failing the import`() {
        val withFutureField = specJson.replace(
            "\"name\": \"Sunset Lock\",",
            "\"name\": \"Sunset Lock\", \"parallaxDepth\": 3,",
        )
        assertNotNull(SceneJson.decodeOrNull(withFutureField))
    }

    @Test
    fun `malformed JSON imports as null rather than throwing`() {
        assertNull(SceneJson.decodeOrNull("{ not a scene"))
    }

    @Test
    fun `parses every accepted colour form`() {
        assertEquals(0xFFFFFFFF.toInt(), ColorSpec.parse("#FFFFFF"))
        assertEquals(0x80FF0000.toInt(), ColorSpec.parse("#80FF0000"))
        assertEquals(0xFFAABBCC.toInt(), ColorSpec.parse("#ABC"))
        assertEquals(0x11223344, ColorSpec.parse("#1234"))
        assertEquals(0xFF00FF00.toInt(), ColorSpec.parse("not a colour", fallback = 0xFF00FF00.toInt()))
        assertEquals("#80FF0000", ColorSpec.format(0x80FF0000.toInt()))
    }
}
