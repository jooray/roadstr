package com.roadstr.nostr

import com.roadstr.location.GeohashUtil
import com.roadstr.model.ConfirmationStatus
import com.roadstr.model.EventType
import com.roadstr.storage.hexToBytes
import com.roadstr.storage.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryCodec {

    const val REPORT_SIZE = 110
    const val CONFIRMATION_SIZE = 134

    // Flags
    private const val FLAG_CONFIRMATION: Int = 0x08 // bit 3
    private const val FLAG_EPHEMERAL: Int = 0x04   // bit 2

    fun encodeReport(event: NostrEvent, ephemeral: Boolean = false): ByteArray {
        val buffer = ByteBuffer.allocate(REPORT_SIZE).order(ByteOrder.BIG_ENDIAN)

        // Flags byte
        var flags = 0
        if (ephemeral) flags = flags or FLAG_EPHEMERAL
        buffer.put(flags.toByte())

        // Public key (32 bytes)
        buffer.put(event.pubkey.hexToBytes())

        // created_at (uint32)
        buffer.putInt(event.createdAt.toInt())

        // Extract lat/lon from tags
        val latTag = event.tags.find { it[0] == "lat" }
        val lonTag = event.tags.find { it[0] == "lon" }
        val lat = latTag?.get(1)?.toDouble() ?: 0.0
        val lon = lonTag?.get(1)?.toDouble() ?: 0.0

        // Latitude (int32, value * 10^7)
        buffer.putInt((lat * 1e7).toLong().toInt())
        // Longitude (int32, value * 10^7)
        buffer.putInt((lon * 1e7).toLong().toInt())

        // Event type byte
        val typeTag = event.tags.find { it[0] == "t" }
        val typeValue = typeTag?.let { EventType.fromTypeName(it[1]).value } ?: 255
        buffer.put(typeValue.toByte())

        // Signature (64 bytes)
        buffer.put(event.sig.hexToBytes())

        return buffer.array()
    }

    fun encodeConfirmation(event: NostrEvent, ephemeral: Boolean = false): ByteArray {
        val buffer = ByteBuffer.allocate(CONFIRMATION_SIZE).order(ByteOrder.BIG_ENDIAN)

        // Flags byte (bit 3 = 1 for confirmation)
        var flags = FLAG_CONFIRMATION
        if (ephemeral) flags = flags or FLAG_EPHEMERAL
        buffer.put(flags.toByte())

        // Public key (32 bytes)
        buffer.put(event.pubkey.hexToBytes())

        // created_at (uint32)
        buffer.putInt(event.createdAt.toInt())

        // Referenced event ID (32 bytes)
        val eTag = event.tags.find { it[0] == "e" }
        buffer.put((eTag?.get(1) ?: "").hexToBytes())

        // Status byte (0 = no_longer_there, 1 = still_there)
        val statusTag = event.tags.find { it[0] == "status" }
        val statusByte = if (statusTag?.get(1) == "still_there") 1 else 0
        buffer.put(statusByte.toByte())

        // Signature (64 bytes)
        buffer.put(event.sig.hexToBytes())

        return buffer.array()
    }

    fun decodeReport(data: ByteArray): NostrEvent? {
        if (data.size != REPORT_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val flags = buffer.get().toInt() and 0xFF
        if (flags and FLAG_CONFIRMATION != 0) return null // Not a report

        val pubkey = ByteArray(32)
        buffer.get(pubkey)

        val createdAt = buffer.getInt().toLong() and 0xFFFFFFFFL

        val latRaw = buffer.getInt()
        val lonRaw = buffer.getInt()
        val lat = latRaw / 1e7
        val lon = lonRaw / 1e7

        val typeValue = buffer.get().toInt() and 0xFF

        val sig = ByteArray(64)
        buffer.get(sig)

        // Reconstruct the event
        val type = EventType.fromValue(typeValue)
        val geohashes = listOf(4, 5, 6).map { GeohashUtil.encode(lat, lon, it) }
        val expiration = createdAt + 1209600L // 14 days per NIP spec
        val latStr = "%.7f".format(lat)
        val lonStr = "%.7f".format(lon)

        val tags = listOf(
            listOf("t", type.typeName),
            listOf("g", geohashes[0]),
            listOf("g", geohashes[1]),
            listOf("g", geohashes[2]),
            listOf("lat", latStr),
            listOf("lon", lonStr),
            listOf("expiration", expiration.toString()),
            listOf("alt", "Roadstr: ${type.typeName} report")
        )

        val event = NostrEvent(
            pubkey = pubkey.toHex(),
            createdAt = createdAt,
            kind = 1315,
            tags = tags,
            content = "",
            sig = sig.toHex()
        )

        val id = event.computeId()
        return event.copy(id = id)
    }

    /**
     * Decode a confirmation from binary format.
     * Per NIP spec, the receiver must provide the original report's coordinates
     * to reconstruct the full Nostr event with geohash tags.
     *
     * @param data The binary confirmation data (134 bytes)
     * @param reportLat Latitude from the referenced report
     * @param reportLon Longitude from the referenced report
     * @return The reconstructed NostrEvent, or null if decoding fails
     */
    fun decodeConfirmation(data: ByteArray, reportLat: Double, reportLon: Double): NostrEvent? {
        if (data.size != CONFIRMATION_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val flags = buffer.get().toInt() and 0xFF
        if (flags and FLAG_CONFIRMATION == 0) return null // Not a confirmation

        val pubkey = ByteArray(32)
        buffer.get(pubkey)

        val createdAt = buffer.getInt().toLong() and 0xFFFFFFFFL

        val eventId = ByteArray(32)
        buffer.get(eventId)

        val statusByte = buffer.get().toInt() and 0xFF
        val status = if (statusByte == 1) ConfirmationStatus.STILL_THERE else ConfirmationStatus.NO_LONGER_THERE

        val sig = ByteArray(64)
        buffer.get(sig)

        // Reconstruct geohashes from report coordinates
        val geohashes = listOf(4, 5, 6).map { GeohashUtil.encode(reportLat, reportLon, it) }
        val expiration = createdAt + 1209600L // 14 days
        val latStr = "%.7f".format(reportLat)
        val lonStr = "%.7f".format(reportLon)

        // Canonical alt text per NIP spec
        val altText = when (status) {
            ConfirmationStatus.STILL_THERE -> "Roadstr: event confirmed"
            ConfirmationStatus.NO_LONGER_THERE -> "Roadstr: event denied"
        }

        val tags = listOf(
            listOf("e", eventId.toHex()),
            listOf("g", geohashes[0]),
            listOf("g", geohashes[1]),
            listOf("g", geohashes[2]),
            listOf("status", status.value),
            listOf("lat", latStr),
            listOf("lon", lonStr),
            listOf("expiration", expiration.toString()),
            listOf("alt", altText)
        )

        val event = NostrEvent(
            pubkey = pubkey.toHex(),
            createdAt = createdAt,
            kind = 1316,
            tags = tags,
            content = "",
            sig = sig.toHex()
        )

        val id = event.computeId()
        return event.copy(id = id)
    }

    /**
     * Decode a report from binary format.
     * For confirmations, use decodeConfirmation() with the original report's coordinates.
     */
    fun decode(data: ByteArray): NostrEvent? {
        return when (data.size) {
            REPORT_SIZE -> decodeReport(data)
            else -> null
        }
    }

    /**
     * Get the referenced event ID from a confirmation binary without full decoding.
     * Useful for looking up the original report to get coordinates.
     */
    fun getConfirmationEventId(data: ByteArray): String? {
        if (data.size != CONFIRMATION_SIZE) return null
        if (!isConfirmation(data)) return null

        // Event ID is at offset 37 (after flags, pubkey, created_at)
        val eventId = data.copyOfRange(37, 69)
        return eventId.toHex()
    }

    fun isReport(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        return (data[0].toInt() and FLAG_CONFIRMATION) == 0
    }

    fun isConfirmation(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        return (data[0].toInt() and FLAG_CONFIRMATION) != 0
    }
}
