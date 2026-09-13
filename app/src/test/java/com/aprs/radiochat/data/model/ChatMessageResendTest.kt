/*
 * RadioChat APRS — Android APRS client for KISS BLE radios,
 * DireWolf (KISS TCP) and APRS-IS.
 * Copyright (C) 2026 Luis Almonte (HI3LAG)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aprs.radiochat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which bubbles offer "Send again" on a long press.
 */
class ChatMessageResendTest {

    private fun msg(
        outgoing: Boolean,
        ack: MessageAckStatus,
        retries: Int = 0
    ) = ChatMessage(
        id = "uuid",
        from = "HI3LAG-7",
        to = "OTA-9",
        text = "hello",
        timestamp = 1_000L,
        isOutgoing = outgoing,
        source = if (outgoing) MessageSource.OUTGOING else MessageSource.RF_BLE,
        peer = "OTA-9",
        messageId = "01",
        ackStatus = ack,
        retryCount = retries
    )

    @Test
    fun `awaiting an ACK can be resent`() {
        assertTrue(msg(outgoing = true, ack = MessageAckStatus.SENT).canResend)
    }

    @Test
    fun `rejected can be resent`() {
        assertTrue(msg(outgoing = true, ack = MessageAckStatus.REJECTED).canResend)
    }

    @Test
    fun `a message sent without asking for an ACK can be resent`() {
        assertTrue(msg(outgoing = true, ack = MessageAckStatus.NONE).canResend)
    }

    @Test
    fun `delivered is never offered`() {
        assertFalse(msg(outgoing = true, ack = MessageAckStatus.DELIVERED).canResend)
    }

    @Test
    fun `incoming messages are never resendable`() {
        MessageAckStatus.entries.forEach { status ->
            assertFalse(
                "incoming should not be resendable for $status",
                msg(outgoing = false, ack = status).canResend
            )
        }
    }

    @Test
    fun `retry count defaults to zero and survives a copy`() {
        assertEquals(0, msg(outgoing = true, ack = MessageAckStatus.SENT).retryCount)
        val retried = msg(outgoing = true, ack = MessageAckStatus.SENT, retries = 2)
        assertEquals(3, retried.copy(retryCount = retried.retryCount + 1).retryCount)
    }
}
