package com.autobot.reader

import org.junit.Assert.*
import org.junit.Test

class MouthGestureTest {
    private fun arm(g:MouthGestureGate,start:Long=0){g.observe(start,.02f);g.observe(start+150,.02f);g.observe(start+300,.02f)}
    private fun hold(g:MouthGestureGate,start:Long):MouthGestureGate.State{g.observe(start,.4f);g.observe(start+200,.4f);g.observe(start+400,.4f);return g.observe(start+600,.4f)}
    @Test fun requiresClosedThenContinuousOpenAndFiresOnce(){val g=MouthGestureGate();assertFalse(hold(g,0).fire);arm(g,800);assertTrue(hold(g,1200).fire);for(t in 2000L..6000L step 200)assertFalse(g.observe(t,.4f).fire)}
    @Test fun shortOpenAndMissingFramesCannotTrigger(){val g=MouthGestureGate();arm(g);g.observe(400,.4f);g.observe(600,.4f);g.observe(700,.02f);assertFalse(g.observe(800,.4f).fire);assertFalse(g.observe(1500,.4f).fire);assertFalse(hold(g,1600).fire)}
    @Test fun cooldownAndClosedResetAreBothRequired(){val g=MouthGestureGate();arm(g);assertTrue(hold(g,400).fire);arm(g,1100);assertFalse(hold(g,1500).fire);arm(g,3600);assertTrue(hold(g,4000).fire)}
    @Test fun blockedGestureDoesNotQueue(){val g=MouthGestureGate();arm(g);g.observe(400,.4f,false);assertFalse(hold(g,600).fire);arm(g,1400);assertTrue(hold(g,1800).fire)}
    @Test fun invalidInputOrAmbiguousMouthResetsHold(){val g=MouthGestureGate();arm(g);g.observe(400,.4f);g.observe(600,Float.NaN);assertFalse(hold(g,800).fire);arm(g,1600);g.observe(2000,.4f);g.observe(2200,.15f);g.observe(2400,.4f);g.observe(2600,.4f);assertFalse(g.observe(2800,.4f).fire);assertTrue(g.observe(3000,.4f).fire)}
    @Test fun apertureIsInvariantToRotationAndRejectsTinyFaces(){
        val upper=(0..8).map{MouthPoint(it*10f,0f)};val lower=(0..8).map{MouthPoint(it*10f,24f)}
        assertEquals(.3f,MouthAperture.measure(upper,lower)!!,.001f)
        fun rotate(points:List<MouthPoint>)=points.map{MouthPoint(-it.y,it.x)}
        assertEquals(.3f,MouthAperture.measure(rotate(upper),rotate(lower))!!,.001f)
        assertNull(MouthAperture.measure(emptyList(),lower));assertNull(MouthAperture.measure(upper.map{MouthPoint(it.x/10,it.y)},lower.map{MouthPoint(it.x/10,it.y)}))
    }
}
