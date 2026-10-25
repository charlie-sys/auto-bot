package com.autobot.reader

import org.junit.Assert.*
import org.junit.Test

class SpeechPolicyTest {
    @Test fun disabledRestrictionPreservesNormalPlayback() {
        assertTrue(SpeechPolicy.canSpeak(false, false, listOf(2)))
        assertTrue(SpeechPolicy.canSpeak(false, false, emptyList()))
    }
    @Test fun restrictionBlocksSpeakerWiredUsbAndCallOnlyConnections() {
        for (outputs in listOf(emptyList(), listOf(2), listOf(3, 4), listOf(22), listOf(7), listOf(27))) {
            assertFalse("Blocked outputs: $outputs", SpeechPolicy.canSpeak(false, true, outputs))
        }
    }
    @Test fun classicAndLeHeadsetsAllowPlayback() {
        assertTrue(SpeechPolicy.canSpeak(false, true, listOf(2, 8)))
        assertTrue(SpeechPolicy.canSpeak(false, true, listOf(2, 26)))
    }
    @Test fun disconnectionClosesGate() {
        assertTrue(SpeechPolicy.canSpeak(false, true, listOf(8)))
        assertFalse(SpeechPolicy.canSpeak(false, true, listOf(2)))
    }
    @Test fun muteAlwaysWins() {
        for (only in listOf(false, true)) {
            assertFalse(SpeechPolicy.canSpeak(true, only, listOf(8, 26)))
            assertFalse(SpeechPolicy.canSpeak(true, only, emptyList()))
        }
    }
}
