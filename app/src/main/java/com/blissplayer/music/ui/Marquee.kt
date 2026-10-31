package com.blissplayer.music.ui

import android.text.TextUtils
import android.widget.TextView

/**
 * Sideways loop for cut-off song titles and artist lines. Short text that
 * fits never moves. Each view scrolls on its own loop.
 */
fun TextView.marquee() {
    ellipsize = TextUtils.TruncateAt.MARQUEE
    setSingleLine(true)
    marqueeRepeatLimit = -1
    isFocusable = false
    isFocusableInTouchMode = false
    isSelected = true
}
