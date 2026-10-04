package dev.fahim.livescanner.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The CRT pass over the radar scope: phosphor bloom, scanlines and a corner vignette.
 *
 * This runs as a GPU fragment shader over whatever the scope already drew, rather than as more
 * Canvas work. That matters for a screen that is already redrawing every frame for the sweep:
 * the effect costs GPU fill rate, not recomposition or draw calls, so it does not compete with
 * the thing that made this screen choppy before.
 *
 * Deliberately static — no time uniform. The scope's motion stops with the audio by design, and
 * a shader that animated on its own would quietly break that.
 */

/** AGSL. `content` is the scope's own rendering, handed in by [RenderEffect]. */
private const val SCOPE_SHADER_SRC = """
uniform shader content;
uniform float2 resolution;
uniform float bloom;
uniform float scanline;
uniform float scanlinePeriod;
uniform float vignette;

half4 main(float2 coord) {
    half4 src = content.eval(coord);

    // Phosphor bloom: eight taps on a ring, so a bright target bleeds into the dark around it
    // the way a real tube does. A full gaussian would look marginally softer and cost several
    // times as much fill rate for a scope this dark.
    float r = 3.0;
    float d = r * 0.70710678;
    half3 sum =
        content.eval(coord + float2( r, 0.0)).rgb +
        content.eval(coord + float2(-r, 0.0)).rgb +
        content.eval(coord + float2(0.0,  r)).rgb +
        content.eval(coord + float2(0.0, -r)).rgb +
        content.eval(coord + float2( d,  d)).rgb +
        content.eval(coord + float2( d, -d)).rgb +
        content.eval(coord + float2(-d,  d)).rgb +
        content.eval(coord + float2(-d, -d)).rgb;
    half3 ring = sum * half(0.125);

    // Only what is already bright glows. Without this threshold the whole scope lifts into grey
    // and the black between targets stops reading as black.
    half brightness = dot(ring, half3(0.299, 0.587, 0.114));
    half3 glow = ring * smoothstep(half(0.22), half(0.85), brightness);

    half3 color = src.rgb + glow * half(bloom);

    // Scanlines, as a cosine rather than hard rows: hard rows alias into moire the moment the
    // picture rotates, and this scope rotates whenever track-up is on.
    half lines = half(0.5) + half(0.5) * half(cos(6.2831853 * coord.y / scanlinePeriod));
    color *= half(1.0) - half(scanline) * lines;

    // Vignette, squared so the falloff stays off the middle of the picture.
    float2 fromCenter = coord / resolution - 0.5;
    float fall = min(length(fromCenter) * 1.41421356, 1.0);
    color *= half(1.0) - half(vignette) * half(fall * fall);

    // Skia composites premultiplied, so colour must never exceed alpha or bright targets fringe.
    return half4(clamp(color, half3(0.0), half3(src.a)), src.a);
}
"""

/** Bloom is lifted at night: the amber palette is dimmer, so the same gain reads as less glow. */
private const val BLOOM_DAY = 0.55f
private const val BLOOM_NIGHT = 0.70f

private const val SCANLINE_DEPTH = 0.10f
private const val VIGNETTE_DEPTH = 0.35f

/** Period in pixels. Near 3 keeps the lines visible without turning into a grey wash. */
private const val SCANLINE_PERIOD_PX = 3.0f

/**
 * A [Modifier] that puts the CRT pass over this composable, or an empty one when it can't.
 *
 * Returns [Modifier] untouched below Android 13 — `RuntimeShader` arrived in API 33 and this app
 * supports back to 26, so roughly the scope as it looks today is the fallback rather than a
 * second software implementation of the same look.
 */
@Composable
fun scopeCrtEffect(enabled: Boolean, night: Boolean): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Modifier
    return crtModifier(night)
}

/** True when this device can run the effect at all, so the UI can hide the control. */
val crtSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun crtModifier(night: Boolean): Modifier {
    // AGSL is compiled by the GPU driver at runtime, not at build time, so nothing in this
    // project's compile or test step can prove it is valid — and a shader that throws would take
    // the whole radar screen with it. A failure here costs the effect, not the scope.
    val shader = remember {
        try {
            RuntimeShader(SCOPE_SHADER_SRC)
        } catch (t: Throwable) {
            Log.e(TAG, "Scope shader failed to compile; falling back to the plain scope", t)
            null
        }
    } ?: return Modifier

    return remember(shader, night) {
        Modifier.graphicsLayer {
            // Set in the draw phase, where the layer's real pixel size is known and re-run for
            // free when it changes — rotation and window insets both move it.
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("bloom", if (night) BLOOM_NIGHT else BLOOM_DAY)
            shader.setFloatUniform("scanline", SCANLINE_DEPTH)
            shader.setFloatUniform("scanlinePeriod", SCANLINE_PERIOD_PX)
            shader.setFloatUniform("vignette", VIGNETTE_DEPTH)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

private const val TAG = "ScopeEffect"
