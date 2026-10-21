package org.privatetracker.core.network

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import kotlinx.serialization.DeserializationStrategy
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.flatMap
import org.privatetracker.core.common.result.map
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.PeerJoin
import org.privatetracker.core.domain.model.PeerWelcome
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.SyncBatch
import org.privatetracker.core.domain.model.peerLabel
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.PeerGateway
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.protocol.mapper.toDomain
import org.privatetracker.core.protocol.mapper.toDto
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.PeerSignature
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.v1.RequestSignature
import org.privatetracker.core.protocol.v1.dto.JoinPeerRequest
import org.privatetracker.core.protocol.v1.dto.JoinPeerResponse
import org.privatetracker.core.protocol.v1.dto.SyncMessageDto
import java.security.SecureRandom

/**
 * Client of the routes paired servers use (1.0): TLS to the key it pins, every request signed with
 * this server's identity key, see [PeerSignature]. Bodies are not sealed: TLS ends at the other server
 * with the key this one pinned, relay or not, so nobody in between reads them.
 */
class KtorPeerGateway(
    private val clients: PinnedClients,
    private val serverKeys: ServerKeys,
    private val clock: Clock = Clock.System,
    private val random: SecureRandom = SecureRandom(),
) : PeerGateway {
    override suspend fun join(url: String, inviterKey: String, join: PeerJoin): Outcome<PeerWelcome> =
        post(url, inviterKey, ApiV1.PEERS, ProtocolJson.encodeToString(JoinPeerRequest.serializer(), join.toDto()), JoinPeerResponse.serializer())
            .map { it.toDomain() }

    override suspend fun exchange(url: String, peerKey: String, batch: SyncBatch): Outcome<SyncBatch> =
        post(url, peerKey, ApiV1.SYNC, ProtocolJson.encodeToString(SyncMessageDto.serializer(), batch.toDto()), SyncMessageDto.serializer())
            .flatMap { it.toDomain() }

    private suspend fun <T> post(url: String, key: String, path: String, body: String, deserializer: DeserializationStrategy<T>): Outcome<T> {
        val bytes = body.encodeToByteArray()
        val created = clock.now().epochSecond
        val nonce = RequestSignature.newNonce(random)
        val (label, signature) = try {
            val label = peerLabel(serverKeys.publicKey())
            label to serverKeys.sign(PeerSignature.signingInput("POST", path, label, created, nonce, bytes))
        } catch (e: DeviceKeyException) {
            return DomainError.DeviceKeyUnavailable.asFailure()
        }
        return protocolExchange(deserializer) {
            clients.clientFor(ServerPin.Key(key)).post(url.trimEnd('/') + path) {
                header(HttpHeaders.Authorization, PeerSignature.header(label, created, nonce, signature))
                setBody(ByteArrayContent(bytes, ContentType.Application.Json))
            }
        }
    }
}
