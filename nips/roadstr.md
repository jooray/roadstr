NIP-XX
======

Road Event Reports
------------------

`draft` `optional`

This NIP defines two event kinds for decentralized road event reporting: `kind:1315` for road event reports and `kind:1316` for confirmations/denials of those reports.

Road events include police checks, speed cameras, accidents, traffic jams, road closures, construction zones, and other hazards. Events are anchored to geographic locations using geohash tags, enabling spatial querying by clients.

## Road Event Report (kind 1315)

A road event report signals the presence of a notable condition at a specific geographic location.

The `.content` field is a plain-text optional comment (e.g., `"Checking seatbelts"`). An empty string `""` indicates no comment.

### Tags

The following tags MUST appear in this canonical order:

- `t` (required) event type string from the defined enum (see [Event Types](#event-types))
- `g` (required, repeated) geohash at precision levels 4, 5, and 6 characters. Levels 4-6 enable efficient spatial querying.
- `lat` (required) latitude as decimal string with 7 decimal places (e.g., `"48.8566140"`)
- `lon` (required) longitude as decimal string with 7 decimal places (e.g., `"2.3522219"`)
- `expiration` (required) Unix timestamp, always computed as `created_at + 1209600` (14 days). Per [NIP-40](40.md), relays SHOULD delete the event after this time. See [Expiration](#expiration).
- `alt` (optional) human-readable fallback per [NIP-31](31.md). If present, MUST be canonically generated as `"Roadstr: <type_string> report"`.

### Example

```json
{
  "id": "<32-bytes lowercase hex-encoded SHA-256>",
  "pubkey": "<32-bytes lowercase hex-encoded public key>",
  "created_at": 1700000000,
  "kind": 1315,
  "tags": [
    ["t", "police"],
    ["g", "u2ed"],
    ["g", "u2edc"],
    ["g", "u2edcg"],
    ["lat", "48.8566140"],
    ["lon", "2.3522219"],
    ["expiration", "1701209600"],
    ["alt", "Roadstr: police report"]
  ],
  "content": "",
  "sig": "<64-bytes hex-encoded signature>"
}
```

## Road Event Confirmation (kind 1316)

A confirmation event indicates whether a previously reported road event is still present or has cleared. Any user passing the reported location can publish a confirmation.

The `.content` field is a plain-text optional comment. An empty string `""` indicates no comment.

### Tags

The following tags MUST appear in this canonical order:

- `e` (required) event ID of the kind 1315 report being confirmed or denied
- `g` (required, repeated) geohash at precision levels 4, 5, and 6 characters. MUST match the geohashes of the referenced report.
- `status` (required) either `"still_there"` or `"no_longer_there"`
- `lat` (required) latitude as decimal string with 7 decimal places. MUST match the referenced report.
- `lon` (required) longitude as decimal string with 7 decimal places. MUST match the referenced report.
- `expiration` (required) Unix timestamp, always `created_at + 1209600` (14 days)
- `alt` (optional) human-readable fallback. If present, MUST be:
  - `"Roadstr: event confirmed"` when status is `still_there`
  - `"Roadstr: event denied"` when status is `no_longer_there`

Note: The `g`, `lat`, and `lon` tags duplicate the location from the referenced report. This enables efficient spatial querying of both reports and confirmations using a single `#g` filter.

### Example

```json
{
  "id": "<32-bytes lowercase hex-encoded SHA-256>",
  "pubkey": "<32-bytes lowercase hex-encoded public key>",
  "created_at": 1700003600,
  "kind": 1316,
  "tags": [
    ["e", "fcbfc866b657a2fe514b579bcf7ff7700a195731eb6f306efb01529a683f86db"],
    ["g", "u2ed"],
    ["g", "u2edc"],
    ["g", "u2edcg"],
    ["status", "still_there"],
    ["lat", "48.8566140"],
    ["lon", "2.3522219"],
    ["expiration", "1701213200"],
    ["alt", "Roadstr: event confirmed"]
  ],
  "content": "",
  "sig": "<64-bytes hex-encoded signature>"
}
```

## Event Types

The `t` tag value MUST be one of the following strings:

| Value | String              | Default Effective TTL |
|-------|---------------------|----------------------|
| 0     | `police`            | 2 hours              |
| 1     | `speed_camera`      | 30 days              |
| 2     | `traffic_jam`       | 1 hour               |
| 3     | `accident`          | 3 hours              |
| 4     | `road_closure`      | 7 days               |
| 5     | `construction`      | 7 days               |
| 6     | `hazard`            | 4 hours              |
| 7     | `road_condition`    | 6 hours              |
| 8     | `pothole`           | 7 days               |
| 9     | `fog`               | 3 hours              |
| 10    | `ice`               | 6 hours              |
| 11    | `animal`            | 1 hour               |
| 255   | `other`             | 2 hours              |

The numeric "Value" column is used only in the compact binary representation. In Nostr events, the string form is always used in the `t` tag.

The "Default Effective TTL" is used by clients to determine how long to display a marker. It is NOT the relay-side expiration (see [Expiration](#expiration)).

## Expiration

This NIP distinguishes between two expiration concepts:

### Relay-side expiration (NIP-40)

The `expiration` tag is always set to `created_at + 1209600` (14 days). This is a fixed constant for all event types. It tells relays when to garbage-collect the event. The 14-day window is long enough that confirmations can extend the effective lifetime of an event without the underlying data being deleted.

### Effective TTL (client-side)

Clients compute an _effective expiration_ from the event type's default TTL and any confirmations received:

1. Base effective expiry = `created_at + default_ttl` (from event types table)
2. Each `still_there` confirmation extends the effective expiry: `max(effective_expiry, confirmation.created_at + default_ttl)`
3. Each `no_longer_there` confirmation reduces it: `min(effective_expiry, confirmation.created_at)`

Clients SHOULD NOT display markers whose effective expiry has passed, even if the relay-side expiration has not yet been reached.

## Geohash Querying

Both reports and confirmations include geohash tags at multiple precision levels to enable efficient spatial filtering:

| Precision | Cell Size         | Use Case                  |
|-----------|-------------------|---------------------------|
| 4 chars   | ~20km x 20km     | Highway / regional queries |
| 5 chars   | ~5km x 5km       | City-level queries         |
| 6 chars   | ~1.2km x 0.6km   | Neighborhood queries       |

Precise coordinates are provided via the `lat` and `lon` tags.

Clients SHOULD query using the `#g` filter at a precision level appropriate to their context (zoom level, vehicle speed, etc.) and include the center cell plus its 8 neighbors to avoid boundary effects.

Since both reports (1315) and confirmations (1316) have geohash tags, clients can fetch both in a single query:

```json
["REQ", "road-events", {
  "kinds": [1315, 1316],
  "#g": ["u2edc", "u2edd", "u2edb", "u2ed9", "u2edf", "u2ed8", "u2ed6", "u2ed3", "u2ed2"],
  "since": 1699993200
}]
```

This returns all reports and their confirmations for the specified area in a single round-trip, enabling clients to compute effective TTL before displaying markers.

## Compact Binary Representation

For bandwidth-constrained transports (mesh networks, BLE), events can be encoded in a compact binary format and reconstructed into fully verifiable Nostr events.

### Report Binary Layout (110 bytes)

```
Offset  Size  Field
------  ----  -----
0       1     Flags byte
1       32    Public key
33      4     created_at (uint32, big-endian)
37      4     Latitude (int32, big-endian, value * 10^7)
41      4     Longitude (int32, big-endian, value * 10^7)
45      1     Event type (enum value 0-11 or 255)
46      64    Schnorr signature
------
Total: 110 bytes
```

### Confirmation Binary Layout (134 bytes)

```
Offset  Size  Field
------  ----  -----
0       1     Flags byte
1       32    Public key
33      4     created_at (uint32, big-endian)
37      32    Referenced event ID
69      1     Status (0 = no_longer_there, 1 = still_there)
70      64    Schnorr signature
------
Total: 134 bytes
```

Note: Unlike reports, confirmation binaries do not include coordinates. The receiver MUST query the referenced report (by event ID) to obtain the `lat`, `lon`, and geohashes needed for Nostr event reconstruction.

### Flags byte

```
Bits 7-4: Version (0 = v1)
Bit 3:    Event class (0 = report, 1 = confirmation)
Bit 2:    Reserved
Bits 1-0: Reserved
```

### Reconstruction

A receiver reconstructs the full Nostr event deterministically.

#### Report Reconstruction

1. Compute geohash from coordinates at precision levels 4, 5, 6
2. Look up type string from the enum value
3. Set `expiration` to `created_at + 1209600`
4. Generate `alt` tag canonically: `"Roadstr: <type_string> report"`
5. Assemble tags in canonical order
6. Set `content` to `""`
7. Serialize per [NIP-01](01.md): `[0, <pubkey_hex>, <created_at>, <kind>, <tags>, ""]`
8. Compute `id` as SHA-256 of the serialized array
9. Verify Schnorr signature against the computed ID and public key

#### Confirmation Reconstruction

Since confirmation binaries do not contain coordinates, the receiver MUST first obtain the referenced report event (via relay query or local cache) to extract its `lat`, `lon`, and geohashes:

1. Query for the referenced report by event ID, or retrieve from local cache
2. Extract `lat`, `lon`, and geohashes from the referenced report
3. Set `expiration` to `created_at + 1209600`
4. Generate `alt` tag canonically based on status:
   - `"Roadstr: event confirmed"` for `still_there`
   - `"Roadstr: event denied"` for `no_longer_there`
5. Assemble tags in canonical order (e, g×3, status, lat, lon, expiration, alt)
6. Set `content` to `""`
7. Serialize per [NIP-01](01.md): `[0, <pubkey_hex>, <created_at>, <kind>, <tags>, ""]`
8. Compute `id` as SHA-256 of the serialized array
9. Verify Schnorr signature against the computed ID and public key

Events with non-empty `.content` cannot be represented in binary form.

### Coordinate Encoding

- Latitude: `int32(lat * 10^7)` — range [-900000000, 900000000]
- Longitude: `int32(lon * 10^7)` — range [-1800000000, 1800000000]
- Precision: ~1.1 cm

## Kind Allocation

Kinds 1315 and 1316 are in the regular event range (1000-9999) defined by [NIP-01](01.md). They are currently unassigned in the NIPs kind registry. Registration requires a PR to [the NIPs repository](https://github.com/nostr-protocol/nips) adding entries to the event kinds table, referencing this specification.

## References

- [NIP-01](01.md) - Basic protocol flow description
- [NIP-31](31.md) - Dealing with unknown event kinds (`alt` tag)
- [NIP-40](40.md) - Expiration timestamp
