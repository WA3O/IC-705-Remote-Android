package com.example.ic_705remote2
import org.junit.Assert.*
import org.junit.Test

class AuthRenewalReplyTest {
    private fun exchange(sequence: Int = 2): Pair<ByteArray, ByteArray> {
        val request = ByteArray(64)
        request[0] = 0x40; request[19] = 0x30; request[20] = 1; request[21] = 5
        request[23] = sequence.toByte(); request[24] = (sequence ushr 8).toByte()
        for (i in 8..15) request[i] = (i * 7).toByte()
        for (i in 26..31) request[i] = (i * 3).toByte()
        val reply = request.copyOf(); reply[20] = 2
        for (i in 0..3) { reply[8+i] = request[12+i]; reply[12+i] = request[8+i] }
        return request to reply
    }
    @Test fun acceptsMatchingReplyAcrossSequenceBoundary() {
        for (seq in listOf(0, 2, 255, 256, 65535)) {
            val (request, reply) = exchange(seq)
            assertTrue(AuthRenewalReply.matches(reply, request))
        }
    }
    @Test fun rejectsStaleSessionAndSequence() {
        val (request, reply) = exchange()
        for (index in listOf(8, 12, 23, 24, 26, 31)) {
            val stale = reply.copyOf(); stale[index] = (stale[index].toInt() xor 1).toByte()
            assertFalse("field $index", AuthRenewalReply.matches(stale, request))
        }
    }
    @Test fun rejectsRequestsOtherCommandsAndTruncatedPackets() {
        val (request, reply) = exchange()
        assertFalse(AuthRenewalReply.matches(request, request))
        for (size in listOf(0, 16, 32, 63, 65)) assertFalse(AuthRenewalReply.matches(reply.copyOf(size), request))
        for (index in listOf(0, 4, 19, 20, 21)) {
            val invalid = reply.copyOf(); invalid[index] = (invalid[index].toInt() xor 1).toByte()
            assertFalse(AuthRenewalReply.matches(invalid, request))
        }
    }
}
