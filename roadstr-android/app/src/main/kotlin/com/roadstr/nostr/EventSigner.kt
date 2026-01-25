package com.roadstr.nostr

import android.util.Log
import com.roadstr.location.GeohashUtil
import com.roadstr.model.ConfirmationStatus
import com.roadstr.model.EventType
import com.roadstr.storage.hexToBytes
import com.roadstr.storage.toHex
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

object EventSigner {

    private const val TAG = "EventSigner"
    private val secp256k1 = Secp256k1.get()

    /** Relay-side expiration: 14 days per NIP spec. All events use this constant. */
    private const val RELAY_TTL = 1209600L

    fun sign(event: NostrEvent, privateKey: ByteArray): NostrEvent {
        val pubkey = derivePublicKey(privateKey).toHex()
        val withPubkey = event.copy(pubkey = pubkey)
        val id = withPubkey.computeId()
        val withId = withPubkey.copy(id = id)

        val aux = ByteArray(32)
        SecureRandom().nextBytes(aux)
        val sig = secp256k1.signSchnorr(id.hexToBytes(), privateKey, aux)

        return withId.copy(sig = sig.toHex())
    }

    fun verify(event: NostrEvent): Boolean {
        return try {
            val expectedId = event.computeId()
            if (expectedId != event.id) {
                Log.d(TAG, "ID mismatch for ${event.id.take(8)}: expected ${expectedId.take(8)}")
                return false
            }

            secp256k1.verifySchnorr(
                event.sig.hexToBytes(),
                event.id.hexToBytes(),
                event.pubkey.hexToBytes()
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid hex in event ${event.id.take(8)}: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Verify failed for ${event.id.take(8)}: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun createReportEvent(
        type: EventType,
        lat: Double,
        lon: Double,
        privateKey: ByteArray,
        content: String = ""
    ): NostrEvent {
        val now = System.currentTimeMillis() / 1000
        val geohashes = listOf(4, 5, 6).map { GeohashUtil.encode(lat, lon, it) }
        val expiration = now + RELAY_TTL

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

        val unsigned = NostrEvent(
            createdAt = now,
            kind = 1315,
            tags = tags,
            content = content
        )

        return sign(unsigned, privateKey)
    }

    fun createConfirmationEvent(
        eventId: String,
        status: ConfirmationStatus,
        lat: Double,
        lon: Double,
        privateKey: ByteArray
    ): NostrEvent {
        val now = System.currentTimeMillis() / 1000
        val geohashes = listOf(4, 5, 6).map { GeohashUtil.encode(lat, lon, it) }
        val expiration = now + RELAY_TTL

        val latStr = "%.7f".format(lat)
        val lonStr = "%.7f".format(lon)

        // Alt tag must be canonical per NIP spec for deterministic mesh encoding
        val altText = when (status) {
            ConfirmationStatus.STILL_THERE -> "Roadstr: event confirmed"
            ConfirmationStatus.NO_LONGER_THERE -> "Roadstr: event denied"
        }

        val tags = listOf(
            listOf("e", eventId),
            listOf("g", geohashes[0]),
            listOf("g", geohashes[1]),
            listOf("g", geohashes[2]),
            listOf("status", status.value),
            listOf("lat", latStr),
            listOf("lon", lonStr),
            listOf("expiration", expiration.toString()),
            listOf("alt", altText)
        )

        val unsigned = NostrEvent(
            createdAt = now,
            kind = 1316,
            tags = tags,
            content = ""
        )

        return sign(unsigned, privateKey)
    }

    private fun derivePublicKey(privKey: ByteArray): ByteArray {
        val fullPubKey = secp256k1.pubkeyCreate(privKey)
        val compressed = secp256k1.pubKeyCompress(fullPubKey)
        return compressed.copyOfRange(1, 33)
    }
}
