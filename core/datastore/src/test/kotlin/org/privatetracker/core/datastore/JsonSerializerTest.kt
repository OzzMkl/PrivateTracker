package org.privatetracker.core.datastore

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class JsonSerializerTest {
    private val serializer = JsonSerializer(TrackerConfigData.serializer(), TrackerConfigData())

    @Test
    fun roundTripKeepsEveryField() = runTest {
        val config = TrackerConfigData(
            serverUrl = "https://192.168.1.10:8787",
            deviceName = "Ana",
            intervalSeconds = 30,
            serverFingerprint = "3F9A-01BC-77D2-E410",
        )
        val bytes = ByteArrayOutputStream().also { serializer.writeTo(config, it) }.toByteArray()

        assertEquals(config, serializer.readFrom(ByteArrayInputStream(bytes)))
    }

    @Test
    fun missingFieldsTakeTheirDefaultsSoOldFilesStillLoad() = runTest {
        val old = """{"serverUrl":"http://10.0.0.2:8787"}""".encodeToByteArray()

        val config = serializer.readFrom(ByteArrayInputStream(old))

        assertEquals("http://10.0.0.2:8787", config.serverUrl)
        assertEquals(60, config.intervalSeconds)
    }

    @Test
    fun garbageIsReportedAsCorruption() = runTest {
        assertThrows(CorruptionException::class.java) {
            kotlinx.coroutines.runBlocking { serializer.readFrom(ByteArrayInputStream("{not json".encodeToByteArray())) }
        }
    }
}

class HttpsAddressesMigrationTest {
    @Test
    fun addressesSavedBefore04MoveToHttpsAndKeepTheirKey() = runTest {
        val old = TrackerConfigData(
            serverUrl = "http://192.168.1.50:8787",
            serverKey = "a2V5",
            serverAddresses = listOf("http://192.168.1.50:8787", "https://100.101.102.103:8787"),
        )

        assertTrue(HttpsAddressesMigration.shouldMigrate(old))
        val migrated = HttpsAddressesMigration.migrate(old)

        assertEquals("https://192.168.1.50:8787", migrated.serverUrl)
        assertEquals(listOf("https://192.168.1.50:8787", "https://100.101.102.103:8787"), migrated.serverAddresses)
        assertEquals("a2V5", migrated.serverKey)
        assertFalse(HttpsAddressesMigration.shouldMigrate(migrated))
        assertFalse(HttpsAddressesMigration.shouldMigrate(TrackerConfigData()))
    }
}
