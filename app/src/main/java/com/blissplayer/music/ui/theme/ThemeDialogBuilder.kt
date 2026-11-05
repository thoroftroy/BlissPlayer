package com.blissplayer.music.ui.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog

/** All app dialogs use the same materials and input FX, including dynamically created forms. */
class ThemeDialogBuilder(context: Context) : AlertDialog.Builder(context) {
    override fun create(): AlertDialog {
        val dialog = super.create()
        val renderer = ThemeRenderers.get(context) ?: return dialog
        if (!renderer.theme.modern) return dialog
        val window = dialog.window ?: return dialog
        val decor = window.decorView
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post {
                    if (!dialog.isShowing) return@post
                    val density = v.resources.displayMetrics.density
                    window.setBackgroundDrawable(InsetDrawable(renderer.drawable(MaterialRole.DIALOG),
                        (4 * density).toInt()))
                    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.attributes = window.attributes.apply {
                        dimAmount = if (renderer.theme == MobileTheme.AQUA) .45f else .65f
                    }
                    if (Build.VERSION.SDK_INT >= 31 && renderer.theme == MobileTheme.AQUA) {
                        // Genuine translucent dialog windows; devices without cross-window blur
                        // retain the legible frosted/metal material instead.
                        window.setBackgroundBlurRadius((20 * density).toInt())
                    }
                    renderer.styleTree(v)
                    // Dialog actions get real roles: confirm pops, cancel recedes.
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let {
                        it.themeRole(MaterialRole.PRIMARY)
                        renderer.control(it, MaterialRole.PRIMARY)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.let {
                        it.themeRole(MaterialRole.SECONDARY)
                        renderer.control(it, MaterialRole.SECONDARY)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let {
                        it.themeRole(MaterialRole.CONTROL)
                        renderer.control(it, MaterialRole.CONTROL)
                    }
                    // AlertDialog's default content panel must not cover the themed window.
                    val panels = listOf("parentPanel", "topPanel", "contentPanel", "customPanel", "buttonPanel")
                    panels.forEach { name ->
                        val id = v.resources.getIdentifier(name, "id", context.packageName)
                        if (id != 0) v.findViewById<View>(id)?.setBackgroundColor(Color.TRANSPARENT)
                    }
                    renderer.attachWindow(window)
                    renderer.animateReveal(v.findViewById<ViewGroup>(android.R.id.content) ?: v)
                }
            }
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
            }
        })
        return dialog
    }

    override fun show(): AlertDialog = create().also { it.show() }
}
