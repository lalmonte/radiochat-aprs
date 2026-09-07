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
package com.aprs.radiochat.data.repository

import android.util.Log
import com.aprs.radiochat.data.aprs.AprsMessageCodec
import com.aprs.radiochat.data.aprs.AprsPacketParser
import com.aprs.radiochat.data.aprsis.AprsIsClient
import com.aprs.radiochat.data.aprsis.OwnTransmissionLog
import com.aprs.radiochat.data.aprsis.Tnc2Codec
import com.aprs.radiochat.data.kiss.KissFrameHub
import com.aprs.radiochat.data.kiss.KissTransport
import com.aprs.radiochat.data.model.AprsIsConnectionState
import com.aprs.radiochat.data.model.AprsPacket
import com.aprs.radiochat.data.model.ChatAck
import com.aprs.radiochat.data.model.ChatMessage
import com.aprs.radiochat.data.model.MessageAckStatus
import com.aprs.radiochat.data.model.MessageSource
import com.aprs.radiochat.data.model.TcpTncConnectionState
import com.aprs.radiochat.data.notification.AprsNotificationManager
import com.aprs.radiochat.data.tnc.TcpKissTncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * APRS chat: RX over KISS (BLE/TCP) + APRS-IS.
 * TX: APRS-IS when verified; otherwise KISS TCP (DireWolf) when connected.
 */
class ChatRepository(
    private val kissHub: KissFrameHub,
    private val tcpTnc: TcpKissTncClient,
    private val aprsIs: AprsIsClient,
    private val ownTx: OwnTransmissionLog,
    private val parser: AprsPacketParser,
    private val store: ChatStore,
    private val notifications: AprsNotificationManager,
    private val myCallsign: () -> String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val msgSeq = AtomicInteger(1)
    private val persistMutex = Mutex()
    private var persistJob: Job? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    private val recentAcks = ConcurrentHashMap<String, Long>()

    @Volatile
    private var activePeer: String? = null

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = store.load()
            _messages.update { current ->
                if (current.isEmpty()) loaded else mergeLoaded(loaded, current)
            }
            Log.i(TAG, "Loaded ${loaded.size} chat messages")
        }
        scope.launch {
            kissHub.frames.collect { frame ->
                when (val packet = parser.parseKissPayload(frame.payload)) {
                    is AprsPacket.TextMessage -> {
                        val src = when (frame.transport) {
                            KissTransport.BLE -> MessageSource.RF_BLE
                            KissTransport.TCP -> MessageSource.RF_TCP
                        }
                        Log.d(
                            TAG,
                            "RX ${frame.transport} msg ${packet.sourceCall} → " +
                                "${packet.addressee}: ${packet.messageText}"
                        )
                        handleIncomingMessage(packet, src)
                    }
                    is AprsPacket.Other -> {
                        val ack = AprsMessageCodec.extractAck(packet.rawInfo) ?: return@collect
                        val src = when (frame.transport) {
                            KissTransport.BLE -> MessageSource.RF_BLE
                            KissTransport.TCP -> MessageSource.RF_TCP
                        }
                        handleIncomingMessage(
                            AprsPacket.TextMessage(
                                sourceCall = packet.sourceCall,
                                destCall = packet.destCall,
                                rawInfo = packet.rawInfo,
                                addressee = packet.destCall,
                                messageText = packet.rawInfo.trim(),
                                messageId = ack.messageId.ifBlank { null }
                            ),
                            src
                        )
                    }
                    null -> Log.d(
                        TAG,
                        "RX ${frame.transport} ${frame.payload.size}B not parseable"
                    )
                    else -> Unit
                }
            }
        }
        scope.launch {
            aprsIs.incomingLines.collect { line ->
                val frame = Tnc2Codec.parse(line) ?: return@collect
                val inner = Tnc2Codec.effective(frame.source, frame.destination, frame.info)
                val msg = AprsMessageCodec.parse(inner.info)
                val bareAck = if (msg == null) AprsMessageCodec.extractAck(inner.info) else null
                if (msg == null && bareAck == null) return@collect
                val packet = AprsPacket.TextMessage(
                    sourceCall = inner.source,
                    destCall = inner.destination,
                    rawInfo = inner.info,
                    addressee = msg?.addressee ?: inner.destination,
                    messageText = msg?.text ?: inner.info.trim(),
                    messageId = msg?.messageId ?: bareAck?.messageId
                )
                handleIncomingMessage(packet, MessageSource.INTERNET)
            }
        }
    }

    fun messagesForPeer(peer: String): List<ChatMessage> {
        val key = peer.uppercase()
        return _messages.value.filter { it.peer.equals(key, ignoreCase = true) }
            .sortedBy { it.timestamp }
    }

    fun openConversation(peer: String) {
        val key = peer.uppercase()
        activePeer = key
        notifications.cancelForPeer(key)
        markConversationRead(key)
    }

    fun closeConversation() {
        activePeer = null
    }

    fun markConversationRead(peer: String) {
        val key = peer.uppercase()
        var changed = false
        _messages.update { list ->
            list.map { msg ->
                if (msg.peer.equals(key, ignoreCase = true) && !msg.isRead) {
                    changed = true
                    msg.copy(isRead = true)
                } else {
                    msg
                }
            }
        }
        if (changed) schedulePersist()
    }

    fun deleteConversation(peer: String) {
        val key = peer.uppercase()
        _messages.update { list -> list.filterNot { it.peer.equals(key, ignoreCase = true) } }
        schedulePersist()
        Log.i(TAG, "Conversation deleted: $key")
    }

    fun deleteAllConversations() {
        _messages.value = emptyList()
        scope.launch(Dispatchers.IO) {
            persistMutex.withLock { store.clear() }
        }
        Log.i(TAG, "Chat history cleared completely")
    }

    fun sendMessage(toCallsign: String, text: String): Boolean {
        val body = text.trim()
        val to = toCallsign.uppercase().trim()
        if (body.isEmpty() || to.isBlank()) {
            _sendError.value = "Recipient and text are required"
            return false
        }

        val myCall = myCallsign().ifBlank { "N0CALL" }
        val msgId = "%02d".format(msgSeq.getAndIncrement() % 100)
        val path = resolveTxPath() ?: return false

        val ok = when (path) {
            TxPath.APRS_IS -> sendViaInternet(myCall, to, body, msgId)
            TxPath.KISS_TCP -> sendViaKissTcp(to, body, msgId)
        }
        if (!ok) return false

        _sendError.value = null
        append(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                from = myCall,
                to = to,
                text = body,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                source = when (path) {
                    TxPath.APRS_IS -> MessageSource.OUTGOING
                    TxPath.KISS_TCP -> MessageSource.OUTGOING_TCP
                },
                peer = to,
                isRead = true,
                messageId = msgId,
                ackStatus = MessageAckStatus.SENT
            )
        )
        return true
    }

    fun clearSendError() {
        _sendError.value = null
    }

    private fun resolveTxPath(): TxPath? {
        val isState = aprsIs.connectionState.value
        if (isState is AprsIsConnectionState.Connected && isState.verified) {
            return TxPath.APRS_IS
        }
        if (tcpTnc.connectionState.value is TcpTncConnectionState.Connected) {
            return TxPath.KISS_TCP
        }
        _sendError.value = when {
            isState is AprsIsConnectionState.Connected && !isState.verified ->
                "APRS-IS unverified; connect KISS TCP or check your passcode"
            else ->
                "Connect APRS-IS or KISS TCP (DireWolf) to send"
        }
        return null
    }

    private fun sendViaInternet(
        myCall: String,
        to: String,
        body: String,
        msgId: String
    ): Boolean {
        val info = AprsMessageCodec.build(to, body, messageId = msgId)
        val line = Tnc2Codec.format(
            source = myCall,
            destination = "APRS",
            digipeaters = listOf("TCPIP*"),
            info = info
        )
        if (!aprsIs.sendLine(line)) {
            _sendError.value = "Could not queue the APRS-IS transmission"
            return false
        }
        // Published to IS by us: its RF echo must not come back through the iGate.
        ownTx.note(myCall, info)
        Log.i(TAG, "TX IS $myCall → $to: $body{$msgId")
        return true
    }

    private fun sendViaKissTcp(to: String, body: String, msgId: String): Boolean {
        val ax25 = parser.buildOutgoingMessage(
            toCallsign = to,
            text = body,
            messageId = msgId,
            digipeaters = RF_DIGIS
        )
        if (!tcpTnc.sendAx25(ax25)) {
            _sendError.value = "Could not send over KISS TCP"
            return false
        }
        Log.i(TAG, "TX KISS-TCP → $to: $body{$msgId")
        return true
    }

    private fun handleIncomingMessage(packet: AprsPacket.TextMessage, source: MessageSource) {
        val mine = myCallsign()
        val ack = AprsMessageCodec.extractAck(packet.messageText)
            ?: AprsMessageCodec.extractAck(packet.rawInfo)
        if (ack != null) {
            val forUs = AprsMessageCodec.isAckAddressedTo(packet.addressee, mine) ||
                AprsMessageCodec.isAckAddressedTo(packet.destCall, mine)
            val fromUs = AprsMessageCodec.sameCallsignWithSsid(packet.sourceCall, mine)
            if (fromUs && !forUs) {
                Log.i(TAG, "Own ACK echo ignored: ${packet.messageText}")
                return
            }
            val genericDest = AprsMessageCodec.isGenericAprsDestination(packet.destCall)
            if (!forUs && !genericDest) {
                Log.i(
                    TAG,
                    "ACK from ${packet.sourceCall} for ${packet.addressee} (not $mine)"
                )
                return
            }
            val id = ack.messageId.ifBlank { packet.messageId?.trim().orEmpty() }
            Log.i(
                TAG,
                "ACK/REJ from ${packet.sourceCall} via $source: ${packet.messageText} " +
                    "id=$id dest=${packet.destCall} to=${packet.addressee} rej=${ack.rejected}"
            )
            applyIncomingAck(packet.sourceCall, id, ack.rejected)
            return
        }

        val forMe = AprsMessageCodec.isAddressedTo(packet.addressee, mine) ||
            packet.addressee.startsWith("BLN", ignoreCase = true)

        if (AprsMessageCodec.sameCallsignWithSsid(packet.sourceCall, mine) && !forMe) {
            Log.i(TAG, "Own echo ignored: ${packet.sourceCall} → ${packet.addressee}")
            return
        }

        if (!forMe) {
            Log.i(
                TAG,
                "Msg from ${packet.sourceCall} for ${packet.addressee}" +
                    " dest=${packet.destCall} (not addressed to $mine)"
            )
            return
        }

        Log.i(
            TAG,
            "Chat ← ${packet.sourceCall} → ${packet.addressee}: ${packet.messageText}"
        )

        val msgId = packet.messageId
        val wantsAck = !msgId.isNullOrBlank() &&
            AprsMessageCodec.isAddressedTo(packet.addressee, mine) &&
            !packet.addressee.startsWith("BLN", ignoreCase = true)
        val acked = if (wantsAck) {
            sendAck(to = packet.sourceCall, messageId = msgId!!, via = source)
        } else {
            false
        }

        append(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                from = packet.sourceCall,
                to = packet.addressee,
                text = packet.messageText,
                timestamp = System.currentTimeMillis(),
                isOutgoing = false,
                source = source,
                peer = packet.sourceCall.uppercase(),
                messageId = msgId,
                ackStatus = when {
                    !wantsAck -> MessageAckStatus.NONE
                    acked -> MessageAckStatus.DELIVERED
                    else -> MessageAckStatus.SENT
                }
            )
        )
    }

    private fun mergeLoaded(
        loaded: List<ChatMessage>,
        current: List<ChatMessage>
    ): List<ChatMessage> {
        val seen = current.map { it.id }.toHashSet()
        return loaded.filter { it.id !in seen } + current
    }

    private fun applyIncomingAck(fromPeer: String, messageId: String, rejected: Boolean) {
        var changed = false
        _messages.update { current ->
            val next = ChatAck.apply(current, fromPeer, messageId, rejected)
            changed = next !== current
            next
        }
        if (changed) {
            Log.i(
                TAG,
                "Message ${if (rejected) "REJ" else "ACK"} id=$messageId from $fromPeer"
            )
            schedulePersist()
            notifyAckIfNeeded(fromPeer, messageId, rejected)
        } else {
            val pending = _messages.value
                .filter {
                    it.isOutgoing && it.ackStatus == MessageAckStatus.SENT
                }
                .joinToString { "${it.peer}/{${it.messageId}}" }
                .ifBlank { "(none)" }
            Log.w(
                TAG,
                "ACK id=$messageId from $fromPeer with no pending outgoing message. Queued: $pending"
            )
        }
    }

    private fun sendAck(to: String, messageId: String, via: MessageSource): Boolean {
        val key = "${to.uppercase()}|$messageId"
        val now = System.currentTimeMillis()
        pruneAcks(now)
        val prev = recentAcks.putIfAbsent(key, now)
        if (prev != null && now - prev < ACK_DEDUPE_MS) return true

        val path = resolveTxPathForAck(via)
        if (path == null) {
            Log.w(TAG, "ACK pending (no IS or TCP) for $to{$messageId} via $via")
            recentAcks.remove(key)
            return false
        }

        val myCall = myCallsign().ifBlank { "N0CALL" }
        val ok = when (path) {
            TxPath.APRS_IS -> {
                val info = AprsMessageCodec.buildAck(to, messageId)
                val line = Tnc2Codec.format(
                    source = myCall,
                    destination = "APRS",
                    digipeaters = listOf("TCPIP*"),
                    info = info
                )
                aprsIs.sendLine(line).also { if (it) ownTx.note(myCall, info) }
            }
            TxPath.KISS_TCP -> {
                val ax25 = parser.buildOutgoingMessage(
                    toCallsign = to,
                    text = "ack$messageId",
                    messageId = null,
                    digipeaters = RF_DIGIS
                )
                tcpTnc.sendAx25(ax25)
            }
        }
        if (ok) {
            Log.i(TAG, "ACK $path → $to ack$messageId (orig=$via)")
            return true
        }
        recentAcks.remove(key)
        Log.w(TAG, "Could not send ACK to $to")
        return false
    }

    /**
     * ACK: reply over whichever path the message arrived on. A correspondent who
     * called us over RF may not be on APRS-IS at all, and an ACK sent to the
     * Internet never reaches them: they retry the message, and to the user it
     * looks like "it didn't send". If that transport is down, the other one is
     * used as a fallback.
     */
    private fun resolveTxPathForAck(via: MessageSource): TxPath? {
        val isUp = aprsIs.connectionState.value
            .let { it is AprsIsConnectionState.Connected && it.verified }
        val tcpUp = tcpTnc.connectionState.value is TcpTncConnectionState.Connected
        val preferRf = via == MessageSource.RF_TCP || via == MessageSource.RF_BLE
        return when {
            preferRf && tcpUp -> TxPath.KISS_TCP
            preferRf && isUp -> TxPath.APRS_IS
            isUp -> TxPath.APRS_IS
            tcpUp -> TxPath.KISS_TCP
            else -> null
        }
    }

    private fun pruneAcks(now: Long) {
        if (recentAcks.size < 64) return
        val it = recentAcks.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > ACK_DEDUPE_MS) it.remove()
        }
    }

    private fun append(msg: ChatMessage) {
        // A real duplicate = same `{msgid` from the same peer (the same packet arriving
        // over both RF and APRS-IS, or a retransmission by the sender). With no msgid
        // all that is left is comparing the text, and there the window must be short:
        // two "ok" in a row are two messages, not a duplicate.
        val dup = _messages.value.any {
            if (!it.peer.equals(msg.peer, ignoreCase = true)) return@any false
            if (it.isOutgoing != msg.isOutgoing) return@any false
            val id = msg.messageId?.trim().orEmpty()
            val otherId = it.messageId?.trim().orEmpty()
            if (id.isNotEmpty() && otherId.isNotEmpty()) {
                AprsMessageCodec.sameMessageId(otherId, id) && it.text == msg.text &&
                    kotlin.math.abs(it.timestamp - msg.timestamp) < DUPLICATE_ID_WINDOW_MS
            } else {
                it.text == msg.text &&
                    kotlin.math.abs(it.timestamp - msg.timestamp) < DUPLICATE_TEXT_WINDOW_MS
            }
        }
        if (dup) return
        val stored = msg.copy(
            isRead = msg.isOutgoing ||
                msg.peer.equals(activePeer, ignoreCase = true)
        )
        _messages.update { it + stored }
        schedulePersist()
        if (!stored.isRead && !stored.isOutgoing) {
            val isBulletin = stored.peer.startsWith("BLN", ignoreCase = true) ||
                stored.to.startsWith("BLN", ignoreCase = true)
            notifications.showIncomingMessage(
                peer = stored.peer,
                from = stored.from,
                text = stored.text,
                isBulletin = isBulletin
            )
        }
    }

    private fun notifyAckIfNeeded(fromPeer: String, messageId: String, rejected: Boolean) {
        if (messageId.isBlank()) return
        val peerKey = fromPeer.uppercase()
        if (peerKey.equals(activePeer, ignoreCase = true)) return
        notifications.showAck(peerKey, messageId, rejected)
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch(Dispatchers.IO) {
            delay(350)
            val snapshot = _messages.value
            persistMutex.withLock { store.save(snapshot) }
        }
    }

    private enum class TxPath { APRS_IS, KISS_TCP }

    companion object {
        private const val TAG = "ChatRepository"
        private const val ACK_DEDUPE_MS = 5 * 60_000L

        /** Same msgid from the same peer: the same packet over another route, or a retry. */
        private const val DUPLICATE_ID_WINDOW_MS = 10 * 60_000L

        /** With no msgid there is no way to tell them apart: short window so we don't eat messages. */
        private const val DUPLICATE_TEXT_WINDOW_MS = 3_000L
        private val RF_DIGIS = listOf("WIDE1-1", "WIDE2-1")
    }
}
