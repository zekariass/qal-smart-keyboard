package com.qalsmartkeyboard.ime.wordart

import com.qalsmartkeyboard.ime.R

/** The shown and spoken title of a category chip (shared by the keyboard and the companion app). */
fun WordArtCategory.titleRes(): Int = when (this) {
    WordArtCategory.POPULAR -> R.string.wordart_category_popular
    WordArtCategory.BIRTHDAY -> R.string.wordart_category_birthday
    WordArtCategory.LOVE -> R.string.wordart_category_love
    WordArtCategory.WEDDING -> R.string.wordart_category_wedding
    WordArtCategory.CONGRATS -> R.string.wordart_category_congrats
    WordArtCategory.THANKS -> R.string.wordart_category_thanks
    WordArtCategory.HOLIDAYS -> R.string.wordart_category_holidays
    WordArtCategory.ANNOUNCEMENTS -> R.string.wordart_category_announcements
    WordArtCategory.SOCIAL -> R.string.wordart_category_social
    WordArtCategory.NAMES -> R.string.wordart_category_names
    WordArtCategory.LETTERS -> R.string.wordart_category_letters
}

fun WordArtNotice.messageRes(): Int? = when (this) {
    WordArtNotice.NONE -> null
    WordArtNotice.COPIED -> R.string.wordart_notice_copied
    WordArtNotice.COPY_FAILED -> R.string.wordart_notice_copy_failed
    WordArtNotice.INSERTED -> R.string.wordart_notice_inserted
    WordArtNotice.NOTHING_TO_USE -> R.string.wordart_notice_nothing
}
