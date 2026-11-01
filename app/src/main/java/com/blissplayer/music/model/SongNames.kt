package com.blissplayer.music.model

import java.util.Locale

object SongNames {
    /** Remove uploader quotation wrappers, not apostrophes in Don't or O'Connor. */
    fun unquote(value: String): String {
        var text = value.trim().trim('"', '“', '”', '„', '＂', '«', '»').trim()
        while (text.length > 2 && ((text.first() == '\'' && text.last() == '\'' && "' - '" !in text) ||
                (text.first() == '‘' && text.last() == '’') ||
                (text.first() == '「' && text.last() == '」'))) {
            text = text.substring(1, text.lastIndex).trim()
        }
        return text.ifBlank { value.trim() }
    }

    fun sortKey(value: String): String = unquote(value).trimStart { !it.isLetterOrDigit() }
        .lowercase(Locale.ROOT)
}
