package com.autobot.reader

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class SoundFeaturesTest {
    private fun tone(frequency:Double,gain:Double=.4,length:Int=8800):ShortArray=ShortArray(16000){i->
        val t=i-2400
        if(t !in 0 until length)0 else (32767*gain*sin(2*PI*frequency*t/16000)*sin(PI*t/length).coerceAtLeast(0.0)).toInt().toShort()
    }
    @Test fun matchingAllowsVolumeChangeAndModestTimingChange(){val ref=SoundFeatures.extract(tone(500.0));assertTrue(SoundFeatures.similarity(ref,SoundFeatures.extract(tone(500.0,.15)))>.95f);assertTrue(SoundFeatures.similarity(ref,SoundFeatures.extract(tone(500.0,.3,9600)))>.8f)}
    @Test fun differentSoundsAndDurationMismatchAreRejected(){val ref=SoundFeatures.extract(tone(500.0));val other=SoundFeatures.extract(tone(1800.0));assertTrue("Different tone similarity: ${SoundFeatures.similarity(ref,other)}",SoundFeatures.similarity(ref,other)<.7f);assertEquals(0f,SoundFeatures.similarity(ref,other.copy(durationMs=3000)),.001f)}
    @Test fun silenceTinyAudioAndConstantNoiseAreNotEnrollments(){assertTrue(runCatching{SoundFeatures.extract(ShortArray(16000))}.isFailure);assertTrue(runCatching{SoundFeatures.extract(ShortArray(300))}.isFailure);assertTrue(runCatching{SoundFeatures.extract(ShortArray(16000){12000})}.isFailure)}
    @Test fun quietMustPrecedeEventAndOngoingSoundFiresOnlyOnce(){val segmenter=SoundEventSegmenter();val quiet=ShortArray(512);val loud=ShortArray(512){12000};repeat(30){assertNull(segmenter.feed(loud))};repeat(32){segmenter.feed(quiet)};repeat(10){assertNull(segmenter.feed(loud))};var events=0;repeat(12){if(segmenter.feed(quiet)!=null)events++};assertEquals(1,events)}
    @Test fun busyAudioIsDiscardedInsteadOfQueued(){val s=SoundEventSegmenter();val quiet=ShortArray(512);val loud=ShortArray(512){12000};repeat(32){s.feed(quiet)};repeat(8){s.feed(loud)};s.feed(loud,false);repeat(20){assertNull(s.feed(loud))};repeat(12){assertNull(s.feed(quiet))}}
    @Test fun cooldownAndEligibilityFenceMatchEvents(){val gate=SoundTriggerGate();assertFalse(gate.accept(0,.99f,.8f,false));assertFalse(gate.accept(100,.6f,.8f,true));assertTrue(gate.accept(200,.95f,.8f,true));assertFalse(gate.accept(2000,.99f,.8f,true));assertTrue(gate.accept(3300,.99f,.8f,true));assertFalse(gate.accept(6600,Float.NaN,.8f,true))}
}
