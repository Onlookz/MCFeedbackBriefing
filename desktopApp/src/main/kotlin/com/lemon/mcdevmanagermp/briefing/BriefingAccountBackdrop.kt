package com.lemon.mcdevmanagermp.briefing

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin

private val distantRidge = floatArrayOf(
    .54f, .54f, .50f, .46f, .40f, .37f, .31f, .31f, .36f, .36f, .42f, .47f, .47f, .52f, .56f,
    .58f, .58f, .55f, .53f, .48f, .45f, .39f, .39f, .34f, .34f, .38f, .44f, .45f, .50f, .48f,
)
private val middleRidge = floatArrayOf(
    .66f, .62f, .62f, .58f, .59f, .56f, .56f, .60f, .63f, .63f, .67f, .69f, .72f, .72f, .74f,
    .74f, .72f, .70f, .67f, .65f, .64f, .60f, .57f, .57f, .61f, .61f, .64f, .61f, .58f, .58f,
)
private val foregroundRidge = floatArrayOf(
    .78f, .79f, .77f, .79f, .81f, .80f, .83f, .85f, .85f, .87f, .88f, .89f, .90f, .91f, .90f,
    .90f, .91f, .89f, .89f, .88f, .87f, .85f, .85f, .83f, .82f, .79f, .80f, .79f, .77f, .75f,
)
private val pines = listOf(
    .018f to 16f, .047f to 24f, .087f to 17f, .132f to 20f, .157f to 13f, .214f to 27f, .260f to 16f,
    .313f to 12f, .692f to 14f, .736f to 19f, .777f to 13f, .824f to 23f, .862f to 15f, .898f to 26f,
    .937f to 17f, .981f to 22f,
)
private val lights = listOf(
    Offset(.076f, .42f), Offset(.108f, .70f), Offset(.175f, .56f), Offset(.243f, .76f), Offset(.29f, .50f),
    Offset(.713f, .69f), Offset(.751f, .46f), Offset(.836f, .74f), Offset(.90f, .53f), Offset(.948f, .67f),
)

@Composable
internal fun BriefingAccountBackdrop(modifier: Modifier, moving: Boolean) {
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < .20f
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val elapsed = frame - previousFrame
            if (elapsed >= 30_000_000L) {
                phase.floatValue = (phase.floatValue + elapsed / 25_000_000_000f) % 1f
                previousFrame = frame
            }
        }
    }

    Box(modifier.drawWithCache {
        val pixel = (size.minDimension / 220f).coerceIn(1f, 4f)
        val ink = if (dark) Color(0xFF111916) else Color(0xFF59645F)
        val mist = if (dark) Color(0xFFABC2B6) else Color(0xFFBCC9C3)
        val sky = Brush.verticalGradient(
            0f to lerp(background, ink, .28f),
            .60f to lerp(background, mist, .29f),
            1f to background,
        )
        val moon = Offset(size.width * .786f, size.height * .217f).snap(pixel)
        val moonHalo = Brush.radialGradient(
            colors = listOf(mist.copy(alpha = if (dark) .21f else .26f), Color.Transparent),
            center = moon,
            radius = pixel * 30f,
        )
        val moonDisk = pixelMoon(moon, pixel)
        val distant = ridge(size, distantRidge, pixel)
        val middle = ridge(size, middleRidge, pixel)
        val foreground = ridge(size, foregroundRidge, pixel)
        val trees = pineForest(size, pixel)
        val cloud = pixelCloud(pixel)
        val lowHaze = Brush.verticalGradient(
            0f to Color.Transparent,
            .68f to mist.copy(alpha = if (dark) .085f else .13f),
            .86f to Color.Transparent,
        )
        val distanceColor = lerp(background, if (dark) mist else ink, if (dark) .14f else .19f)
        val middleColor = lerp(background, ink, if (dark) .20f else .27f)
        val foregroundColor = lerp(background, ink, if (dark) .70f else .48f)

        onDrawBehind {
            val angle = phase.floatValue * (2f * PI.toFloat())
            drawRect(sky)
            drawRect(moonHalo)
            drawPath(moonDisk, mist.copy(alpha = if (dark) .52f else .80f))
            translate(size.width * (.088f + .050f * sin(angle)), size.height * .273f) {
                drawPath(cloud, mist.copy(alpha = .13f))
            }
            translate(size.width * (.69f + .042f * sin(angle + 1.1f)), size.height * .339f) {
                drawPath(cloud, mist.copy(alpha = .10f))
            }
            drawPath(distant, distanceColor)
            drawPath(middle, middleColor)
            drawRect(lowHaze)
            drawPath(foreground, foregroundColor)
            drawPath(trees, foregroundColor)
            lights.forEachIndexed { index, point ->
                val rhythm = angle + index * 1.67f
                val spot = Offset(
                    size.width * point.x + pixel * 3f * sin(rhythm + .8f),
                    size.height * point.y + pixel * 4f * sin(rhythm),
                ).snap(pixel)
                val opacity = .29f + .18f * sin(rhythm + 1.3f)
                drawRect(mist.copy(alpha = opacity), spot, Size(pixel, pixel))
            }
        }
    })
}

private fun ridge(size: Size, heights: FloatArray, pixel: Float): Path = Path().apply {
    moveTo(0f, size.height)
    heights.forEachIndexed { index, height ->
        val start = size.width * index / heights.size
        val end = size.width * (index + 1) / heights.size
        val y = round(size.height * height / pixel) * pixel
        lineTo(start, y)
        lineTo(end, y)
    }
    lineTo(size.width, size.height)
    close()
}

private fun pixelMoon(center: Offset, pixel: Float): Path = Path().apply {
    val rows = listOf(4, 6, 8, 10, 10, 10, 10, 8, 6, 4)
    rows.forEachIndexed { row, width ->
        val half = width * pixel / 2f
        val top = center.y + (row - rows.size / 2) * pixel
        addRect(Rect(center.x - half, top, center.x + half, top + pixel))
    }
}

private fun pixelCloud(pixel: Float): Path = Path().apply {
    addRect(Rect(0f, 3f * pixel, 32f * pixel, 5f * pixel))
    addRect(Rect(6f * pixel, pixel, 26f * pixel, 3f * pixel))
    addRect(Rect(12f * pixel, 0f, 22f * pixel, pixel))
    addRect(Rect(13f * pixel, 5f * pixel, 37f * pixel, 6f * pixel))
}

private fun pineForest(size: Size, pixel: Float): Path = Path().apply {
    pines.forEach { (position, height) ->
        val ground = foregroundRidge[(position * foregroundRidge.size).toInt()]
        val base = Offset(size.width * position, size.height * ground + 2f * pixel).snap(pixel)
        val top = base.y - height * pixel
        addRect(Rect(base.x - pixel, top, base.x + pixel, base.y))
        for (tier in 0..3) {
            val half = (tier + 2) * pixel
            val shoulder = top + round(height * (.14f + tier * .18f)) * pixel
            val hem = top + round(height * (.32f + tier * .18f)) * pixel
            addRect(Rect(base.x - half + pixel, shoulder, base.x + half - pixel, hem))
            addRect(Rect(base.x - half, hem - 2f * pixel, base.x + half, hem))
        }
    }
}

private fun Offset.snap(pixel: Float): Offset = Offset(round(x / pixel) * pixel, round(y / pixel) * pixel)
