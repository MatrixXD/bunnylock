package dev.ririxi.bunnylock.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val HOP_MILLIS = 900
private const val HOP_AIRBORNE_SHARE = 0.7f
private const val HOP_SQUASH = 0.14f
private const val HOP_TILT_DEGREES = 18f
private const val BADGE_HOP_MILLIS = 650
private const val ESCAPE_MILLIS = 900
private const val DROOP_SWAY_MILLIS = 2_800
private const val DROOP_SWAY_DEGREES = 4f
private const val BREATH_MILLIS = 2_000
private const val Z_MILLIS = 3_000
private const val Z_COUNT = 3

private val REFRESH_SLOT = 96.dp
private val REFRESH_BUNNY = 60.dp
private val REFRESH_BUNNY_GAP = 6.dp
private val REFRESH_HOP = 18.dp

private enum class BunnyPose(
    val earTilt: Float,
    val eyesClosed: Boolean,
    val viewport: Rect,
) {
    UPRIGHT(0f, false, Rect(33f, 26f, 75f, 82f)),
    DROOPY(120f, false, Rect(8f, 40f, 100f, 84f)),
    SLEEPY(20f, true, Rect(26f, 24f, 82f, 84f)),
}

// The launcher icon's drawing, in its 108-unit canvas.
private object BunnyPaths {
    private const val BODY = "M42,50 H66 A9,9 0 0 1 75,59 V73 A9,9 0 0 1 66,82 H42 A9,9 0 0 1 33,73 V59 A9,9 0 0 1 42,50 Z "
    private const val OPEN_EYES = "M44.5,63 A2.75,2.75 0 1 0 50,63 A2.75,2.75 0 1 0 44.5,63 Z M58,63 A2.75,2.75 0 1 0 63.5,63 A2.75,2.75 0 1 0 58,63 Z "
    private const val CLOSED_EYES = "M44.5,62.5 A2.75,2.75 0 0 0 50,62.5 A2.75,1.6 0 0 1 44.5,62.5 Z M58,62.5 A2.75,2.75 0 0 0 63.5,62.5 A2.75,1.6 0 0 1 58,62.5 Z "
    private const val NOSE = "M50.5,69.5 H57.5 L54,73.5 Z"

    val leftEar = path("M44,26 A5,5 0 0 1 49,31 V54 A5,5 0 0 1 39,54 V31 A5,5 0 0 1 44,26 Z")
    val rightEar = path("M64,26 A5,5 0 0 1 69,31 V54 A5,5 0 0 1 59,54 V31 A5,5 0 0 1 64,26 Z")
    val awakeBody = path(BODY + OPEN_EYES + NOSE, PathFillType.EvenOdd)
    val sleepingBody = path(BODY + CLOSED_EYES + NOSE, PathFillType.EvenOdd)
    val leftEarBase = Offset(44f, 54f)
    val rightEarBase = Offset(64f, 54f)

    private fun path(
        data: String,
        fillType: PathFillType = PathFillType.NonZero,
    ) = PathParser().parsePathString(data).toPath().apply { this.fillType = fillType }
}

@Composable
private fun BunnyLock(
    pose: BunnyPose,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    earSway: () -> Float = { 0f },
) {
    val viewport = pose.viewport
    Canvas(modifier.aspectRatio(viewport.width / viewport.height)) {
        val scale = size.width / viewport.width
        withTransform({
            scale(scale, scale, pivot = Offset.Zero)
            translate(-viewport.left, -viewport.top)
        }) {
            val tilt = pose.earTilt + earSway()
            rotate(-tilt, pivot = BunnyPaths.leftEarBase) { drawPath(BunnyPaths.leftEar, color) }
            rotate(tilt, pivot = BunnyPaths.rightEarBase) { drawPath(BunnyPaths.rightEar, color) }
            drawPath(if (pose.eyesClosed) BunnyPaths.sleepingBody else BunnyPaths.awakeBody, color)
        }
    }
}

private class HopPose(
    val lift: Float = 0f,
    val squash: Float = 0f,
    val tilt: Float = 0f,
)

private fun hopPose(phase: Float): HopPose {
    if (phase >= HOP_AIRBORNE_SHARE) return HopPose(squash = sin(PI * (phase - HOP_AIRBORNE_SHARE) / (1 - HOP_AIRBORNE_SHARE)).toFloat())
    val flight = phase / HOP_AIRBORNE_SHARE
    val lift = 4 * flight * (1 - flight)
    return HopPose(lift = lift, tilt = HOP_TILT_DEGREES * (flight - 0.5f) * lift)
}

private fun Modifier.hopping(
    height: Dp,
    phase: () -> Float,
): Modifier =
    graphicsLayer {
        val pose = hopPose(phase())
        transformOrigin = TransformOrigin(0.5f, 1f)
        translationY = -height.toPx() * pose.lift
        rotationZ = pose.tilt
        scaleX = 1f + HOP_SQUASH * pose.squash
        scaleY = 1f - HOP_SQUASH * pose.squash
    }

@Composable
private fun rememberHopPhase(): State<Float> =
    rememberInfiniteTransition(label = "hop").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(HOP_MILLIS, easing = LinearEasing)),
        label = "phase",
    )

private suspend fun Animatable<Float, *>.playOnce(millis: Int) {
    snapTo(0f)
    animateTo(1f, tween(millis, easing = LinearEasing))
}

@Composable
fun HoppingBunny(height: Dp = 88.dp) {
    val phase = rememberHopPhase()
    val jump = height * 0.45f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(height + jump), contentAlignment = Alignment.BottomCenter) {
            BunnyLock(BunnyPose.UPRIGHT, Modifier.height(height).hopping(jump) { phase.value })
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(width = height * 0.6f, height = 6.dp)
                .graphicsLayer {
                    val lift = hopPose(phase.value).lift
                    scaleX = 1f - 0.45f * lift
                    alpha = 1f - 0.6f * lift
                }.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), CircleShape),
        )
    }
}

@Composable
fun DroopyBunny(width: Dp = 150.dp) {
    val sway =
        rememberInfiniteTransition(label = "droop").animateFloat(
            initialValue = -DROOP_SWAY_DEGREES,
            targetValue = DROOP_SWAY_DEGREES,
            animationSpec = infiniteRepeatable(tween(DROOP_SWAY_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "sway",
        )
    BunnyLock(BunnyPose.DROOPY, Modifier.width(width), color = MaterialTheme.colorScheme.onSurfaceVariant, earSway = { sway.value })
}

@Composable
fun SleepyBunny(
    title: String,
    detail: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "sleep")
    val breath =
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BREATH_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath",
        )
    val drift =
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Z_MILLIS, easing = LinearEasing)),
            label = "drift",
        )
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 96.dp, height = 104.dp)) {
            BunnyLock(
                BunnyPose.SLEEPY,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 18.dp)
                    .height(64.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleX = 1f + 0.025f * breath.value
                        scaleY = 1f - 0.03f * breath.value
                    },
            )
            repeat(Z_COUNT) { index ->
                Text(
                    "z",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.offset(x = 66.dp, y = 36.dp).graphicsLayer {
                            val t = (drift.value + index.toFloat() / Z_COUNT) % 1f
                            val grow = 0.55f + 0.6f * t
                            translationX = 14.dp.toPx() * t + 3.dp.toPx() * sin(2 * PI * t).toFloat()
                            translationY = -36.dp.toPx() * t
                            scaleX = grow
                            scaleY = grow
                            rotationZ = -14f + 12f * t
                            alpha = min(t / 0.15f, 1f) * min((1f - t) / 0.4f, 1f)
                        },
                )
            }
        }
        Text(title, style = MaterialTheme.typography.bodyLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun BouncyLockBadge(
    locked: Boolean,
    ignored: Boolean,
    userAction: Boolean,
    size: Dp,
) {
    val hop = remember { Animatable(1f) }
    val escape = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val changedByUser by rememberUpdatedState(userAction)
    var previous by remember { mutableStateOf(locked) }
    LaunchedEffect(locked) {
        val changed = previous != locked
        previous = locked
        // A refresh that finds locks, like the first one after startup, shouldn't bounce the badge.
        if (!changed || !changedByUser) return@LaunchedEffect
        val nowLocked = locked
        // Outside the effect, so the next flip can't cancel it halfway and leave the badge stuck in the air.
        scope.launch { if (nowLocked) hop.playOnce(BADGE_HOP_MILLIS) else escape.playOnce(ESCAPE_MILLIS) }
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .hopping(size * 0.29f) { hop.value }
                .clickable(interactionSource = null, indication = null) { scope.launch { hop.playOnce(BADGE_HOP_MILLIS) } },
        ) {
            LockBadge(locked, size = size, spin = true, ignored = ignored)
        }
        Icon(
            BunnyIcons.Bunny,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.size(size * 0.36f).graphicsLayer {
                    val t = escape.value
                    val grow = 0.5f + 0.5f * min(1f, 3 * t)
                    alpha = 1f - t * t
                    translationX = size.toPx() * 0.6f * t
                    translationY = size.toPx() * (-1.25f * t + 0.8f * t * t)
                    rotationZ = 25f * t
                    scaleX = grow
                    scaleY = grow
                },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun Density.revealedHeight(state: PullToRefreshState): Float = REFRESH_SLOT.toPx() * state.distanceFraction.coerceIn(0f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.belowBunnyRefresh(state: PullToRefreshState): Modifier = graphicsLayer { translationY = revealedHeight(state) }

// The bunny stays put while the pull uncovers it from the top, ears first, then rides down with the content.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BunnyRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val phase = if (isRefreshing) rememberHopPhase() else null
    Box(
        modifier
            .fillMaxWidth()
            .height(REFRESH_SLOT)
            .drawWithContent { clipRect(bottom = revealedHeight(state)) { this@drawWithContent.drawContent() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        BunnyLock(
            BunnyPose.UPRIGHT,
            Modifier.height(REFRESH_BUNNY).graphicsLayer {
                val pose = hopPose(phase?.value ?: 1f)
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = max(0f, revealedHeight(state) - (REFRESH_BUNNY + REFRESH_BUNNY_GAP).toPx()) - REFRESH_HOP.toPx() * pose.lift
                scaleX = 1f + HOP_SQUASH * pose.squash
                scaleY = 1f - HOP_SQUASH * pose.squash
            },
        )
    }
}
