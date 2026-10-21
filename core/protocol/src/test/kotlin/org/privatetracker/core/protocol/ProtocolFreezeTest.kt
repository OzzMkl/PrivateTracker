package org.privatetracker.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.encryptionKeyInput
import org.privatetracker.core.domain.model.pairingProof
import org.privatetracker.core.domain.model.peerPairingProof
import org.privatetracker.core.domain.model.serverAddressesInput
import org.privatetracker.core.domain.model.serverIdentityInput
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.PairingUri
import org.privatetracker.core.protocol.v1.PeerSignature
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.v1.RequestSignature
import org.privatetracker.core.protocol.v1.SealedBodies
import org.privatetracker.core.protocol.v1.dto.DeviceDetailDto
import org.privatetracker.core.protocol.v1.dto.DeviceSummaryDto
import org.privatetracker.core.protocol.v1.dto.DevicesResponse
import org.privatetracker.core.protocol.v1.dto.EncryptionKeyDto
import org.privatetracker.core.protocol.v1.dto.HealthResponse
import org.privatetracker.core.protocol.v1.dto.JoinPeerRequest
import org.privatetracker.core.protocol.v1.dto.JoinPeerResponse
import org.privatetracker.core.protocol.v1.dto.LocationBatchRequest
import org.privatetracker.core.protocol.v1.dto.LocationBatchResponse
import org.privatetracker.core.protocol.v1.dto.LocationDto
import org.privatetracker.core.protocol.v1.dto.PairingClaimDto
import org.privatetracker.core.protocol.v1.dto.ProblemDetails
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceRequest
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceResponse
import org.privatetracker.core.protocol.v1.dto.RejectedLocationDto
import org.privatetracker.core.protocol.v1.dto.RemovedDeviceDto
import org.privatetracker.core.protocol.v1.dto.SealedRequestDto
import org.privatetracker.core.protocol.v1.dto.SealedResponseDto
import org.privatetracker.core.protocol.v1.dto.SessionDto
import org.privatetracker.core.protocol.v1.dto.SyncCursorDto
import org.privatetracker.core.protocol.v1.dto.SyncDeviceDto
import org.privatetracker.core.protocol.v1.dto.SyncLocationDto
import org.privatetracker.core.protocol.v1.dto.SyncMessageDto
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protocol v1 is frozen with version 1.0 (2026-10-16): trackers and servers already out there speak
 * it. Each message must read and write exactly as its file in `protocol-v1/`, and everything that is
 * signed must keep its exact bytes. A failure here means a change that breaks them; see the README
 * next to the files. Adding optional fields, routes and error codes stays allowed.
 */
class ProtocolFreezeTest {
    private val device = "6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21"
    private val location = LocationDto(
        id = "b3e1f7a2-5c4d-4e8f-a1b2-000000000001",
        latitude = 19.4326,
        longitude = -99.1332,
        accuracyM = 8.5f,
        altitudeM = 2240.0,
        speedMps = 1.25f,
        bearingDeg = 90.0f,
        provider = "gps",
        batteryPct = 80,
        isMock = false,
        recordedAt = "2026-10-16T17:59:00Z",
    )

    /** Reads [file] into [expected], and writes [expected] back as [file] says, field by field. */
    private fun <T> frozen(file: String, serializer: KSerializer<T>, expected: T) {
        val text = checkNotNull(javaClass.getResource("/protocol-v1/$file")) { "missing $file" }.readText()
        assertEquals(expected, ProtocolJson.decodeFromString(serializer, text), "$file no longer reads as before")
        assertEquals(Json.parseToJsonElement(text), ProtocolJson.encodeToJsonElement(serializer, expected), "$file is no longer written as before")
        // Whatever a later version adds, this one still reads.
        val newer = JsonObject(Json.parseToJsonElement(text).jsonObject + ("added_in_a_later_version" to JsonPrimitive(1)))
        assertEquals(expected, ProtocolJson.decodeFromJsonElement(serializer, newer), "$file breaks on an unknown field")
    }

    @Test
    fun `every message of v1 keeps its shape`() {
        frozen(
            "health-response.json",
            HealthResponse.serializer(),
            HealthResponse(
                status = "ok",
                serverName = "Casa",
                serverVersion = "1.0.0",
                protocolVersion = 1,
                serverTime = "2026-10-16T18:00:00Z",
                serverKey = "c2VydmVyLWtleQ==",
                signature = "c2lnbmF0dXJl",
                encryptionKey = EncryptionKeyDto("k1", "ZW5jcnlwdGlvbi1rZXk=", "2026-10-23T18:00:00Z", "a2V5LXNpZ25hdHVyZQ=="),
                addresses = listOf("https://192.168.1.20:8787", "https://9e9d7678d67df283.relay.example.org"),
                addressesSignature = "YWRkcmVzc2VzLXNpZ25hdHVyZQ==",
            ),
        )
        frozen(
            "register-device-request.json",
            RegisterDeviceRequest.serializer(),
            RegisterDeviceRequest(device, "Pixel de Ana", "ANDROID", "1.0.0", 1, "ZGV2aWNlLWtleQ==", PairingClaimDto("t1", "cHJvb2Y=")),
        )
        frozen(
            "register-device-response.json",
            RegisterDeviceResponse.serializer(),
            RegisterDeviceResponse(device, created = true, maxBatchSize = 100, serverTime = "2026-10-16T18:00:00Z", approval = "APPROVED"),
        )
        frozen("location-batch-request.json", LocationBatchRequest.serializer(), LocationBatchRequest(listOf(location)))
        frozen(
            "location-batch-response.json",
            LocationBatchResponse.serializer(),
            LocationBatchResponse(
                accepted = listOf(location.id),
                duplicates = emptyList(),
                rejected = listOf(RejectedLocationDto("not-a-uuid", "INVALID_FIELD", "id: invalid_format")),
                serverTime = "2026-10-16T18:00:00Z",
            ),
        )
        frozen(
            "devices-response.json",
            DevicesResponse.serializer(),
            DevicesResponse(listOf(DeviceSummaryDto(device, "Pixel de Ana", "ONLINE", "APPROVED", "2026-10-16T17:59:30Z", location))),
        )
        frozen(
            "device-detail.json",
            DeviceDetailDto.serializer(),
            DeviceDetailDto(
                deviceId = device,
                name = "Pixel de Ana",
                platform = "ANDROID",
                status = "ONLINE",
                approval = "APPROVED",
                keyFingerprint = "3F9A-01BC-77D2-E410",
                createdAt = "2026-10-01T12:00:00Z",
                appVersion = "1.0.0",
                lastSeenAt = "2026-10-16T17:59:30Z",
                lastLocation = location,
                currentSession = SessionDto("2026-10-16T08:00:00Z", "2026-10-16T17:59:30Z", 600),
            ),
        )
        frozen(
            "problem.json",
            ProblemDetails.serializer(),
            ProblemDetails("urn:privatetracker:problem:batch-too-large", "Payload Too Large", 413, "At most 100 locations per batch", "BATCH_TOO_LARGE", 100),
        )
        frozen("sealed-request.json", SealedRequestDto.serializer(), SealedRequestDto("k1", "ZW5j", "Y2lwaGVydGV4dA=="))
        frozen("sealed-response.json", SealedResponseDto.serializer(), SealedResponseDto("bm9uY2Utbm9uY2U=", "Y2lwaGVydGV4dA=="))
    }

    @Test
    fun `the messages between paired servers keep their shape too`() {
        frozen(
            "join-peer-request.json",
            JoinPeerRequest.serializer(),
            JoinPeerRequest("t1", "Oficina", "cGVlci1rZXk=", listOf("https://10.0.0.5:8787"), "cHJvb2Y="),
        )
        frozen(
            "join-peer-response.json",
            JoinPeerResponse.serializer(),
            JoinPeerResponse("Casa", listOf("https://192.168.1.20:8787", "https://9e9d7678d67df283.relay.example.org")),
        )
        frozen(
            "sync-message.json",
            SyncMessageDto.serializer(),
            SyncMessageDto(
                serverName = "Casa",
                addresses = listOf("https://192.168.1.20:8787"),
                have = SyncCursorDto(120, 7),
                upTo = SyncCursorDto(620, 9),
                devices = listOf(
                    SyncDeviceDto(
                        deviceId = device,
                        name = "Pixel de Ana",
                        platform = "ANDROID",
                        appVersion = "1.0.0",
                        protocolVersion = 1,
                        publicKey = "ZGV2aWNlLWtleQ==",
                        approval = "APPROVED",
                        createdAt = "2026-10-01T12:00:00Z",
                        updatedAt = "2026-10-10T09:00:00Z",
                        approvalChangedAt = "2026-10-12T15:30:00Z",
                        lastSeenAt = "2026-10-16T17:59:30Z",
                    ),
                ),
                removedDevices = listOf(RemovedDeviceDto("0b8f2d4e-1a3c-4e5f-8a9b-7c6d5e4f3a21", "2026-10-15T20:00:00Z")),
                locations = listOf(
                    with(location) {
                        SyncLocationDto(device, id, latitude, longitude, accuracyM, altitudeM, speedMps, bearingDeg, provider, batteryPct, isMock, recordedAt, "2026-10-16T17:59:31Z")
                    },
                ),
                more = true,
            ),
        )
    }

    @Test
    fun `routes and limits stay where they are`() {
        assertEquals(1, ApiV1.PROTOCOL_VERSION)
        assertEquals(
            listOf("/api/v1/health", "/api/v1/devices/register", "/api/v1/devices", "/api/v1/devices/{device_id}", "/api/v1/devices/{device_id}/locations", "/api/v1/peers", "/api/v1/sync"),
            listOf(ApiV1.HEALTH, ApiV1.REGISTER, ApiV1.DEVICES, ApiV1.DEVICE, ApiV1.LOCATIONS, ApiV1.PEERS, ApiV1.SYNC),
        )
        assertEquals("challenge", ApiV1.CHALLENGE_PARAM)
        assertEquals(256 * 1024, ApiV1.MAX_BODY_BYTES)
        assertEquals("application/problem+json", ApiV1.PROBLEM_CONTENT_TYPE)
    }

    @Test
    fun `error codes are only ever added`() {
        val frozen = listOf(
            "MALFORMED_JSON", "EMPTY_BATCH", "VALIDATION_FAILED", "UNSUPPORTED_PROTOCOL_VERSION", "NEW_DEVICES_DISABLED",
            "LOCAL_ONLY", "DEVICE_NOT_REGISTERED", "DEVICE_NOT_FOUND", "NOT_FOUND", "BATCH_TOO_LARGE", "UNSUPPORTED_MEDIA_TYPE",
            "RATE_LIMITED", "INTERNAL_ERROR", "SHUTTING_DOWN", "SIGNATURE_MISSING", "SIGNATURE_MALFORMED", "SIGNATURE_EXPIRED",
            "SIGNATURE_REPLAYED", "SIGNATURE_INVALID", "KEY_MISMATCH", "DEVICE_PENDING_APPROVAL", "DEVICE_REJECTED",
            "PAIRING_INVALID", "ENCRYPTION_REQUIRED", "ENCRYPTION_KEY_UNKNOWN", "DECRYPTION_FAILED", "PEER_UNKNOWN",
        )
        val now = ErrorCode.entries.map { it.name }
        assertTrue(now.containsAll(frozen), "removed: ${frozen - now.toSet()}")
        assertEquals("urn:privatetracker:problem:batch-too-large", ErrorCode.BATCH_TOO_LARGE.problemType)
    }

    @Test
    fun `everything signed keeps its exact bytes`() {
        val body = """{"locations":[]}""".encodeToByteArray()
        val bodyHash = "kyOlNOEIMstzuHGSMIWXaKec+msJTCXFPMdwelKiH80="
        assertEquals(
            "PrivateTracker-ECDSA-v1\nPOST\n/api/v1/devices/$device/locations\n$device\n1792173600\nbm9uY2U\n$bodyHash",
            RequestSignature.signingInput("post", "/api/v1/devices/$device/locations", device, 1792173600, "bm9uY2U", body).decodeToString(),
        )
        assertEquals(
            """PrivateTracker-ECDSA device="$device", created="1792173600", nonce="bm9uY2U", signature="c2ln"""",
            RequestSignature.header(device, 1792173600, "bm9uY2U", "sig".encodeToByteArray()),
        )
        assertEquals("PrivateTracker-ack-v1\nbm9uY2U\n$bodyHash", RequestSignature.ackInput("bm9uY2U", body).decodeToString())
        assertEquals("PrivateTracker-Ack-Signature", RequestSignature.ACK_HEADER)
        assertEquals(
            "PrivateTracker-peer-v1\nPOST\n/api/v1/sync\n9e9d7678d67df283\n1792173600\nbm9uY2U\n$bodyHash",
            PeerSignature.signingInput("POST", "/api/v1/sync", "9e9d7678d67df283", 1792173600, "bm9uY2U", body).decodeToString(),
        )
        assertEquals(
            """PrivateTracker-Peer peer="9e9d7678d67df283", created="1792173600", nonce="bm9uY2U", signature="c2ln"""",
            PeerSignature.header("9e9d7678d67df283", 1792173600, "bm9uY2U", "sig".encodeToByteArray()),
        )

        val time = Instant.parse("2026-10-16T18:00:00Z")
        assertEquals("PrivateTracker-server-v1\nAAAAAAAAAAAAAAAAAAAAAA\n1792173600000\nk1", serverIdentityInput("AAAAAAAAAAAAAAAAAAAAAA", time, "k1").decodeToString())
        assertEquals("PrivateTracker-server-v1\nAAAAAAAAAAAAAAAAAAAAAA\n1792173600000", serverIdentityInput("AAAAAAAAAAAAAAAAAAAAAA", time).decodeToString())
        assertEquals(
            "PrivateTracker-addresses-v1\nAAAAAAAAAAAAAAAAAAAAAA\nhttps://192.168.1.20:8787\nhttps://x.relay.example.org",
            serverAddressesInput("AAAAAAAAAAAAAAAAAAAAAA", listOf("https://192.168.1.20:8787", "https://x.relay.example.org")).decodeToString(),
        )
        assertEquals(
            "PrivateTracker-encryption-key-v1\nk1\nZW5jcnlwdGlvbi1rZXk=\n1792778400000",
            encryptionKeyInput(EncryptionKey("k1", "ZW5jcnlwdGlvbi1rZXk=", Instant.parse("2026-10-23T18:00:00Z"))).decodeToString(),
        )
        // The HMACs of pairing, over the frozen texts, with a known secret.
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { it.toByte() })
        assertEquals("Q3qs5BkZqDOJBbqeIUEeWIjrM/bfHlmc0Xga/ho1JVM=", pairingProof(secret, DeviceId.of(device), "ZGV2aWNlLWtleQ=="))
        assertEquals("xjiJbdvV7KP1vt1sQFOIb/Aae6WCeI3D9jtlqjQsr/4=", peerPairingProof(secret, "cGVlci1rZXk="))
        // What sealing binds to: the HPKE info, the request it travels in, and the answer's label.
        assertEquals("PrivateTracker-sealed-v1", SealedBodies.INFO.decodeToString())
        assertEquals("PrivateTracker-response-v1", SealedBodies.RESPONSE_LABEL.decodeToString())
        assertEquals("PrivateTracker-request-v1\nPOST\n/api/v1/devices/register", SealedBodies.aad("post", "/api/v1/devices/register").decodeToString())
    }

    @Test
    fun `invite links keep their form`() {
        val invite = PairingInvite("Casa", listOf("https://192.168.1.20:8787"), "c2VydmVyLWtleQ==", "t1", "AAECAwQFBgcICQoLDA0ODw", Instant.parse("2026-10-16T18:10:00Z"))
        val tracker = "privatetracker://pair?v=1&name=Casa&url=https%3A%2F%2F192.168.1.20%3A8787&key=c2VydmVyLWtleQ&ticket=t1&secret=AAECAwQFBgcICQoLDA0ODw&expires=1792174200"
        assertEquals(tracker, PairingUri.format(invite))
        assertEquals(invite, PairingUri.parse(tracker))
        val server = tracker.replace("://pair?", "://peer?")
        assertEquals(server, PairingUri.format(invite, PairingUri.PEER_HOST))
        assertEquals(invite, PairingUri.parse(server, PairingUri.PEER_HOST))
    }
}
