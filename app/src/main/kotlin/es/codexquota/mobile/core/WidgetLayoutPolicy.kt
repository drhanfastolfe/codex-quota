package es.codexquota.mobile.core

/** Layout choices only: widget resizing never changes the refresh schedule or user settings. */
object WidgetLayoutPolicy {
    @JvmStatic fun showFullHeading(heightDp: Int): Boolean = heightDp >= 210
    @JvmStatic fun showMiniRefresh(widthDp: Int): Boolean = widthDp >= 220
    @JvmStatic fun showMiniTheme(widthDp: Int): Boolean = widthDp >= 290
}
