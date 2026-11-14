package com.openminis.app.mikova.vd

/** A touch position already routed to one virtual display. */
data class VdIslandTouch(val displayId: Int, val x: Float, val y: Float)

/** FIT_CENTER preview geometry to virtual-display coordinates. */
object VdIslandTouchMapper {
    fun map(
        displayId: Int,
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): VdIslandTouch? {
        if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0 ||
            displayWidth <= 0 || displayHeight <= 0 || !viewX.isFinite() || !viewY.isFinite()
        ) return null
        val scale = minOf(viewWidth.toFloat() / frameWidth, viewHeight.toFloat() / frameHeight)
        val drawnWidth = frameWidth * scale
        val drawnHeight = frameHeight * scale
        val left = (viewWidth - drawnWidth) / 2f
        val top = (viewHeight - drawnHeight) / 2f
        val localX = viewX - left
        val localY = viewY - top
        if (localX < 0f || localY < 0f || localX >= drawnWidth || localY >= drawnHeight) return null
        return VdIslandTouch(
            displayId = displayId,
            x = (localX / drawnWidth * displayWidth).coerceIn(0f, displayWidth - 1f),
            y = (localY / drawnHeight * displayHeight).coerceIn(0f, displayHeight - 1f),
        )
    }
}
