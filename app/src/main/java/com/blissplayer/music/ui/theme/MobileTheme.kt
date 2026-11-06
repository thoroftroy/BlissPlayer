package com.blissplayer.music.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.blissplayer.music.R
import com.blissplayer.core.BlissTheme
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Materials, typography and motion are theme-specific, not a light/dark switch. */
enum class MobileTheme(val key: String) {
    BLISS("bliss"), DARK("dark"), AQUA("aqua"), SUPER("super");

    /** Shared definition; scalar values live in `:core` so Linux uses the same ones. */
    val bliss: BlissTheme get() = BlissTheme.from(key)

    val modern: Boolean get() = bliss.modern
    val ink: Int get() = bliss.ink
    val secondary: Int get() = bliss.secondary
    val accent: Int get() = bliss.accent

    companion object {
        fun from(key: String?): MobileTheme = entries.firstOrNull { it.key == key } ?: BLISS
        fun read(context: Context): MobileTheme {
            val prefs = context.getSharedPreferences("bliss", Context.MODE_PRIVATE)
            return from(prefs.getString("theme", if (prefs.getBoolean("dark", false)) "dark" else "bliss"))
        }
    }
}

enum class MaterialRole {
    BACKDROP, TOOLBAR, PANEL, DIALOG, CONTROL, PRIMARY, SECONDARY,
    TAB, FIELD, DISPLAY, STATUS, COLUMN, ROW, SECTION, ICON
}

/** Typography is cached once, including bold instances of the bundled variable fonts. */
object ThemeFonts {
    private val fonts = mutableMapOf<String, Typeface>()

    fun face(context: Context, theme: MobileTheme, bold: Boolean = false): Typeface {
        val key = "${theme.key}:$bold"
        return fonts.getOrPut(key) {
            if (theme == MobileTheme.DARK) {
                return@getOrPut Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val resource = when (theme) {
                MobileTheme.AQUA -> R.font.aqua_sans
                MobileTheme.SUPER -> R.font.super_sans
                else -> if (bold) R.font.ptsans_700 else R.font.ptsans
            }
            val base = runCatching { ResourcesCompat.getFont(context, resource) }.getOrNull()
                ?: Typeface.create("sans-serif", Typeface.NORMAL)
            // Android's isBold reports false below weight 700. Use a true bold
            // so later tree styling retains headings instead of demoting them.
            if (theme.modern) Typeface.create(base, if (bold) 700 else 450, false)
            else base
        }
    }

    fun apply(view: TextView, theme: MobileTheme, bold: Boolean = view.typeface?.isBold == true) {
        val font = face(view.context, theme, bold)
        if (view.typeface != font) view.typeface = font
        // Keep the XP text shadows, spacing and padding intact in retro themes.
        if (!theme.modern) return
        view.letterSpacing = if (theme == MobileTheme.SUPER) {
            if (bold) 0.015f else 0.005f
        } else -0.015f
        view.includeFontPadding = false
        view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }
}

/** Activity-scoped renderer lookup for recycled rows and themed dialog contexts. */
object ThemeRenderers {
    private val renderers = WeakHashMap<Context, WeakReference<MobileThemeController>>()

    fun put(context: Context, renderer: MobileThemeController) {
        renderers[context] = WeakReference(renderer)
    }

    fun get(context: Context): MobileThemeController? {
        var ctx = context
        repeat(12) {
            renderers[ctx]?.get()?.let { return it }
            val base = (ctx as? ContextWrapper)?.baseContext ?: return null
            if (ctx === base) return null
            ctx = base
        }
        return null
    }

    fun remove(context: Context) { renderers.remove(context) }
}

fun View.themeRole(role: MaterialRole) { setTag(R.id.theme_role, role) }
