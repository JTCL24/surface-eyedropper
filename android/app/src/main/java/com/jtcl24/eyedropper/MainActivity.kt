package com.jtcl24.eyedropper

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var engine: CameraEngine

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine.start() else engine.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = CameraEngine(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EyedropperScreen(engine, onStart = ::requestStart)
            }
        }
    }

    private fun requestStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) engine.start()
        else permission.launch(Manifest.permission.CAMERA)
    }

    // Release camera + torch when the app goes to the background
    override fun onStop() {
        super.onStop()
        engine.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }
}

private val OK = Color(0xFF5DF2A0)
private val WARN = Color(0xFFFFD166)
private val BAD = Color(0xFFFF6B6B)
private val TNUM = "tnum"

@Composable
fun EyedropperScreen(engine: CameraEngine, onStart: () -> Unit) {
    val ui by engine.state.collectAsState()
    val frameState = engine.frame.collectAsState()
    val rootSize = remember { mutableStateOf(IntSize.Zero) }
    var holding by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<Rgb?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var toastText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { engine.messages.collect { toast = it to System.nanoTime() } }
    LaunchedEffect(toast) {
        toast?.let { toastText = it.first; delay(1800); toast = null }
    }
    LaunchedEffect(ui.running) { if (!ui.running) holding = false }

    val shown = if (holding) held else ui.rgb
    val ink by animateColorAsState(if (ui.bright) Color(0xFF0D0D12) else Color.White, tween(400), label = "ink")
    val ink2 = ink.copy(alpha = if (ui.bright) 0.62f else 0.68f)
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    fun copy(text: String) {
        if (shown == null) return
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("color", text))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        toast = "Copied $text" to System.nanoTime()
    }

    val env = remember(ui.bright) { GlassEnv(frameState, rootSize, ui.bright) }

    CompositionLocalProvider(LocalGlass provides env, LocalContentColor provides ink) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged {
                    rootSize.value = it
                    engine.setViewport(it.width, it.height)
                }
        ) {
            val third = maxHeight / 3
            val screenW = maxWidth
            val compact = maxHeight < 640.dp

            CameraLayer(frameState, rootSize)
            ReticleLayer(ui)

            // ---- top third: view only, swallows every touch ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(third)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
            ) {
                Text(
                    "SAMPLING AREA",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    style = TextStyle(
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 6f),
                    ),
                )
            }

            // ---- bottom two thirds: liquid glass UI ----
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = third)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultCard(
                    Modifier.weight(1f), ui, shown, holding, ink2, screenW, compact,
                    onCopy = ::copy,
                )
                StatusRow(ui, ink2, onWbClick = { if (ui.calibrated && !ui.calibrating) confirmClear = true })
                Controls(
                    ui, holding, compact,
                    onHold = {
                        holding = !holding
                        if (holding) held = ui.rgb
                    },
                    onMain = { if (ui.running) engine.calibrate() else onStart() },
                    onTorch = { engine.setTorch(!ui.torchOn) },
                )
            }

            AnimatedVisibility(
                visible = toast != null,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (compact) 170.dp else 190.dp),
                enter = fadeIn(tween(250)) + slideInVertically(tween(350)) { it / 2 },
                exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { it / 2 },
            ) {
                Glass(shape = RoundedCornerShape(50), tint = SolidColor(Color.Black.copy(alpha = 0.35f))) {
                    Text(
                        toastText,
                        Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear calibration?") },
            text = { Text("The white-balance calibration will be removed and raw camera color shown.") },
            confirmButton = {
                TextButton(onClick = { engine.clearCalibration(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Full-screen camera image: lightly blurred, with a frosted-glass grain over it. */
@Composable
private fun CameraLayer(frame: State<Frame?>, root: MutableState<IntSize>) {
    val density = LocalDensity.current
    val noise = remember(density) { noiseBrush(frostNoise((160 * density.density).toInt())) }
    Canvas(Modifier.fillMaxSize().blur(2.88.dp)) {
        drawBackdrop(frame.value, Offset.Zero, root.value, glass = false)
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.06f))),
            alpha = 0.9f, blendMode = BlendMode.Softlight,
        )
        drawRect(noise, alpha = 0.9f, blendMode = BlendMode.Softlight)
    }
}

/** Dims the camera except the sampled square, and outlines it. */
@Composable
private fun ReticleLayer(ui: UiState) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        1f, 1.06f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "pulse",
    )
    val side by animateFloatAsState(ui.reticlePx, tween(300), label = "side")
    Canvas(Modifier.fillMaxSize()) {
        val s = (if (side > 0f) side else 120.dp.toPx()) * (if (ui.calibrating) pulse else 1f)
        val cx = size.width / 2f
        val cy = size.height / 6f
        val rr = RoundRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, CornerRadius(26.dp.toPx()))
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(rr)
        }
        drawPath(dim, Color.Black.copy(alpha = 0.18f))
        val color = when {
            ui.calibrating -> WARN
            ui.clipping -> BAD
            else -> Color.White.copy(alpha = 0.9f)
        }
        val stroke = if (ui.calibrating || ui.clipping) 2.dp.toPx() else 1.5.dp.toPx()
        drawPath(Path().apply { addRoundRect(rr) }, color, style = Stroke(stroke))
        val arm = 7.dp.toPx()
        val cross = Color.White.copy(alpha = 0.85f)
        drawLine(cross, Offset(cx - arm, cy), Offset(cx + arm, cy), 1.5.dp.toPx())
        drawLine(cross, Offset(cx, cy - arm), Offset(cx, cy + arm), 1.5.dp.toPx())
    }
}

@Composable
private fun ResultCard(
    modifier: Modifier,
    ui: UiState,
    shown: Rgb?,
    holding: Boolean,
    ink2: Color,
    width: Dp,
    compact: Boolean,
    onCopy: (String) -> Unit,
) {
    val swatchSize = (width.value * 0.28f).coerceIn(88f, 132f).dp
    val numberSize = (width.value * 0.085f).coerceIn(24f, 36f).sp
    val name = remember(shown) { shown?.let { ColorMath.name(it) } }
    Glass(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().padding(if (compact) 14.dp else 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Swatch(shown, holding, swatchSize)
                Column(Modifier.weight(1f).height(swatchSize), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (ui.calibrated) "AVERAGE COLOR · CALIBRATED" else "AVERAGE COLOR · RAW",
                        color = ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row {
                        Channel("R", shown?.r, Color(0xFFFF7A7A), Color(0xFFC62828), ui.bright, numberSize, Modifier.weight(1f))
                        Channel("G", shown?.g, Color(0xFF6BE59B), Color(0xFF1B8A4A), ui.bright, numberSize, Modifier.weight(1f))
                        Channel("B", shown?.b, Color(0xFF7AB8FF), Color(0xFF1F5FBF), ui.bright, numberSize, Modifier.weight(1f))
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    name?.name ?: "—",
                    Modifier.weight(1f, fill = false),
                    fontSize = (width.value * 0.06f).coerceIn(20f, 26f).sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (name != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(name.match, color = ink2, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CopyChip(shown?.css ?: "rgb(—, —, —)", Modifier.weight(1.5f), compact) { shown?.let { onCopy(it.css) } }
                CopyChip(shown?.hex ?: "#——————", Modifier.weight(1f), compact) { shown?.let { onCopy(it.hex) } }
            }
            if (!compact) {
                Text(
                    ui.hint, Modifier.fillMaxWidth(), color = ink2, fontSize = 13.sp,
                    lineHeight = 17.sp, textAlign = TextAlign.Center, maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Rgb?, holding: Boolean, size: Dp) {
    val target = color?.let { Color(it.r, it.g, it.b) } ?: Color(0xFF28282E)
    val c by animateColorAsState(target, tween(150), label = "swatch")
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(c)
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.32f to Color.Transparent))
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
    ) {
        if (holding) {
            Text(
                "HOLD",
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            )
        }
    }
}

@Composable
private fun Channel(label: String, value: Int?, dark: Color, light: Color, bright: Boolean, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = if (bright) light else dark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            value?.toString() ?: "—",
            fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            style = TextStyle(fontFeatureSettings = TNUM, letterSpacing = (-0.5).sp, lineHeight = size),
        )
    }
}

@Composable
private fun CopyChip(text: String, modifier: Modifier, compact: Boolean, onClick: () -> Unit) {
    Glass(
        modifier.height(if (compact) 44.dp else 50.dp),
        shape = RoundedCornerShape(18.dp),
        backdrop = false,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Copy, null, Modifier.size(17.dp), tint = LocalContentColor.current.copy(alpha = 0.8f))
            Spacer(Modifier.width(8.dp))
            Text(
                text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = TextStyle(fontFeatureSettings = TNUM),
            )
        }
    }
}

@Composable
private fun StatusRow(ui: UiState, ink2: Color, onWbClick: () -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill(Modifier.weight(1f).fillMaxHeight(), "LIGHT", ink2) {
            val pct = (ui.lightLevel * 100).toInt()
            Text(
                when {
                    !ui.running -> "—"
                    !ui.hasTorch -> "No torch"
                    !ui.torchOn -> "Off"
                    else -> "$pct%"
                },
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            val level by animateFloatAsState(if (ui.running && ui.hasTorch) ui.lightLevel.coerceIn(0f, 1f) else 0f, tween(300), label = "light")
            Box(
                Modifier
                    .padding(top = 7.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x47808080))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(level)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFFFE08A), Color(0xFFFFF6D8))))
                )
            }
        }
        Pill(Modifier.weight(1f).fillMaxHeight(), "EXPOSURE", ink2) {
            val (dot, label) = when (if (ui.running) ui.exposure else Exposure.IDLE) {
                Exposure.IDLE -> null to "—"
                Exposure.OK -> OK to "Good"
                Exposure.ADJUSTING -> WARN to "Tuning"
                Exposure.BRIGHT -> BAD to "Too bright"
                Exposure.DARK -> WARN to "Too dark"
            }
            DotLabel(dot, label, ink2)
        }
        Pill(Modifier.weight(1f).fillMaxHeight(), "WHITE BAL.", ink2, onClick = onWbClick) {
            when {
                ui.calibrating -> DotLabel(WARN, "Calibrating", ink2)
                ui.calibrated -> DotLabel(OK, "White ✓", ink2)
                else -> DotLabel(null, "Not set", ink2)
            }
        }
    }
}

@Composable
private fun Pill(modifier: Modifier, title: String, ink2: Color, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Glass(modifier, shape = RoundedCornerShape(20.dp), onClick = onClick) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, color = ink2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun DotLabel(dot: Color?, label: String, ink2: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot ?: ink2))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Controls(
    ui: UiState,
    holding: Boolean,
    compact: Boolean,
    onHold: () -> Unit,
    onMain: () -> Unit,
    onTorch: () -> Unit,
) {
    val h = if (compact) 56.dp else 64.dp
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Glass(Modifier.size(h), shape = CircleShape, enabled = ui.running, onClick = onHold) {
            Icon(if (holding) Icons.Play else Icons.Pause, if (holding) "Resume" else "Hold", Modifier.align(Alignment.Center).size(24.dp))
        }
        Glass(
            Modifier.weight(1f).height(h),
            shape = RoundedCornerShape(32.dp),
            enabled = !ui.starting && !ui.calibrating,
            onClick = onMain,
        ) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Target, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        ui.starting -> "Starting…"
                        ui.running -> "Calibrate white"
                        else -> "Start camera"
                    },
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Glass(
            Modifier.size(h),
            shape = CircleShape,
            enabled = ui.running && ui.hasTorch,
            tint = if (ui.torchOn) Brush.linearGradient(listOf(Color(0x8CFFECAA), Color(0x40FFD666))) else null,
            onClick = onTorch,
        ) {
            Icon(Icons.Torch, "Flashlight", Modifier.align(Alignment.Center).size(24.dp))
        }
    }
}
