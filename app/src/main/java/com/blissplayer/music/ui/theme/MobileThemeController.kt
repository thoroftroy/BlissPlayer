package com.blissplayer.music.ui.theme

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import com.blissplayer.music.R
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.hypot

/** One clock, real child-touch dispatch, no global singleton animator or activity leaks. */
class MobileThemeController(private val activity: Activity, val theme: MobileTheme) : Choreographer.FrameCallback {
    private val d = activity.resources.displayMetrics.density
    private val materials = WeakHashMap<View, WeakReference<MaterialDrawable>>()
    private val layers = mutableListOf<WindowLayer>()
    private var resumed = false
    private var posted = false
    private var previousNanos = 0L
    private var time = 0f
    private var lastDrawNanos = 0L
    private var lastActiveNanos = 0L
    private val visible = Rect()
    var frameCount = 0L
        private set
    var interactionCount = 0
        private set
    val particleCount: Int get() = layers.sumOf { it.effects.field.sparks.size }
    val burstCount: Int get() = layers.sumOf { it.effects.field.burstCount }
    val clockRunning: Boolean get() = posted
    val styledSurfaceCount: Int get() = materials.size

    private val preference get() = activity.getSharedPreferences("bliss", Context.MODE_PRIVATE)
    val motionSetting: String get() = preference.getString("theme_motion", "full") ?: "full"
    val fullMotion: Boolean get() = motionSetting == "full" && ValueAnimator.areAnimatorsEnabled()
    private val moving: Boolean get() = motionSetting != "off" && ValueAnimator.areAnimatorsEnabled()
    /** Per-tap feedback amount: full, gentle, or off. Re-read live. */
    private val clickFx: String get() = preference.getString("click_fx", "full") ?: "full"

    init { ThemeRenderers.put(activity, this) }

    private fun dp(n: Int) = (n * d).toInt()

    fun drawable(role: MaterialRole, accent: Int = theme.accent): Drawable =
        MaterialDrawable(theme, role, d, accent).also { it.motion = fullMotion }

    fun material(view: View, role: MaterialRole, accent: Int = theme.accent, ripple: Boolean = true) {
        if (!theme.modern) return
        view.themeRole(role)
        val old = view.getTag(R.id.theme_material) as? MaterialDrawable
        val background = view.background
        val stillAttached = old != null && (background === old ||
            background is RippleDrawable && background.numberOfLayers > 0 && background.getDrawable(0) === old)
        val art = if (stillAttached && old?.role == role && old.accent == accent) old else {
            val fresh = MaterialDrawable(theme, role, d, accent)
            view.backgroundTintList = null
            view.background = if (ripple && role in setOf(MaterialRole.CONTROL, MaterialRole.PRIMARY, MaterialRole.SECONDARY,
                    MaterialRole.TAB, MaterialRole.ICON, MaterialRole.SECTION, MaterialRole.STATUS, MaterialRole.ROW)) {
                val mask = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(if (theme == MobileTheme.AQUA) 14 else 20).toFloat()
                }
                RippleDrawable(ColorStateList.valueOf(if (theme == MobileTheme.AQUA) 0x440D8BF2 else 0x5576FFE1),
                    fresh, mask)
            } else fresh
            view.setTag(R.id.theme_material, fresh)
            fresh
        }
        if (art != null) {
            art.phase = time; art.motion = fullMotion
            materials[view] = WeakReference(art)
        }
    }

    fun styleMain(root: ViewGroup) {
        if (!theme.modern) return
        material(root, MaterialRole.BACKDROP)
        root.clipChildren = false
        root.clipToPadding = false
        attachWindow(activity.window)
        styleTree(root)
        root.findViewById<View>(R.id.titleBarWrap)?.let { toolbar ->
            material(toolbar, MaterialRole.TOOLBAR)
            toolbar.minimumHeight = dp(if (theme == MobileTheme.AQUA) 62 else 82)
            toolbar.setPadding(dp(12), dp(10), dp(10), dp(10))
            if (toolbar is ViewGroup) {
                (toolbar.getChildAt(0) as? ImageView)?.let { icon ->
                    // The user's own Aqua/Super artwork, not painted dots.
                    val brand = when (theme) {
                        MobileTheme.AQUA -> R.drawable.aqua_brand
                        MobileTheme.SUPER -> R.drawable.super_brand
                        else -> null
                    }
                    if (brand != null) {
                        icon.setImageResource(brand)
                    } else {
                        icon.setImageDrawable(ThemeGlyphDrawable(theme, ThemeGlyphDrawable.Kind.BRAND))
                    }
                    icon.layoutParams = icon.layoutParams.apply {
                        width = dp(if (theme == MobileTheme.AQUA) 46 else 32)
                        height = dp(26)
                    }
                    icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }
        }
        root.findViewById<TextView>(R.id.titleBar)?.let { title ->
            title.typeface = ThemeFonts.face(activity, theme, true)
            title.textSize = if (theme == MobileTheme.AQUA) 18f else 27f
            title.letterSpacing = if (theme == MobileTheme.AQUA) -.025f else -.035f
            title.setTextColor(theme.ink)
        }
        root.findViewById<View>(R.id.tabRow)?.let { tabs ->
            material(tabs, if (theme == MobileTheme.AQUA) MaterialRole.TOOLBAR else MaterialRole.PANEL)
            tabs.setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf(R.id.btnPlayAll, R.id.btnPlayShuffle, R.id.btnSort).forEachIndexed { i, id ->
            root.findViewById<Button>(id)?.let { button ->
                control(button, if (i == 0) MaterialRole.PRIMARY else if (i == 1) MaterialRole.SECONDARY else MaterialRole.CONTROL)
                button.minimumHeight = dp(if (theme == MobileTheme.AQUA) 44 else 52)
                button.textSize = if (theme == MobileTheme.AQUA) 14f else 16f
                button.typeface = ThemeFonts.face(activity, theme, true)
            }
        }
        root.findViewById<TextView>(R.id.crumb)?.let {
            // Instruction line: ink, never muted gray, on every theme.
            it.setTextColor(theme.ink)
            it.textSize = 11f
            it.setPadding(dp(4), dp(5), dp(4), dp(3))
        }
        root.findViewById<TextView>(R.id.headerBar)?.let {
            material(it, MaterialRole.COLUMN)
            it.textSize = 10f
            it.setTextColor(theme.secondary)
            it.setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.findViewById<RecyclerView>(R.id.list)?.let {
            it.background = null
            it.setPadding(dp(1), dp(4), dp(1), dp(8))
            // Rows stay inside the list: never paint over Play/Mix/Sort.
            it.clipToPadding = true
            it.clipChildren = true
        }
        listOf(R.id.miniPlayer, R.id.nowPlayingPanel).forEach { id ->
            root.findViewById<LinearLayout>(id)?.let {
                material(it, MaterialRole.PANEL)
                it.setPadding(dp(12), dp(12), dp(12), dp(10))
                it.elevation = dp(if (theme == MobileTheme.AQUA) 6 else 9).toFloat()
                it.clipToPadding = false
            }
        }
        root.findViewById<TextView>(R.id.miniTitle)?.let { mini ->
            (mini.parent as? LinearLayout)?.let {
                material(it, MaterialRole.DISPLAY)
                it.setPadding(dp(10), dp(9), dp(6), dp(9))
                (it.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(6)
            }
        }
        root.findViewById<View>(R.id.npDisplay)?.let { material(it, MaterialRole.DISPLAY) }
        listOf(R.id.miniTitle, R.id.npTitle, R.id.miniSub, R.id.npArtist).forEachIndexed { i, id ->
            root.findViewById<TextView>(id)?.let {
                it.typeface = ThemeFonts.face(activity, theme, i < 2)
                it.textSize = if (i < 2) if (theme == MobileTheme.AQUA) 15f else 18f else 11f
                // The XP green-on-black deck is unreadable on Aqua's light glass.
                it.setTextColor(if (i < 2) theme.ink else theme.secondary)
            }
        }
        root.findViewById<TextView>(R.id.statusStrip)?.let {
            material(it, MaterialRole.STATUS)
            it.textSize = 10.5f
            it.setPadding(dp(10), dp(7), dp(10), dp(7))
            it.setTextColor(theme.ink)
        }
        if (theme == MobileTheme.AQUA) {
            // Snow Leopard menu bar: light silver chrome, dark icons.
            activity.window.statusBarColor = 0xFFDCE3E9.toInt()
            activity.window.navigationBarColor = 0xFFDCE3E9.toInt()
        } else {
            activity.window.statusBarColor = 0xFF080F20.toInt()
            activity.window.navigationBarColor = 0xFF080F20.toInt()
        }
        WindowInsetsControllerCompat(activity.window, root).apply {
            isAppearanceLightStatusBars = theme == MobileTheme.AQUA
            isAppearanceLightNavigationBars = theme == MobileTheme.AQUA
        }
    }

    fun styleTree(root: View) {
        if (!theme.modern) {
            if (root is TextView) ThemeFonts.apply(root, theme)
            if (root is ViewGroup && root !is RecyclerView) {
                for (i in 0 until root.childCount) styleTree(root.getChildAt(i))
            }
            return
        }
        when (root) {
            is SeekBar -> {
                root.progressDrawable = progressDrawable()
                root.thumb = thumbDrawable()
                root.progressTintList = null
                root.thumbTintList = null
                root.splitTrack = false
            }
            is ProgressBar -> root.progressDrawable = progressDrawable()
            is CompoundButton -> {
                ThemeFonts.apply(root, theme)
                root.setTextColor(theme.ink)
                root.buttonTintList = ColorStateList.valueOf(theme.accent)
            }
            is EditText -> {
                ThemeFonts.apply(root, theme)
                material(root, MaterialRole.FIELD)
                root.setTextColor(theme.ink)
                root.setHintTextColor(theme.secondary)
                root.setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            is Button -> control(root, root.getTag(R.id.theme_role) as? MaterialRole ?: when (root.id) {
                R.id.btnPlayAll -> MaterialRole.PRIMARY
                R.id.btnPlayShuffle -> MaterialRole.SECONDARY
                R.id.tabSongs, R.id.tabArtists, R.id.tabAlbums, R.id.tabGenres, R.id.tabPlaylists, R.id.tabMore -> MaterialRole.TAB
                else -> MaterialRole.CONTROL
            })
            is ImageButton -> {
                material(root, MaterialRole.ICON)
                root.setColorFilter(theme.ink)
                root.elevation = dp(1).toFloat()
            }
            is TextView -> {
                ThemeFonts.apply(root, theme)
                val section = (root.getTag(R.id.tabMore) as? String)?.startsWith("section:") == true
                val role = root.getTag(R.id.theme_role) as? MaterialRole
                if (section) styleSection(root)
                else if (role == MaterialRole.TOOLBAR) {
                    material(root, MaterialRole.TOOLBAR)
                    root.setTextColor(theme.ink)
                } else {
                    root.setTextColor(if (root.textSize / root.resources.displayMetrics.scaledDensity < 13f)
                        theme.secondary else theme.ink)
                }
            }
        }
        if (root is ViewGroup && root !is RecyclerView) {
            for (i in 0 until root.childCount) styleTree(root.getChildAt(i))
        }
    }

    fun control(button: Button, role: MaterialRole = MaterialRole.CONTROL) {
        if (!theme.modern) return
        material(button, role)
        ThemeFonts.apply(button, theme, role == MaterialRole.PRIMARY || role == MaterialRole.SECONDARY || button.typeface?.isBold == true)
        button.isAllCaps = false
        val ink = if (theme == MobileTheme.AQUA) {
            if (role == MaterialRole.PRIMARY || role == MaterialRole.TAB && button.isSelected) Color.WHITE else theme.ink
        } else if (role == MaterialRole.PRIMARY || role == MaterialRole.SECONDARY || role == MaterialRole.TAB && button.isSelected)
            0xFF11293A.toInt() else theme.ink
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(theme.secondary, ink)))
        button.compoundDrawablesRelative.filterNotNull().forEach { it.mutate().setTint(ink) }
        button.minWidth = 0
        button.setPadding(dp(10), dp(8), dp(10), dp(8))
        button.elevation = if (theme == MobileTheme.AQUA && role in setOf(MaterialRole.PRIMARY, MaterialRole.CONTROL)) dp(2).toFloat() else 0f
    }

    fun styleSection(header: TextView) {
        val name = (header.getTag(R.id.tabMore) as? String)?.removePrefix("section:") ?: ""
        val colors = intArrayOf(0xFF187CC0.toInt(), 0xFF319B87.toInt(), 0xFFE69A55.toInt(),
            0xFFA588D7.toInt(), 0xFF669ECD.toInt(), 0xFFE780A5.toInt())
        val color = colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
        material(header, MaterialRole.SECTION, color)
        ThemeFonts.apply(header, theme, true)
        header.setTextColor(theme.ink)
        header.textSize = if (theme == MobileTheme.AQUA) 14f else 15f
        header.setPadding(dp(14), dp(12), dp(12), dp(12))
        header.minimumHeight = dp(50)
    }

    fun row(box: ViewGroup, title: TextView, subtitle: TextView, duration: TextView,
            more: ImageButton, icon: ImageView, selected: Boolean, group: Boolean, fav: ImageButton? = null) {
        material(box, MaterialRole.ROW)
        box.isSelected = selected
        box.minimumHeight = dp(if (theme == MobileTheme.AQUA) 63 else 71)
        box.setPadding(dp(10), dp(9), dp(6), dp(9))
        (box.layoutParams as? RecyclerView.LayoutParams)?.apply {
            bottomMargin = dp(if (theme == MobileTheme.AQUA) 2 else 6)
            marginStart = dp(2); marginEnd = dp(2)
        }
        ThemeFonts.apply(title, theme, true)
        ThemeFonts.apply(subtitle, theme, false)
        ThemeFonts.apply(duration, theme, false)
        title.textSize = if (theme == MobileTheme.AQUA) 14f else 16f
        subtitle.textSize = 11f
        duration.textSize = 10f
        title.setTextColor(theme.ink)
        subtitle.setTextColor(theme.secondary)
        duration.setTextColor(theme.secondary)
        title.setPadding(0, 0, 0, dp(4))
        material(more, MaterialRole.ICON)
        more.setColorFilter(theme.ink)
        // The star floats faceless (no button box); the adapter styles it.
        icon.setImageDrawable(ThemeGlyphDrawable(theme,
            if (group) ThemeGlyphDrawable.Kind.GROUP else ThemeGlyphDrawable.Kind.TRACK))
        icon.layoutParams = icon.layoutParams.apply { width = dp(30); height = dp(30) }
        box.elevation = 0f // visible reflections are in the material, not black card shadows.
    }

    fun progressDrawable(): Drawable {
        fun shape(colors: IntArray) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = dp(8).toFloat()
        }
        val track = shape(if (theme == MobileTheme.AQUA) intArrayOf(0xFF8BA9B6.toInt(), 0xFFD3E4EB.toInt())
            else intArrayOf(0xFF0C1728.toInt(), 0xFF293D54.toInt()))
        val fill: Drawable = when (theme) {
            MobileTheme.AQUA -> shape(
                intArrayOf(0xFFB4F3FF.toInt(), 0xFF1687F4.toInt(), 0xFF0756B2.toInt()))
            MobileTheme.SUPER -> RainbowBarDrawable(d, fullMotion)
            else -> shape(
                intArrayOf(0xFFCEFFF2.toInt(), 0xFF6AFFDA.toInt(), 0xFF71B8FF.toInt()))
        }
        return LayerDrawable(arrayOf(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    fun thumbDrawable(): Drawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        if (theme == MobileTheme.AQUA) intArrayOf(Color.WHITE, 0xFFB5D4E8.toInt(), Color.WHITE)
        else intArrayOf(0xFFFFFFFF.toInt(), 0xFF99FFE7.toInt())).apply {
        shape = GradientDrawable.OVAL
        setSize(dp(20), dp(20))
        setStroke(dp(1), if (theme == MobileTheme.AQUA) 0xFF5A87AC.toInt() else 0xFF68B2CE.toInt())
    }

    fun animateReveal(view: View) {
        // Retro themes stay instant; motion lives in clicks, not transitions.
        if (!theme.modern || !moving) return
        view.animate().cancel()
        view.alpha = .25f
        view.translationY = dp(if (theme == MobileTheme.SUPER) 15 else 5).toFloat()
        view.animate().alpha(1f).translationY(0f)
            .setDuration(if (fullMotion && theme == MobileTheme.SUPER) 450 else 220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun prepareSectionTransition(panel: ViewGroup) {
        if (moving) TransitionManager.beginDelayedTransition(panel,
            AutoTransition().setDuration(if (theme == MobileTheme.SUPER && fullMotion) 320 else 180))
    }

    fun attachWindow(window: Window) {
        // Every theme gets click bursts now: neon for modern, retro XP
        // glints for Bliss/Dark. Motion setting + system animator still gate.
        if (layers.any { it.window.get() === window }) return
        val decor = window.decorView as? ViewGroup ?: return
        val original = window.callback ?: return
        val layer = WindowLayer(window, decor, original)
        layers.add(layer)
        decor.overlay.add(layer.effects)
        layer.layout()
        decor.addOnLayoutChangeListener(layer.layoutListener)
        decor.addOnAttachStateChangeListener(layer.attachListener)
        window.callback = layer.callback
    }

    fun resume() {
        resumed = true
        previousNanos = 0
        lastActiveNanos = SystemClock.elapsedRealtimeNanos()
        if (!moving) {
            materials.forEach { (view, ref) -> ref.get()?.let { it.motion = false; it.phase = 0f; view.invalidate() } }
            return
        }
        // Retro themes have no ambient animation; start their clock on a tap.
        attachWindow(activity.window)
        if (theme.modern || layers.any { it.effects.field.rings.isNotEmpty() || it.effects.field.sparks.isNotEmpty() }) postFrame()
    }

    fun pause() {
        resumed = false
        if (posted) Choreographer.getInstance().removeFrameCallback(this)
        posted = false
        materials.keys.toList().forEach { it.animate().cancel(); it.scaleX = 1f; it.scaleY = 1f }
    }

    fun close() {
        pause()
        layers.toList().forEach { it.detach() }
        layers.clear(); materials.clear()
        ThemeRenderers.remove(activity)
    }

    private fun postFrame() {
        if (!posted && resumed) { posted = true; Choreographer.getInstance().postFrameCallback(this) }
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (!resumed || !moving) return
        val dt = if (previousNanos == 0L) 1f / 60f else ((frameTimeNanos - previousNanos) / 1_000_000_000f).coerceIn(0f, .05f)
        previousNanos = frameTimeNanos
        time += dt
        // Adaptive pacing: full speed right after interaction, then settle to
        // a cheap heartbeat so idle screens (and Espresso) are never starved.
        val liveBurst = layers.any { layer ->
            layer.effects.field.rings.isNotEmpty() || layer.effects.field.sparks.any { !it.ambient }
        }
        val busy = liveBurst || frameTimeNanos - lastActiveNanos < 4_000_000_000L
        val minimum = when {
            !fullMotion -> 66_000_000
            busy -> 15_000_000
            else -> 125_000_000
        }
        if (frameTimeNanos - lastDrawNanos >= minimum) {
            val drawDt = ((frameTimeNanos - lastDrawNanos) / 1_000_000_000f).coerceIn(0f, .05f)
            lastDrawNanos = frameTimeNanos
            frameCount++
            materials.entries.toList().forEach { (view, ref) ->
                val art = ref.get() ?: return@forEach
                if (art.animated && view.isAttachedToWindow && view.isShown && view.getLocalVisibleRect(visible)) {
                    art.phase = time; art.motion = fullMotion; art.invalidateSelf()
                }
            }
            layers.forEach { layer ->
                layer.effects.reduced = !fullMotion
                layer.effects.gentle = clickFx == "gentle"
                if (layer.decor.get()?.isShown == true) layer.effects.tick(drawDt)
            }
        }
        if (theme.modern || layers.any { it.effects.field.rings.isNotEmpty() || it.effects.field.sparks.isNotEmpty() }) {
            postFrame()
        } else {
            previousNanos = 0L
            lastDrawNanos = 0L
        }
    }

    private inner class WindowLayer(window: Window, decor: ViewGroup, val original: Window.Callback) {
        val window = WeakReference(window)
        val decor = WeakReference(decor)
        val effects = ThemeEffectsLayer(activity, theme)
        private var downX = 0f; private var downY = 0f
        private var target = WeakReference<View>(null)
        private var downAt = 0L
        val callback = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                touch(event)
                // Delegate unchanged: controls, accessibility and RecyclerView gestures keep working.
                return original.dispatchTouchEvent(event)
            }
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layout() }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { layout() }
            override fun onViewDetachedFromWindow(v: View) { detach(); layers.remove(this@WindowLayer) }
        }

        fun layout() {
            val view = decor.get() ?: return
            effects.layout(0, 0, view.width, view.height)
        }

        private fun touch(e: MotionEvent) {
            if (!moving || clickFx == "off") return
            val gentleTap = clickFx == "gentle"
            val parent = decor.get() ?: return
            val xy = IntArray(2); parent.getLocationOnScreen(xy)
            val x = e.rawX - xy[0]; val y = e.rawY - xy[1]
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; downAt = SystemClock.uptimeMillis()
                    lastActiveNanos = SystemClock.elapsedRealtimeNanos()
                    target = WeakReference(hit(parent, e.rawX.toInt(), e.rawY.toInt()))
                    // Gentle clicks skip the press squash; full clicks squash.
                    if (!gentleTap) target.get()?.takeIf { it !is SeekBar }?.let {
                        it.animate().cancel()
                        it.animate().scaleX(if (theme == MobileTheme.SUPER) .95f else .98f)
                            .scaleY(if (theme == MobileTheme.SUPER) .95f else .98f).setDuration(100).start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.get()?.let {
                        it.animate().scaleX(1f).scaleY(1f).setDuration(if (fullMotion) 350 else 120)
                            .setInterpolator(android.view.animation.OvershootInterpolator(if (theme == MobileTheme.SUPER) 1.7f else .7f)).start()
                    }
                    if (e.actionMasked == MotionEvent.ACTION_UP && hypot(x - downX, y - downY) < dp(18) &&
                        SystemClock.uptimeMillis() - downAt < 800) {
                        interactionCount++
                        lastActiveNanos = SystemClock.elapsedRealtimeNanos()
                        effects.tap(x, y)
                        postFrame()
                    }
                    target.clear()
                }
            }
        }

        fun detach() {
            decor.get()?.let {
                it.overlay.remove(effects)
                it.removeOnLayoutChangeListener(layoutListener)
                it.removeOnAttachStateChangeListener(attachListener)
            }
            window.get()?.let { if (it.callback === callback) it.callback = original }
            effects.field.clear()
        }
    }

    private fun hit(view: View, x: Int, y: Int): View? {
        if (!view.isShown || !view.getGlobalVisibleRect(visible) || !visible.contains(x, y)) return null
        if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) {
            hit(view.getChildAt(i), x, y)?.let { return it }
        }
        return if (view.isClickable && view.isEnabled) view else null
    }
}
