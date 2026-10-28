package com.autobot.reader

import org.junit.Assert.*
import org.junit.Test

class VoiceQuestionTest {
    @Test fun ambientSpeechAndShortFragmentsDoNotConsumeSolves(){
        val p=VoiceQuestionPolicy()
        assertNotNull(p.inspect("你好",1000))
        assertNotNull(p.inspect("今天阳光很好我们出去走走",2000))
        assertNull(p.inspect("计算二加三等于多少",3000))
        assertNotNull(p.inspect("计算 二加三等于多少？",4000))
        assertNull(p.inspect("计算二加三等于多少",4000,true))
        assertNull(p.inspect("计算二加三等于多少",35000))
        p.reset();assertNull(p.inspect("计算二加三等于多少",36000))
    }
    @Test fun manualReviewCanSubmitUnusualQuestionsButStillEnforcesLength(){
        val p=VoiceQuestionPolicy()
        assertNull(p.inspect("水的化学式",0,true))
        assertNotNull(p.inspect("啊",0,true))
        assertNotNull(p.inspect("题".repeat(4001),0,true))
    }
    @Test fun automaticEndpointWaitsTwoSecondsAndAcceptsDelayedRecognition(){
        val endpoint=VoiceEndpoint(true)
        repeat(5){assertNull(endpoint.feed(.05,1600,false))}
        assertFalse(endpoint.active)
        assertNull(endpoint.feed(.001,1600,true));assertTrue(endpoint.active)
        repeat(18){assertNull(endpoint.feed(.001,1600,true))}
        assertEquals("done",endpoint.feed(.001,1600,true));assertFalse(endpoint.active)
    }
    @Test fun noiseWithoutTranscriptNeverStartsAutomaticQuestion(){
        val endpoint=VoiceEndpoint(true)
        repeat(1000){assertNull(endpoint.feed(.04,1600,false))}
        assertFalse(endpoint.active)
    }
    @Test fun mouthStartsOneSegmentSilenceFinishesWithoutSecondGesture(){
        val endpoint=VoiceEndpoint(false);endpoint.reset(true)
        assertTrue(endpoint.active)
        repeat(5){assertNull(endpoint.feed(.05,1600,true))}
        repeat(19){assertNull(endpoint.feed(.001,1600,true))}
        assertEquals("done",endpoint.feed(.001,1600,true))
        repeat(30){assertNull(endpoint.feed(.05,1600,true))}
        assertFalse(endpoint.active)
    }
    @Test fun silentManualRecordingTimesOutAndLongSpeechIsBounded(){
        val endpoint=VoiceEndpoint(false);endpoint.reset(true)
        repeat(79){assertNull(endpoint.feed(.001,1600,false))}
        assertEquals("empty",endpoint.feed(.001,1600,false))
        endpoint.reset(true)
        repeat(599){assertNull(endpoint.feed(.05,1600,true))}
        assertEquals("limit",endpoint.feed(.05,1600,true))
    }
}
