package com.blissplayer.music.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/** Optional AGSL plasma; all materials have a Canvas-shader fallback on Android 10–12. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object ThemeSceneShader {
    fun create(): RuntimeShader = RuntimeShader("""
        uniform float2 resolution;
        uniform float time;
        half4 main(float2 xy) {
            float2 uv = xy / max(resolution, float2(1.0));
            float2 p = (uv - 0.5) * float2(1.0, resolution.y / resolution.x);
            float t = time * 0.24;
            float wave = sin(p.y * 5.0 + t) * 0.12 + cos(p.x * 5.0 - t) * 0.1;
            float a = exp(-3.7 * length((p - float2(-0.35 + sin(t) * 0.16, -0.25)) * float2(1.2, 0.7)));
            float b = exp(-4.0 * length((p - float2(0.3, 0.30 + cos(t) * 0.2)) * float2(1.3, 0.9)));
            float c = exp(-4.4 * abs(p.x + p.y * 0.24 + wave + 0.25));
            float3 ink = float3(0.018, 0.027, 0.061);
            float3 light = float3(0.28, 0.04, 0.21) * a + float3(0.015, 0.25, 0.27) * b;
            light += float3(0.04, 0.08, 0.19) * c;
            return half4(half3(ink + light), 1.0);
        }
    """.trimIndent())
}
