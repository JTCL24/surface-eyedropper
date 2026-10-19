package com.jtcl24.eyedropper

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/** What every glass panel needs to draw the camera image "behind" itself. */
class GlassEnv(val frame: State<Frame?>, val root: State<IntSize>, val bright: Boolean)

val LocalGlass = staticCompositionLocalOf<GlassEnv> { error("GlassEnv not provided") }

private val SATURATE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.85f) })
private val EMPTY_BG = listOf(Color(0xFF2B2F3A), Color(0xFF0C0D12), Color.Black)

/** Draw the backdrop (camera frame, or the idle gradient) as it appears full-screen, shifted by [origin]. */
fun DrawScope.drawBackdrop(frame: Frame?, origin: Offset, root: IntSize, glass: Boolean) {
    translate(-origin.x, -origin.y) {
        if (frame == null) {
            drawRect(
                Brush.radialGradient(
                    0f to EMPTY_BG[0], 0.6f to EMPTY_BG[1], 1f to EMPTY_BG[2],
                    center = Offset(root.width * 0.5f, root.height * 0.2f),
                    radius = root.height * 0.8f,
                ),
                size = androidx.compose.ui.geometry.Size(root.width.toFloat(), root.height.toFloat()),
            )
        } else {
            drawImage(
                frame.bitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(frame.bitmap.width, frame.bitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = root,
                filterQuality = FilterQuality.Low,
                colorFilter = if (glass) SATURATE else null,
            )
        }
        if (glass) {
            // match the 18% dim the reticle overlay applies to the camera layer
            drawRect(Color.Black.copy(alpha = 0.18f), size = androidx.compose.ui.geometry.Size(root.width.toFloat(), root.height.toFloat()))
        }
    }
}

/**
 * Liquid-glass surface: blurred + saturated copy of the camera image behind it,
 * a translucent tint, a specular sheen and a gradient rim.
 */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(30.dp),
    backdrop: Boolean = true,
    tint: Brush? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val env = LocalGlass.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "press")

    val tintTop = if (env.bright) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.10f)
    val tintBottom = if (env.bright) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f)
    val edge = if (env.bright) Color.White.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.34f)

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .then(
                if (onClick != null) Modifier.clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
                else Modifier
            ),
    ) {
        if (backdrop) {
            Canvas(Modifier.matchParentSize().blur(24.dp, BlurredEdgeTreatment.Unbounded)) {
                val pad = 72.dp.toPx()
                clipRect(-pad, -pad, size.width + pad, size.height + pad) {
                    drawBackdrop(env.frame.value, origin, env.root.value, glass = true)
                }
            }
        }
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(tintTop, tintBottom))))
        if (tint != null) Box(Modifier.matchParentSize().background(tint))
        Box(
            Modifier.matchParentSize().background(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(0f, 0f), radius = 520f,
                )
            )
        )
        Box(
            Modifier.matchParentSize().border(
                1.dp,
                Brush.linearGradient(
                    0f to edge, 0.38f to Color.White.copy(alpha = 0.05f),
                    0.62f to Color.White.copy(alpha = 0.04f), 1f to edge,
                ),
                shape,
            )
        )
        content()
    }
}

/** Fine white grain, tiled over the camera image for the frosted-glass look. */
fun frostNoise(tilePx: Int): ImageBitmap {
    val base = 160
    val bmp = Bitmap.createBitmap(base, base, Bitmap.Config.ARGB_8888)
    val rnd = Random(7)
    val px = IntArray(base * base) {
        val a = (rnd.nextFloat() * rnd.nextFloat() * 0.55f * 255).toInt()
        (a shl 24) or 0xFFFFFF
    }
    bmp.setPixels(px, 0, base, 0, 0, base, base)
    val scaled = if (tilePx != base) Bitmap.createScaledBitmap(bmp, tilePx, tilePx, true) else bmp
    return scaled.asImageBitmap()
}

fun noiseBrush(img: ImageBitmap) = ShaderBrush(ImageShader(img, TileMode.Repeated, TileMode.Repeated))

// ---------- icons (same strokes as the web app) ----------

private fun strokeIcon(name: String, vararg paths: String, fill: Boolean = false): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    for (d in paths) {
        if (fill) b.addPath(addPathNodes(d), fill = SolidColor(Color.White))
        else b.addPath(
            addPathNodes(d), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

object Icons {
    val Copy = strokeIcon(
        "copy",
        "M12 9h6a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3h-6a3 3 0 0 1-3-3v-6a3 3 0 0 1 3-3z",
        "M5 15V6a3 3 0 0 1 3-3h7",
    )
    val Target = strokeIcon(
        "target",
        "M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2",
        "M8.5 12a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0",
    )
    val Torch = strokeIcon("torch", "M7 2h10v4l-2 4v11a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V10L7 6z", "M7 6h10M12 13v2")
    val Pause = strokeIcon(
        "pause",
        "M7.5 5h1A1.5 1.5 0 0 1 10 6.5v11A1.5 1.5 0 0 1 8.5 19h-1A1.5 1.5 0 0 1 6 17.5v-11A1.5 1.5 0 0 1 7.5 5z",
        "M15.5 5h1A1.5 1.5 0 0 1 18 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5v-11A1.5 1.5 0 0 1 15.5 5z",
        fill = true,
    )
    val Play = strokeIcon("play", "M7 4.5v15a1 1 0 0 0 1.5.86l12-7.5a1 1 0 0 0 0-1.72l-12-7.5A1 1 0 0 0 7 4.5z", fill = true)
}
