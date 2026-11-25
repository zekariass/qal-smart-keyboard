package com.qalsmartkeyboard.app.creative.cards

import androidx.core.content.FileProvider

/**
 * The card maker's own FileProvider (T8.7). A subclass, so the app's manifest can declare it next to the keyboard's
 * `drawings` provider (the manifest merger keys providers by class name). Paths: `xml/cards_file_paths.xml`.
 */
class CardFileProvider : FileProvider()
