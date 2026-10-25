package com.autobot.reader
import org.junit.Assert.*
import org.junit.Test

class NetworkFrameGateTest {
    @Test fun repeatedOrOutOfOrderFramesNeverTriggerAnotherAnalysis(){
        val gate=NetworkFrameGate()
        assertTrue(gate.accept("100",0,10));repeat(100){assertFalse(gate.accept("100",0,10))}
        assertFalse(gate.accept("99",0,10));assertTrue(gate.accept("101",0,10))
    }
    @Test fun expiredFramesAndMalformedMetadataAreRejected(){
        val gate=NetworkFrameGate()
        assertFalse(gate.accept("1",2400,101));assertFalse(gate.accept("1",-1,1))
        assertFalse(gate.accept("broken",0,0));assertFalse(gate.accept("1",Long.MAX_VALUE,Long.MAX_VALUE))
        assertTrue(gate.accept("1",1000,1000))
    }
    @Test fun reconnectAllowsANewClockWithoutRevivingOldGateState(){
        val gate=NetworkFrameGate();assertTrue(gate.accept("100",0,10));gate.reset()
        assertTrue(gate.accept("50",0,10));assertFalse(gate.accept("50",0,10))
    }
}
