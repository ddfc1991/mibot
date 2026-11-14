package com.openminis.app.mikova.vd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VdIslandTouchMapperTest {
    @Test
    fun mapsFitCenterFrameToFullDisplayCoordinates() {
        val point = VdIslandTouchMapper.map(
            displayId = 17,
            viewX = 200f,
            viewY = 200f,
            viewWidth = 400,
            viewHeight = 400,
            frameWidth = 360,
            frameHeight = 640,
            displayWidth = 720,
            displayHeight = 1280,
        )
        assertEquals(VdIslandTouch(17, 360f, 640f), point)
    }

    @Test
    fun letterboxIsNotTreatedAsScreenInput() {
        val point = VdIslandTouchMapper.map(
            displayId = 8,
            viewX = 10f,
            viewY = 200f,
            viewWidth = 400,
            viewHeight = 400,
            frameWidth = 360,
            frameHeight = 640,
            displayWidth = 720,
            displayHeight = 1280,
        )
        assertNull(point)
    }

    @Test
    fun mapsEachPreviewOnlyToItsOwnDisplayId() {
        val first = VdIslandTouchMapper.map(3, 50f, 50f, 100, 100, 100, 100, 720, 1280)
        val second = VdIslandTouchMapper.map(9, 50f, 50f, 100, 100, 100, 100, 720, 1280)

        assertEquals(VdIslandTouch(3, 360f, 640f), first)
        assertEquals(VdIslandTouch(9, 360f, 640f), second)
    }

    @Test
    fun rejectsInvalidGeometryAndClampsLastPixel() {
        assertNull(VdIslandTouchMapper.map(1, 0f, 0f, 0, 100, 10, 10, 720, 1280))
        val last = VdIslandTouchMapper.map(1, 99.999f, 99.999f, 100, 100, 100, 100, 720, 1280)
        assertEquals(VdIslandTouch(1, 719f, 1279f), last)
    }
}
