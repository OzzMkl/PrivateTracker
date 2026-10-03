package org.privatetracker.core.domain.model

import org.privatetracker.core.domain.geo.distanceMeters
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aDevice
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdsTest {
    @Test
    fun `parses canonical UUIDs and normalizes them to lowercase`() {
        val id = DeviceId.parse("6F1C2A8E-3B7D-4C1E-9A52-0D8E7F4B9C21")
        assertEquals("6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21", id?.value)
        assertEquals(id, DeviceId.parse("6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21"))
    }

    @Test
    fun `rejects anything that is not 8-4-4-4-12 hexadecimal`() {
        listOf(
            "",
            "not-a-uuid",
            "1-1-1-1-1",
            "6f1c2a8e3b7d4c1e9a520d8e7f4b9c21",
            "6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c2g",
            " 6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21",
        ).forEach { assertNull(LocationId.parse(it), "'$it' should be rejected") }
    }
}

class DeviceStatusTest {
    private val threshold = Duration.ofSeconds(300)

    private fun statusAfter(seconds: Long) = DeviceStatus.of(T0, T0.plusSeconds(seconds), threshold)

    @Test
    fun `a device never seen is offline`() {
        assertEquals(DeviceStatus.OFFLINE, DeviceStatus.of(null, T0, threshold))
    }

    @Test
    fun `status degrades from online to stale to offline as silence grows`() {
        assertEquals(DeviceStatus.ONLINE, statusAfter(300))
        assertEquals(DeviceStatus.STALE, statusAfter(301))
        assertEquals(DeviceStatus.STALE, statusAfter(900))
        assertEquals(DeviceStatus.OFFLINE, statusAfter(901))
    }
}

class DistanceTest {
    @Test
    fun `one degree of latitude is about 111 km`() {
        val meters = distanceMeters(19.0, -99.0, 20.0, -99.0)
        assertTrue(meters in 111_000.0..111_400.0, "got $meters")
    }

    @Test
    fun `distance to the same point is zero`() {
        assertEquals(0.0, distanceMeters(19.4326, -99.1332, 19.4326, -99.1332))
    }
}

class DeviceApprovalTest {
    @Test
    fun `only a device with a key awaits approval`() {
        assertTrue(aDevice(approval = DeviceApproval.PENDING).awaitsApproval)
        assertFalse(aDevice(approval = DeviceApproval.PENDING).copy(publicKey = null).awaitsApproval)
        assertFalse(aDevice(approval = DeviceApproval.APPROVED).awaitsApproval)
    }
}
