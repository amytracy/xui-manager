package dev.autorasqr.xuimanager.domain.model

/** Look of the whole app, chosen on the Appearance screen. */
enum class UiStyle {
    /** Colours from the wallpaper (Android 12+), compact top bars. */
    MATERIAL_YOU,

    /** Samsung-like: large titles over the top of the screen that fold away on scroll, rounder blocks, blue accent. */
    ONE_UI,
    ;

    companion object {
        val DEFAULT = MATERIAL_YOU

        /** A stored name; anything unknown (a style from a newer version, a damaged file) falls back to the default. */
        fun parse(name: String?): UiStyle = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
