package com.autobot.reader

import org.junit.Assert.*
import org.junit.Test

class AutoFramingControllerTest {
    private fun decision(regions: List<TextRegion>, ratio: Float = 1f): FramingChange? {
        val controller = AutoFramingController()
        controller.observe(regions, ratio, 1f, 8f, 1000L)
        return controller.observe(regions, ratio, 1f, 8f, 2000L)
    }
    @Test fun smallCenteredQuestionZoomsInGradually() {
        val result = decision(listOf(TextRegion(.35f, .4f, .65f, .6f)))!!
        assertTrue(result.ratio > 1f && result.ratio <= 1.22f)
    }
    @Test fun offCenterTextIsNotCroppedByZoom() {
        assertNull(decision(listOf(TextRegion(.05f, .4f, .25f, .6f))))
    }
    @Test fun multipleQuestionsAreKeptInFrame() {
        assertNull(decision(listOf(TextRegion(.2f, .1f, .8f, .4f), TextRegion(.2f, .6f, .8f, .9f))))
    }
    @Test fun edgeTextCausesZoomOutRatherThanMoreCropping() {
        assertTrue(decision(listOf(TextRegion(.01f, .2f, .8f, .8f)), 2f)!!.ratio < 2f)
    }
    @Test fun hardwareLimitAndAutomaticCeilingAreRespected() {
        val c = AutoFramingController()
        val box = listOf(TextRegion(.35f, .4f, .65f, .6f))
        c.observe(box, 1f, 1f, 1.1f, 1000)
        assertEquals(1.1f, c.observe(box, 1f, 1f, 1.1f, 2000)!!.ratio, .001f)
        assertNull(decision(box, 3f))
    }
    @Test fun manualZoomIsNotImmediatelyUndone() {
        val c = AutoFramingController()
        c.manualOverride(1000)
        val box = listOf(TextRegion(.35f, .4f, .65f, .6f))
        c.observe(box, 1f, 1f, 8f, 2000)
        assertNull(c.observe(box, 1f, 1f, 8f, 3000))
    }
    @Test fun emptySceneSearchIsBoundedAndReturnsToWideView() {
        val c = AutoFramingController()
        var zoom = 1f
        val changes = mutableListOf<Float>()
        for (i in 0..12) c.observe(emptyList(), zoom, 1f, 8f, i * 1000L)?.let { zoom = it.ratio; changes.add(zoom) }
        assertEquals(listOf(1.25f, 1.5f, 1f), changes)
    }
    @Test fun focusCoordinatesHandleAllImageRotations() {
        for ((rotation, expected) in listOf(0 to (.2f to .7f), 90 to (.7f to .8f), 180 to (.8f to .3f), 270 to (.3f to .2f))) {
            val actual = AutoFramingController.surfacePoint(.2f, .7f, rotation)
            assertEquals(expected.first, actual.first, .001f)
            assertEquals(expected.second, actual.second, .001f)
        }
    }
}
