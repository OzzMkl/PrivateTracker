package org.privatetracker.server.api.route

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.protocol.mapper.toDomain
import org.privatetracker.core.protocol.mapper.toDto
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.dto.JoinPeerRequest
import org.privatetracker.core.protocol.v1.dto.SyncMessageDto
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.auth.peerLabelOrNull
import org.privatetracker.server.api.auth.signedPeerRequest
import org.privatetracker.server.api.plugin.decodeJson
import org.privatetracker.server.api.plugin.getOrThrowApi
import org.privatetracker.server.api.plugin.receiveJsonBytes
import org.privatetracker.server.api.plugin.toApiException

// Paired servers, from 1.0 on. Both routes are signed with the calling server's identity key and
// answer in the clear inside TLS: TLS ends at each server with the key the other pinned, relay or not.

/** A server that read this one's invite joins with the key it signs with. */
fun Route.peerJoinRoutes(deps: ServerDependencies) {
    val accept = deps.acceptPeer ?: return
    post(ApiV1.PEERS) {
        val body = call.receiveJsonBytes()
        val signed = call.signedPeerRequest(body)
        val join = decodeJson(JoinPeerRequest.serializer(), body).toDomain()
        call.respond(accept(signed, join).getOrThrowApi().toDto())
    }
}

/** One exchange: the caller's batch in, this server's batch back. */
fun Route.syncRoutes(deps: ServerDependencies) {
    val answer = deps.answerPeerExchange ?: return
    post(ApiV1.SYNC) {
        // A server this one does not know is turned away before a megabyte of its body is read.
        val label = call.peerLabelOrNull()
        if (label != null && !answer.knows(label)) throw DomainError.PeerUnknown.toApiException()
        val body = call.receiveJsonBytes(ApiV1.MAX_SYNC_BODY_BYTES)
        // Who signed it, before reading a byte of what it says.
        val peer = answer.authenticate(call.signedPeerRequest(body)).getOrThrowApi()
        val batch = decodeJson(SyncMessageDto.serializer(), body).toDomain().getOrThrowApi()
        call.respond(answer(peer, batch).getOrThrowApi().toDto())
    }
}
