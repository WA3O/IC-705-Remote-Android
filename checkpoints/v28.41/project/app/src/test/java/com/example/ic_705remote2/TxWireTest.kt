package com.example.ic_705remote2

import org.junit.Assert.*
import org.junit.Test

class TxWireTest {
    @Test fun bcdCoversAllRadioLevels() {
        for (n in 0..255) {
            val b = TxWire.levelBcd(n)
            assertEquals(n, b[0] * 100 + (b[1] ushr 4) * 10 + (b[1] and 15))
            assertTrue((b[1] and 15) <= 9)
        }
        assertArrayEquals(intArrayOf(2, 0x55), TxWire.levelBcd(999))
        assertArrayEquals(intArrayOf(0, 0), TxWire.levelBcd(-1))
    }
    @Test fun audioPacketsMatchIcomHeadersAndPreservePayload() {
        val pcm = ByteArray(1920) { (it % 251).toByte() }
        val first = TxWire.audioPacket(pcm, 0, 1364, 0x1234, 0x5678, 0x01020304, 0x05060708)
        assertEquals(1388, first.size)
        assertArrayEquals(byteArrayOf(0x6c, 5, 0, 0, 0, 0, 0x34, 0x12,
            1,2,3,4,5,6,7,8,0x80.toByte(),0,0x56,0x78,0,0,5,0x54), first.copyOfRange(0,24))
        val second = TxWire.audioPacket(pcm,1364,556,0x1235,0x5679,0x01020304,0x05060708)
        assertEquals(580,second.size)
        assertEquals(0x44,second[0].toInt()); assertEquals(2,second[1].toInt())
        assertEquals(2,second[22].toInt()); assertEquals(0x2c,second[23].toInt())
        assertArrayEquals(pcm,first.copyOfRange(24,first.size)+second.copyOfRange(24,second.size))
    }
    @Test fun sequenceWrapUsesUnsigned16Bits() {
        val p=TxWire.audioPacket(ByteArray(1920),0,1364,65535,65535,0,0)
        assertEquals(255,p[6].toInt() and 255); assertEquals(255,p[7].toInt() and 255)
        val next=TxWire.audioPacket(ByteArray(1920),0,1364,0,0,0,0)
        assertEquals(0,next[6].toInt()); assertEquals(0,next[18].toInt())
    }
    @Test fun meterMatchesIcomCalibrationAndIsMonotonic() {
        assertEquals(0,TxWire.powerPercent(0))
        assertEquals(50,TxWire.powerPercent(143))
        assertEquals(100,TxWire.powerPercent(213))
        assertEquals(100,TxWire.powerPercent(255))
        for(n in 1..255) assertTrue(TxWire.powerPercent(n)>=TxWire.powerPercent(n-1))
    }
}
