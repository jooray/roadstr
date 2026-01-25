package com.roadstr.nostr

import org.junit.Assert.*
import org.junit.Test

class BinaryCodecTest {

    @Test
    fun `encode report produces correct size`() {
        val event = createTestReportEvent()
        val encoded = BinaryCodec.encodeReport(event)
        assertEquals(BinaryCodec.REPORT_SIZE, encoded.size)
    }

    @Test
    fun `encode confirmation produces correct size`() {
        val event = createTestConfirmationEvent()
        val encoded = BinaryCodec.encodeConfirmation(event)
        assertEquals(BinaryCodec.CONFIRMATION_SIZE, encoded.size)
    }

    @Test
    fun `round trip report encode decode preserves fields`() {
        val event = createTestReportEvent()
        val encoded = BinaryCodec.encodeReport(event)
        val decoded = BinaryCodec.decodeReport(encoded)

        assertNotNull(decoded)
        assertEquals(event.pubkey, decoded!!.pubkey)
        assertEquals(event.createdAt, decoded.createdAt)
        assertEquals(event.kind, decoded.kind)

        // Check type tag preserved
        val origType = event.tags.find { it[0] == "t" }?.get(1)
        val decodedType = decoded.tags.find { it[0] == "t" }?.get(1)
        assertEquals(origType, decodedType)

        // Check coordinates
        val origLat = event.tags.find { it[0] == "lat" }?.get(1)
        val decodedLat = decoded.tags.find { it[0] == "lat" }?.get(1)
        assertEquals(origLat, decodedLat)
    }

    @Test
    fun `round trip confirmation encode decode preserves fields`() {
        // Confirmation comes from a report at these coordinates
        val reportLat = 48.8566140
        val reportLon = 2.3522219

        val event = createTestConfirmationEvent()
        val encoded = BinaryCodec.encodeConfirmation(event)
        val decoded = BinaryCodec.decodeConfirmation(encoded, reportLat, reportLon)

        assertNotNull(decoded)
        assertEquals(event.pubkey, decoded!!.pubkey)
        assertEquals(event.createdAt, decoded.createdAt)
        assertEquals(event.kind, decoded.kind)

        val origEventId = event.tags.find { it[0] == "e" }?.get(1)
        val decodedEventId = decoded.tags.find { it[0] == "e" }?.get(1)
        assertEquals(origEventId, decodedEventId)

        val origStatus = event.tags.find { it[0] == "status" }?.get(1)
        val decodedStatus = decoded.tags.find { it[0] == "status" }?.get(1)
        assertEquals(origStatus, decodedStatus)

        // Verify geohash tags are reconstructed
        val gTags = decoded.tags.filter { it[0] == "g" }
        assertEquals(3, gTags.size)

        // Verify lat/lon tags match the provided coordinates
        val decodedLat = decoded.tags.find { it[0] == "lat" }?.get(1)
        assertEquals("%.7f".format(reportLat), decodedLat)

        // Verify canonical alt text
        val altTag = decoded.tags.find { it[0] == "alt" }?.get(1)
        assertEquals("Roadstr: event confirmed", altTag)
    }

    @Test
    fun `flags byte distinguishes report from confirmation`() {
        val report = createTestReportEvent()
        val confirmation = createTestConfirmationEvent()

        val encodedReport = BinaryCodec.encodeReport(report)
        val encodedConf = BinaryCodec.encodeConfirmation(confirmation)

        assertTrue(BinaryCodec.isReport(encodedReport))
        assertFalse(BinaryCodec.isConfirmation(encodedReport))

        assertTrue(BinaryCodec.isConfirmation(encodedConf))
        assertFalse(BinaryCodec.isReport(encodedConf))
    }

    @Test
    fun `ephemeral flag is set correctly`() {
        val event = createTestReportEvent()

        val normal = BinaryCodec.encodeReport(event, ephemeral = false)
        val ephemeral = BinaryCodec.encodeReport(event, ephemeral = true)

        assertEquals(0, normal[0].toInt() and 0x04)
        assertNotEquals(0, ephemeral[0].toInt() and 0x04)
    }

    @Test
    fun `coordinate encoding boundary values`() {
        // Test max latitude
        val maxLatEvent = createReportWithCoords(90.0, 180.0)
        val encoded = BinaryCodec.encodeReport(maxLatEvent)
        val decoded = BinaryCodec.decodeReport(encoded)
        assertNotNull(decoded)

        val decodedLat = decoded!!.tags.find { it[0] == "lat" }?.get(1)?.toDouble() ?: 0.0
        val decodedLon = decoded.tags.find { it[0] == "lon" }?.get(1)?.toDouble() ?: 0.0
        assertEquals(90.0, decodedLat, 0.0000001)
        assertEquals(180.0, decodedLon, 0.0000001)

        // Test negative coords
        val negEvent = createReportWithCoords(-48.8566140, -2.3522219)
        val negEncoded = BinaryCodec.encodeReport(negEvent)
        val negDecoded = BinaryCodec.decodeReport(negEncoded)
        assertNotNull(negDecoded)

        val negLat = negDecoded!!.tags.find { it[0] == "lat" }?.get(1)?.toDouble() ?: 0.0
        assertEquals(-48.8566140, negLat, 0.0000001)
    }

    @Test
    fun `all event types encode and decode correctly`() {
        val types = listOf("police", "speed_camera", "traffic_jam", "accident",
            "road_closure", "construction", "hazard", "road_condition")

        for (typeName in types) {
            val event = createReportWithType(typeName)
            val encoded = BinaryCodec.encodeReport(event)
            val decoded = BinaryCodec.decodeReport(encoded)

            assertNotNull("Decode failed for type $typeName", decoded)
            val decodedType = decoded!!.tags.find { it[0] == "t" }?.get(1)
            assertEquals("Type mismatch for $typeName", typeName, decodedType)
        }
    }

    @Test
    fun `decode returns null for wrong size`() {
        assertNull(BinaryCodec.decodeReport(ByteArray(50)))
        assertNull(BinaryCodec.decodeConfirmation(ByteArray(50), 0.0, 0.0))
    }

    @Test
    fun `generic decode handles reports only`() {
        val report = BinaryCodec.encodeReport(createTestReportEvent())
        val conf = BinaryCodec.encodeConfirmation(createTestConfirmationEvent())

        val decodedReport = BinaryCodec.decode(report)
        assertNotNull(decodedReport)
        assertEquals(1315, decodedReport!!.kind)

        // Confirmations require coordinates, so decode() returns null for them
        val decodedConf = BinaryCodec.decode(conf)
        assertNull(decodedConf)
    }

    @Test
    fun `getConfirmationEventId extracts event ID from binary`() {
        val event = createTestConfirmationEvent()
        val encoded = BinaryCodec.encodeConfirmation(event)

        val eventId = BinaryCodec.getConfirmationEventId(encoded)
        assertNotNull(eventId)

        val expectedId = event.tags.find { it[0] == "e" }?.get(1)
        assertEquals(expectedId, eventId)
    }

    // Helper methods

    private fun createTestReportEvent(): NostrEvent {
        return NostrEvent(
            id = "a".repeat(64),
            pubkey = "b".repeat(64),
            createdAt = 1700000000L,
            kind = 1315,
            tags = listOf(
                listOf("t", "police"),
                listOf("g", "u2ed"),
                listOf("g", "u2edc"),
                listOf("g", "u2edcg"),
                listOf("lat", "48.8566140"),
                listOf("lon", "2.3522219"),
                listOf("expiration", "1700007200"),
                listOf("alt", "Roadstr: police report")
            ),
            content = "",
            sig = "c".repeat(128)
        )
    }

    private fun createTestConfirmationEvent(): NostrEvent {
        return NostrEvent(
            id = "d".repeat(64),
            pubkey = "b".repeat(64),
            createdAt = 1700003600L,
            kind = 1316,
            tags = listOf(
                listOf("e", "a".repeat(64)),
                listOf("g", "u2ed"),
                listOf("g", "u2edc"),
                listOf("g", "u2edcg"),
                listOf("status", "still_there"),
                listOf("lat", "48.8566140"),
                listOf("lon", "2.3522219"),
                listOf("expiration", "1701213200"),
                listOf("alt", "Roadstr: event confirmed")
            ),
            content = "",
            sig = "e".repeat(128)
        )
    }

    private fun createReportWithCoords(lat: Double, lon: Double): NostrEvent {
        return NostrEvent(
            id = "a".repeat(64),
            pubkey = "b".repeat(64),
            createdAt = 1700000000L,
            kind = 1315,
            tags = listOf(
                listOf("t", "police"),
                listOf("g", "u2ed"),
                listOf("g", "u2edc"),
                listOf("g", "u2edcg"),
                listOf("lat", "%.7f".format(lat)),
                listOf("lon", "%.7f".format(lon)),
                listOf("expiration", "1700007200"),
                listOf("alt", "Roadstr: police report")
            ),
            content = "",
            sig = "c".repeat(128)
        )
    }

    private fun createReportWithType(typeName: String): NostrEvent {
        return NostrEvent(
            id = "a".repeat(64),
            pubkey = "b".repeat(64),
            createdAt = 1700000000L,
            kind = 1315,
            tags = listOf(
                listOf("t", typeName),
                listOf("g", "u2ed"),
                listOf("g", "u2edc"),
                listOf("g", "u2edcg"),
                listOf("lat", "48.8566140"),
                listOf("lon", "2.3522219"),
                listOf("expiration", "1700007200"),
                listOf("alt", "Roadstr: $typeName report")
            ),
            content = "",
            sig = "c".repeat(128)
        )
    }
}
