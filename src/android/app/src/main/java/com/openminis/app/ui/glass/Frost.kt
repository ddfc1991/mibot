package com.openminis.app.ui.glass

import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.LocalMibotDark
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * [fork/frost] ONE shared frosted-glass ("毛玻璃朦胧") surface for the whole app.
 *
 * Replaces the old low-alpha fills (0.40–0.72 flat over the moving sky) that let
 * text sit on a busy background. Every Mibot surface now draws:
 *
 *  - API 31+: the shared backdrop (the sky / photo under the NavHost, registered
 *    as a Haze source by [FrostHost] + [frostSource]) blurred with RenderEffect,
 *    a light grain, then a tinted scrim (light ~0.72, dark ~0.62);
 *  - API < 31, inside a dialog / sheet window, or with no backdrop host: a
 *    higher-opacity flat tint (~0.88) so text stays readable without blur;
 *  - a 1dp hairline rim (top highlight → hairline) and an optional soft shadow.
 *
 * The scrim is denser over a user photo ([FrostEnv.photo]) because a
 * photo can hold high-contrast detail the procedural sky never has. Tints are
 * chosen so body text (ink) and muted text keep ≥ 4.5:1 in both themes.
 */
enum class FrostLevel {
    /** Cards, list rows, inputs, chips: the default. */
    Card,
    /** Content-bearing feed cards over a user photo; prevents backdrop text bleeding into copy. */
    Content,
    /** Floating bars (tab bar, top-bar buttons). Same scrim, stronger blur. */
    Bar,
    /** Sheets / drawers / overlays holding long text: a little denser. */
    Sheet,
}

/** What the frosted surfaces blur, published by [FrostHost]. */
@Immutable
class FrostEnv(
    val haze: HazeState?,
    /** The window the backdrop source lives in; other windows can't sample it. */
    val hostView: View?,
    /** The backdrop is the user's photo: denser scrim (see [FrostTokens.scrimAlpha]). */
    val photo: Boolean,
)

val LocalFrost = staticCompositionLocalOf { FrostEnv(null, null, false) }

object FrostTokens {
    private val DarkBase = Color(0xFF16181D)
    private val LightBase = Color(0xFFFBFAF8)

    fun base(dark: Boolean): Color = if (dark) DarkBase else LightBase

    /** Scrim alpha over the blurred backdrop. */
    fun scrimAlpha(dark: Boolean, level: FrostLevel, photo: Boolean = false): Float {
        var a = when (level) {
            FrostLevel.Card, FrostLevel.Bar -> if (dark) 0.62f else 0.72f
            FrostLevel.Content -> if (dark) 0.88f else 0.92f
            FrostLevel.Sheet -> if (dark) 0.72f else 0.80f
        }
        // A photo can put white (dark theme) or black (light theme) right under
        // the glass even after its veil; muted text needs ≥ 0.84 / 0.86 there.
        if (photo) a = maxOf(a, if (dark) 0.84f else 0.86f)
        return a
    }

    /** Flat tint when there is no blur (API < 31, other windows, no host). */
    fun fallbackAlpha(level: FrostLevel, photo: Boolean = false): Float = when {
        photo -> 0.93f
        level == FrostLevel.Sheet -> 0.94f
        level == FrostLevel.Content -> 0.97f
        else -> 0.88f
    }

    fun blurRadius(level: FrostLevel): Dp = when (level) {
        FrostLevel.Card -> 24.dp
        FrostLevel.Content -> 28.dp
        FrostLevel.Bar -> 28.dp
        FrostLevel.Sheet -> 32.dp
    }

    /** The no-blur fill; also what [com.openminis.app.mibot.design.MibotTokens.card] returns. */
    fun flat(dark: Boolean, level: FrostLevel = FrostLevel.Card, photo: Boolean = false): Color =
        base(dark).copy(alpha = fallbackAlpha(level, photo))

    fun hairline(dark: Boolean): Color =
        if (dark) Color.White.copy(alpha = 0.10f) else Color(0xFF1B1D22).copy(alpha = 0.09f)

    /** Rim: faint top highlight fading into the hairline. */
    fun rim(dark: Boolean): Brush = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.16f else 0.70f),
            hairline(dark),
        ),
    )

    fun style(dark: Boolean, level: FrostLevel, photo: Boolean = false): HazeStyle = HazeStyle(
        backgroundColor = base(dark),
        tints = listOf(HazeTint(base(dark).copy(alpha = scrimAlpha(dark, level, photo)))),
        blurRadius = blurRadius(level),
        noiseFactor = if (dark) 0.035f else 0.045f,
        fallbackTint = HazeTint(flat(dark, level, photo)),
    )
}

/** Dark when the Mibot night variant is active (else: the current scheme is dark). */
@Composable
@ReadOnlyComposable
fun frostIsDark(): Boolean =
    if (com.openminis.app.ui.theme.LocalMibotActive.current) LocalMibotDark.current
    else androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * Root host: creates the backdrop Haze state and publishes it. Put it once
 * around the activity content; [frostSource] goes on the backdrop itself.
 */
@Composable
fun FrostHost(enabled: Boolean = true, content: @Composable () -> Unit) {
    val haze = rememberHazeState()
    val view = LocalView.current
    val context = LocalContext.current
    val settings = remember(context) { GlassPrefs.flow(context) }.collectAsState().value
    val photo = settings.mode == BackdropMode.PHOTO && settings.photoPath != null
    val env = remember(haze, view, photo, enabled) { FrostEnv(if (enabled) haze else null, view, photo) }
    CompositionLocalProvider(LocalFrost provides env) { content() }
}

/** Registers this layer (the shared backdrop) as what frosted surfaces blur. */
@Composable
fun Modifier.frostSource(): Modifier {
    val haze = LocalFrost.current.haze ?: return this
    return this.hazeSource(haze)
}

/**
 * The frosted surface. Clips to [shape], blurs + tints (or flat-tints on the
 * fallback path), draws the hairline rim and, with [elevation] > 0, a soft
 * shadow. [haze] overrides the shared backdrop (e.g. the tab bar blurs the
 * page scrolling beneath it). [tint] lays an extra wash on top (selected chips).
 */
@Composable
fun Modifier.frosted(
    shape: Shape = RectangleShape,
    level: FrostLevel = FrostLevel.Card,
    border: Boolean = true,
    elevation: Dp = 0.dp,
    haze: HazeState? = null,
    tint: Color = Color.Transparent,
): Modifier {
    val dark = frostIsDark()
    val env = LocalFrost.current
    val view = LocalView.current
    val state = haze ?: env.haze?.takeIf { env.hostView == null || env.hostView === view }
    val blur = state != null && Build.VERSION.SDK_INT >= 31
    var m = this
    if (elevation > 0.dp) {
        val c = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f)
        m = m.shadow(elevation, shape, clip = false, ambientColor = c, spotColor = c)
    }
    m = m.clip(shape)
    m = if (blur) {
        m.hazeEffect(state = state, style = FrostTokens.style(dark, level, env.photo)) { blurEnabled = true }
    } else {
        m.background(FrostTokens.flat(dark, level, env.photo))
    }
    if (tint.alpha > 0f) m = m.background(tint)
    if (border) m = m.border(1.dp, FrostTokens.rim(dark), shape)
    return m
}

/** Box wrapper around [frosted] for call sites that want a component. */
@Composable
fun FrostedSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    level: FrostLevel = FrostLevel.Card,
    border: Boolean = true,
    elevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.frosted(shape, level, border, elevation), content = content)
}
