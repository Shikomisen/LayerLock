package com.shikomisen.layerlock.scene

/**
 * Starter scenes.
 *
 * All of these use colour/gradient backgrounds rather than media URIs, so a fresh install has
 * something that renders correctly before the user has picked a single photo — and so they keep
 * rendering after a `content://` permission is revoked.
 */
object ScenePresets {

    fun blank(sceneId: String, name: String = "Untitled scene"): Scene = Scene(
        sceneId = sceneId,
        name = name,
        target = ScreenTarget.LOCK,
        background = Background(type = BackgroundType.GRADIENT),
        layers = listOf(
            ClockLayer(
                id = "clock",
                z = 20,
                transform = Transform(x = 540f, y = 620f),
                style = TextStyleSpec(fontSize = 190f, weight = 300, shadow = true),
            ),
            DateLayer(
                id = "date",
                z = 30,
                transform = Transform(x = 540f, y = 780f),
                style = TextStyleSpec(fontSize = 46f, weight = 400, shadow = true),
            ),
        ),
    )

    fun midnight(sceneId: String): Scene = Scene(
        sceneId = sceneId,
        name = "Midnight",
        target = ScreenTarget.LOCK,
        background = Background(
            type = BackgroundType.GRADIENT,
            color = "#FF05060B",
            colorEnd = "#FF1B2340",
            gradientAngle = 115f,
        ),
        layers = listOf(
            ClockLayer(
                id = "clock",
                z = 20,
                transform = Transform(x = 540f, y = 700f),
                style = TextStyleSpec(
                    fontFamily = "sans-serif-thin",
                    fontSize = 230f,
                    weight = 200,
                    letterSpacing = -0.02f,
                    shadow = true,
                ),
            ),
            DateLayer(
                id = "date",
                z = 30,
                transform = Transform(x = 540f, y = 880f),
                style = TextStyleSpec(fontSize = 44f, weight = 400, allCaps = true, letterSpacing = 0.18f),
            ),
            WidgetLayer(
                id = "battery",
                z = 40,
                transform = Transform(x = 540f, y = 2120f),
                gridSnapped = true,
                widgetKind = WidgetKind.BATTERY,
                style = TextStyleSpec(fontSize = 34f, weight = 500),
            ),
        ),
    )

    fun stacked(sceneId: String): Scene = Scene(
        sceneId = sceneId,
        name = "Stacked",
        target = ScreenTarget.BOTH,
        background = Background(
            type = BackgroundType.GRADIENT,
            color = "#FF2B1B3D",
            colorEnd = "#FFE0654A",
            gradientAngle = 70f,
            dim = 0.15f,
        ),
        layers = listOf(
            TextLayer(
                id = "headline",
                z = 10,
                transform = Transform(x = 540f, y = 480f, rotation = 352f),
                text = "GOOD\nMORNING",
                style = TextStyleSpec(
                    fontFamily = "sans-serif-black",
                    fontSize = 130f,
                    weight = 900,
                    allCaps = true,
                    lineHeightMultiplier = 0.95f,
                    color = "#33FFFFFF",
                ),
            ),
            ClockLayer(
                id = "clock",
                z = 20,
                transform = Transform(x = 540f, y = 1180f),
                style = TextStyleSpec(fontFamily = "sans-serif-medium", fontSize = 210f, weight = 500),
            ),
            DateLayer(
                id = "date",
                z = 30,
                transform = Transform(x = 540f, y = 1330f),
                style = TextStyleSpec(fontSize = 42f, weight = 400),
                pattern = "EEEE d MMMM",
            ),
        ),
    )

    fun all(idFactory: () -> String): List<Scene> = listOf(midnight(idFactory()), stacked(idFactory()))
}
