# Roadstr — Specification

## 1. Overview

Roadstr is a decentralized road event reporting system built on the Nostr protocol. It operates as an OsmAnd plugin (Android) that allows drivers to report, confirm, and view road events (police checks, construction, closures, traffic jams, etc.) directly from the navigation interface. Events are published as signed Nostr events and displayed as color-coded map markers.

A compact binary encoding enables future transfer of events over MeshCore mesh networks (under 135 bytes per event), allowing off-grid propagation of road reports.

### Design Principles

- **Decentralized**: No central server. Events are published to Nostr relays.
- **Verifiable**: All events are cryptographically signed. Any recipient can verify authenticity.
- **Compact**: Binary representation allows mesh network transfer under 135 bytes.
- **Trust-all model (v1)**: No reputation system. All users are trusted equally.
- **Privacy-configurable**: Users choose between persistent identity or ephemeral keys.

---

## 2. Architecture

```
┌─────────────────────────────────────────────────┐
│                   OsmAnd App                     │
│  ┌───────────────────────────────────────────┐  │
│  │           Map View + Navigation            │  │
│  │  - Roadstr map layer (markers)            │  │
│  │  - Report button (widget)                 │  │
│  │  - Context menu (confirm/deny)            │  │
│  └───────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────┘
                       │ AIDL IPC
┌──────────────────────┴──────────────────────────┐
│              Roadstr Plugin (APK)                │
│                                                  │
│  ┌────────────┐  ┌────────────┐  ┌───────────┐ │
│  │  Nostr     │  │  Location  │  │  Alert    │ │
│  │  Client    │  │  Monitor   │  │  Engine   │ │
│  └─────┬──────┘  └─────┬──────┘  └─────┬─────┘ │
│        │               │               │        │
│  ┌─────┴───────────────┴───────────────┴─────┐  │
│  │            Event Manager                   │  │
│  │  - Create/sign events                     │  │
│  │  - Query by geohash                       │  │
│  │  - Compute effective expiration           │  │
│  │  - Binary encode/decode                   │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────┘
                       │ WebSocket (NIP-01)
              ┌────────┴────────┐
              │  Nostr Relays   │
              └─────────────────┘
```

**Language**: Kotlin (Android native, required for AIDL integration with OsmAnd)

**Key Components**:
- **Nostr Client**: WebSocket connection to relays, event publishing/subscribing (NIP-01)
- **Location Monitor**: Tracks user position via OsmAnd callbacks, triggers area queries
- **Alert Engine**: Proximity detection, directional awareness, sound/vibration alerts
- **Event Manager**: Event creation, signing, binary encoding, expiration calculation
- **AIDL Bridge**: Map layer management, markers, widgets, context menu buttons

---

## 3. Nostr Event Format

### 3.1 Event Kinds

| Kind | Name | Category | Description |
|------|------|----------|-------------|
| 1315 | Road Event Report | Regular (stored) | A new road event report |
| 1316 | Road Event Confirmation | Regular (stored) | Confirmation or denial of existing event |

Both kinds are in the regular range (1000–9999), meaning relays store them persistently.

### 3.2 Road Event Report (Kind 1315)

```json
{
  "id": "<sha256 hex>",
  "pubkey": "<32-byte hex pubkey>",
  "created_at": 1700000000,
  "kind": 1315,
  "tags": [
    ["t", "police"],
    ["g", "u2ed"],
    ["g", "u2edc"],
    ["g", "u2edcg"],
    ["expiration", "1700007200"],
    ["alt", "Roadstr: police report"]
  ],
  "content": "",
  "sig": "<64-byte hex signature>"
}
```

**Tag Definitions (canonical order)**:

| # | Tag | Description |
|---|-----|-------------|
| 1 | `t` | Event type string (from enum or custom text) |
| 2 | `g` (4 chars) | Geohash prefix level 4 (~20km cell) |
| 3 | `g` (5 chars) | Geohash prefix level 5 (~5km cell) |
| 4 | `g` (6 chars) | Geohash prefix level 6 (~1.2km cell) |
| 5 | `expiration` | Unix timestamp when event expires (NIP-40) |
| 6 | `alt` | Human-readable fallback (NIP-31) |

**Content field**:
- Empty string `""` for standard enum types
- For custom types: JSON with additional details: `{"description": "Fallen tree blocking right lane"}`

**Coordinate Encoding in Geohash**:
The precise coordinates are encoded in the geohash tags. A 6-character geohash provides ~1.2km × 0.6km precision. For exact marker placement, a 9-character geohash is included in the content for custom events, or the full coordinates are stored as additional tags:

```json
["lat", "48.8566140"],
["lon", "2.3522219"]
```

**Updated canonical tag order with precise coordinates:**

| # | Tag | Description |
|---|-----|-------------|
| 1 | `t` | Event type string |
| 2 | `g` (4 chars) | Geohash level 4 |
| 3 | `g` (5 chars) | Geohash level 5 |
| 4 | `g` (6 chars) | Geohash level 6 |
| 5 | `lat` | Latitude as decimal string (7 decimal places) |
| 6 | `lon` | Longitude as decimal string (7 decimal places) |
| 7 | `expiration` | NIP-40 expiry timestamp |
| 8 | `alt` | NIP-31 fallback text |

### 3.3 Road Event Confirmation (Kind 1316)

```json
{
  "id": "<sha256 hex>",
  "pubkey": "<32-byte hex pubkey>",
  "created_at": 1700003600,
  "kind": 1316,
  "tags": [
    ["e", "<referenced-event-id-hex>"],
    ["status", "still_there"],
    ["expiration", "1701213200"],
    ["alt", "Roadstr: event confirmation"]
  ],
  "content": "",
  "sig": "<64-byte hex signature>"
}
```

**Tag Definitions (canonical order)**:

| # | Tag | Description |
|---|-----|-------------|
| 1 | `e` | Referenced event ID (the report being confirmed/denied) |
| 2 | `status` | `"still_there"` or `"no_longer_there"` |
| 3 | `expiration` | NIP-40 expiry timestamp (always `created_at + 1209600`) |
| 4 | `alt` | Human-readable fallback (NIP-31) |

### 3.4 Event Types (Enum)

| Value | String | Default TTL | Color | Icon |
|-------|--------|-------------|-------|------|
| 0 | `police` | 2 hours | #0000FF Blue | 👮 |
| 1 | `speed_camera` | 30 days | #800080 Purple | 📷 |
| 2 | `traffic_jam` | 1 hour | #FF8C00 Orange | 🚗 |
| 3 | `accident` | 3 hours | #FF0000 Red | 💥 |
| 4 | `road_closure` | 7 days | #8B0000 Dark Red | 🚫 |
| 5 | `construction` | 7 days | #FFD700 Gold | 🚧 |
| 6 | `hazard` | 4 hours | #FF4500 OrangeRed | ⚠️ |
| 7 | `road_condition` | 6 hours | #4682B4 SteelBlue | 🛣️ |
| 8 | `pothole` | 7 days | #795548 Brown | 🕳️ |
| 9 | `fog` | 3 hours | #9E9E9E Gray | 🌫️ |
| 10 | `ice` | 6 hours | #00CED1 Cyan | 🧊 |
| 11 | `animal` | 1 hour | #4CAF50 Green | 🦌 |
| 12–254 | *reserved* | — | — | — |
| 255 | `other` | 2 hours | #808080 Gray | ℹ️ |

The `other` type (value 255) uses the `content` field for the type description. These are NOT compatible with the compact binary mesh format.

---

## 4. Compact Binary Format (Mesh-Ready)

The binary format enables transfer over MeshCore mesh networks. It must be under 135 bytes and contain enough information to reconstruct a fully verifiable Nostr event.

### 4.1 Design Constraints

| Field | Size | Notes |
|-------|------|-------|
| Signature | 64 bytes | Schnorr signature (non-negotiable for verification) |
| Public key | 32 bytes | secp256k1 pubkey (non-negotiable for verification) |
| **Remaining** | **39 bytes** | For timestamp, coordinates, type, metadata |

### 4.2 Report Event Binary Layout (109 bytes)

```
Offset  Size  Field
─────────────────────────────────────
0       1     Version/flags byte
1       32    Public key (raw bytes)
33      4     created_at (uint32 big-endian, unix seconds)
37      4     Latitude (int32 big-endian, value × 10^7)
41      4     Longitude (int32 big-endian, value × 10^7)
45      1     Event type (enum byte, 0–255)
46      64    Signature (raw bytes, 64 bytes Schnorr sig)
─────────────────────────────────────
Total: 110 bytes
```

**Version/flags byte**:
```
Bits 7-4: Version (0 = v1)
Bit 3:    Event class (0 = report, 1 = confirmation)
Bit 2:    Privacy mode (0 = persistent key, 1 = ephemeral key)
Bits 1-0: Reserved
```

**Coordinate Encoding**:
- Latitude: `int32(lat × 10^7)` — range ±900000000, precision ~1.1cm
- Longitude: `int32(lon × 10^7)` — range ±1800000000, precision ~1.1cm
- Example: 48.8566140°N → `488566140` → `0x1D1D8E8C`

### 4.3 Confirmation Event Binary Layout (134 bytes)

```
Offset  Size  Field
─────────────────────────────────────
0       1     Version/flags byte (bit 3 = 1)
1       32    Public key (raw bytes)
33      4     created_at (uint32 big-endian)
37      32    Referenced event ID (raw bytes, sha256)
69      1     Status (0 = no_longer_there, 1 = still_there)
70      64    Signature (raw bytes)
─────────────────────────────────────
Total: 134 bytes
```

### 4.4 Reconstruction Algorithm

To reconstruct a full Nostr event from the binary representation:

**For Report Events:**

1. Extract fields from binary
2. Compute geohash from lat/lon at levels 4, 5, 6
3. Look up type string from enum byte
4. Compute expiration: `created_at + 1209600` (14 days, fixed constant)
5. Format coordinates as decimal strings (7 decimal places)
6. Assemble canonical tag array (fixed order per §3.2)
7. Set content to `""`
8. Set kind to `1315`
9. Serialize as JSON: `[0, <pubkey_hex>, <created_at>, 1315, <tags>, ""]`
10. Compute event ID: `sha256(serialized)`
11. Verify: `schnorr_verify(id, sig, pubkey)`

**For Confirmation Events:**

1. Extract fields from binary
2. Compute expiration: `created_at + 1209600` (14 days, fixed constant)
3. Assemble canonical tag array (fixed order per §3.3, including expiration tag)
4. Set content to `""`
5. Set kind to `1316`
6. Serialize, compute ID, verify signature

**Canonical JSON Serialization Rules (for ID computation)**:
- No whitespace between elements
- Tags array elements in exact order defined above
- Coordinate strings: no trailing zeros beyond 7 decimal places, no leading zeros
- Latitude format: `-?[0-9]{1,2}\.[0-9]{7}` (always 7 decimal places)
- Longitude format: `-?[0-9]{1,3}\.[0-9]{7}` (always 7 decimal places)

### 4.5 Mesh Transfer Protocol (v1.5)

Events are transmitted on a shared MeshCore channel. The binary payload is sent as-is. Receiving nodes:
1. Decode the binary event
2. Reconstruct the full Nostr event
3. Verify the signature
4. If valid, publish to configured Nostr relays (internet bridge)
5. Use them as if they were received over Nostr and dispay in the app

Mesh nodes act as bridges between the mesh network and the Nostr relay network.

Another possibility is - if not seen on meshcore and received over Nostr, publish to MeshCore. The point is users cooperate to share in both mediums. People without internet connection get the reports through the mesh. And people publishing over mesh get their events published on nostr by internet connected people (anyone can broadcast the event, using the original nostr key, since the signature of reconstructed event is valid)

---

## 5. Expiration & Confirmation System

### 5.1 Relay Expiration vs Effective TTL

This specification distinguishes between two expiration concepts:

**Relay-side expiration (NIP-40):** The `expiration` tag in Nostr events is ALWAYS set to:
```
expiration = created_at + 1209600  (14 days)
```

This fixed constant applies to ALL event types. It tells relays when to garbage-collect the event. The 14-day window is long enough that confirmations can extend the effective lifetime of an event without the underlying data being deleted. It also ensures deterministic reconstruction from binary format.

**Effective TTL (client-side):** Clients compute an _effective expiration_ from the event type's default TTL (see §3.4) and any confirmations received. This determines when to stop displaying the marker.

### 5.2 Effective Expiration Calculation

Clients compute the **effective expiration** by aggregating confirmations:

```kotlin
fun computeEffectiveExpiry(
    report: RoadEvent,
    confirmations: List<Confirmation>
): Long {
    val baseTTL = report.type.defaultTTL
    var effectiveExpiry = report.createdAt + baseTTL

    // Sort confirmations by timestamp
    val sorted = confirmations.sortedBy { it.createdAt }

    for (conf in sorted) {
        when (conf.status) {
            STILL_THERE -> {
                // Extend expiry from confirmation time
                val newExpiry = conf.createdAt + baseTTL
                effectiveExpiry = maxOf(effectiveExpiry, newExpiry)
            }
            NO_LONGER_THERE -> {
                // Immediately expire at denial time
                effectiveExpiry = minOf(effectiveExpiry, conf.createdAt)
            }
        }
    }

    return effectiveExpiry
}
```

### 5.3 Conflict Resolution

When conflicting confirmations exist within a time window:

1. Collect all confirmations in the last `CONFLICT_WINDOW` (5 minutes)
2. Count `still_there` vs `no_longer_there`
3. Majority wins
4. Ties: event remains active (benefit of the doubt)

```kotlin
fun resolveConflicts(
    confirmations: List<Confirmation>,
    now: Long,
    conflictWindow: Long = 300 // 5 minutes
): EffectiveStatus {
    val recent = confirmations.filter { now - it.createdAt < conflictWindow }
    if (recent.isEmpty()) return ACTIVE

    val stillThere = recent.count { it.status == STILL_THERE }
    val noLonger = recent.count { it.status == NO_LONGER_THERE }

    return if (noLonger > stillThere) EXPIRED else ACTIVE
}
```

### 5.4 Display Behavior

| Condition | Visual | Behavior |
|-----------|--------|----------|
| Active, recently confirmed | Full opacity marker | Alert on approach |
| Active, near expiry (< 25% TTL remaining) | Semi-transparent marker | Alert on approach |
| Expired | Not shown | No alert |
| Denied by majority | Not shown | No alert |

---

## 6. Geohash & Spatial Querying

### 6.1 Geohash Precision Levels

| Level | Chars | Cell Size | Use Case |
|-------|-------|-----------|----------|
| 4 | `u2ed` | ~20km × 20km | Regional/highway queries |
| 5 | `u2edc` | ~5km × 5km | City-level queries |
| 6 | `u2edcg` | ~1.2km × 0.6km | Neighborhood queries |

Each report event includes all three levels as separate `g` tags, enabling queries at different zoom levels.

### 6.2 Querying Strategy

To query events in the user's visible area:

1. Determine the user's current position
2. Compute the geohash at the appropriate level for the current zoom/speed:
   - Driving on highway (>80 km/h): query level 4 (covers ~20km ahead)
   - Driving in city (<80 km/h): query level 5 (covers ~5km)
   - Stationary/browsing map: query level 6 for visible area
3. Query the center cell and 8 surrounding cells (9 cells total) to avoid boundary effects
4. Subscribe to Nostr relay with filter:

```json
{
  "kinds": [1315, 1316],
  "#g": ["u2ed", "u2ee", "u2eb", ...],
  "since": <now - max_ttl>
}
```

### 6.3 Subscription Management

- Maintain a rolling subscription as the user moves
- Re-subscribe when the user moves to a new geohash cell
- Keep confirmations (kind 1316) linked to visible reports via `#e` filter
- Unsubscribe from cells that are no longer relevant

```json
// Primary subscription: reports in area
["REQ", "roadstr-reports", {
  "kinds": [1315],
  "#g": ["u2edc", "u2edd", "u2edb", "u2ed9", "u2edf", ...],
  "since": 1699993200
}]

// Secondary subscription: confirmations for visible reports
["REQ", "roadstr-confirmations", {
  "kinds": [1316],
  "#e": ["<event-id-1>", "<event-id-2>", ...]
}]
```

---

## 7. OsmAnd Plugin Integration

### 7.1 AIDL Integration Points

The plugin communicates with OsmAnd via `IOsmAndAidlInterface`:

| AIDL Method | Purpose |
|-------------|---------|
| `addMapLayer` | Create "roadstr_events" layer |
| `addMapPoint` / `updateMapPoint` | Add/update event markers |
| `removeMapPoint` | Remove expired events |
| `addMapWidget` | Add "Report" quick-action widget |
| `addContextMenuButtons` | "Still there" / "No longer there" buttons on markers |
| `registerForUpdates` | Receive periodic position updates |

### 7.2 Map Layer

```kotlin
const val LAYER_ID = "roadstr_events"
const val LAYER_NAME = "Road Events"
const val LAYER_Z_ORDER = 5.5f // Above roads, below UI

osmandHelper.addMapLayer(LAYER_ID, LAYER_NAME, LAYER_Z_ORDER, null)
```

### 7.3 Event Markers

Each active event is displayed as a colored map point:

```kotlin
fun addEventMarker(event: RoadEvent) {
    osmandHelper.addMapPoint(
        layerId = LAYER_ID,
        pointId = event.id,
        shortName = event.type.icon,       // Single char: "P", "!", "X", etc.
        fullName = event.type.displayName, // "Police Check"
        typeName = formatTimeAgo(event),   // "12 min ago"
        color = event.type.color,          // Color int
        location = ALatLon(event.lat, event.lon),
        details = listOf(
            "Reported by: ${event.pubkey.short}",
            "Confirmations: ${event.confirmCount}",
            "Expires: ${formatExpiry(event)}"
        ),
        params = mapOf(
            "roadstr_event_id" to event.id,
            "roadstr_type" to event.type.name
        )
    )
}
```

### 7.4 Color Coding

| Type | Marker Color | Hex |
|------|-------------|-----|
| Police / Speed camera | Blue | `#2196F3` |
| Traffic jam / Accident | Red | `#F44336` |
| Road closure | Dark gray | `#424242` |
| Construction | Orange | `#FF9800` |
| Hazard / Road condition | Yellow | `#FFC107` |
| Custom | Gray | `#9E9E9E` |

### 7.5 Widget (Report Button)

A map widget that opens the report dialog:

```kotlin
osmandHelper.addMapWidget(
    id = "roadstr_report",
    menuTitle = "Report Road Event",
    lightIconName = "roadstr_report_day",
    darkIconName = "roadstr_report_night",
    text = "",
    intentOnClick = createReportIntent()
)
```

### 7.6 Context Menu Buttons

When a user taps an event marker, context menu buttons appear:

```kotlin
osmandHelper.addContextMenuButtons(
    appPackage = BuildConfig.APPLICATION_ID,
    leftButton = AContextMenuButton(
        buttonId = "roadstr_confirm",
        leftTextCaption = "Still there",
        leftIconName = "ic_action_confirm",
        needColorizeIcon = true
    ),
    rightButton = AContextMenuButton(
        buttonId = "roadstr_deny",
        rightTextCaption = "Gone",
        rightIconName = "ic_action_deny",
        needColorizeIcon = true
    )
)
```

---

## 8. User Interface

### 8.1 Main Plugin Activity

The plugin's own UI (separate from OsmAnd map) provides:

1. **Status bar**: Connection status (relay connected/disconnected), active subscriptions
2. **Event list**: Recent events in the area, sorted by distance
3. **Settings**: Configuration (see §9)
4. **Key management**: nsec import/generation, ephemeral key toggle

### 8.2 Report Dialog

Triggered by the map widget button or a menu action:

1. **Type selection**: Grid of event type buttons (icons + labels)
2. **Location**: Auto-filled from current GPS, with option to long-press map
3. **Custom description**: Text field (only shown if "Custom" type selected)
4. **Submit**: Signs and publishes the event

The dialog should be minimal and fast — usable while driving (large touch targets, minimal steps).

### 8.3 Event Detail View

Shown when tapping a marker's info area:

- Event type and icon
- Time reported (relative: "23 min ago")
- Reporter pubkey (truncated, e.g., `npub1a2b...x9z0`)
- Number of confirmations / denials
- Effective expiry countdown
- "Still there" / "Gone" action buttons

---

## 9. Configuration

### 9.1 Settings

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `nsec` | string | — | Private key (hex or bech32). Required. |
| `relays` | string[] | `["wss://relay.damus.io", "wss://nos.lol"]` | Nostr relay URLs |
| `use_ephemeral_keys` | bool | `false` | Generate throwaway key per event |
| `alert_enabled` | bool | `true` | Enable proximity alerts |
| `alert_distance` | int | `500` | Alert distance in meters |
| `alert_sound` | bool | `true` | Play sound on alert |
| `alert_vibration` | bool | `true` | Vibrate on alert |
| `query_speed_threshold` | int | `80` | km/h threshold for query level switch |
| `visible_types` | set | *all* | Which event types to display |
| `osmand_package` | string | `net.osmand.plus` | OsmAnd package to connect to |

### 9.2 Key Management

- **Import**: Paste nsec (bech32) or hex private key
- **Generate**: Create new keypair, display npub for sharing
- **Ephemeral mode**: When enabled, each event is signed with a fresh random key. The ephemeral pubkey is not stored. Events cannot be linked to the user's identity.
- **Key storage**: Encrypted in Android Keystore

### 9.3 Relay Management

- Add/remove relay URLs
- Per-relay connection status indicator
- Read/write toggle per relay (some relays for reading only)
- Connection retry with exponential backoff

---

## 10. Proximity Alerts

### 10.1 Alert Logic

```kotlin
fun checkProximityAlerts(
    userPos: Location,
    userBearing: Float,
    activeEvents: List<RoadEvent>
) {
    for (event in activeEvents) {
        val distance = userPos.distanceTo(event.location)
        val bearing = userPos.bearingTo(event.location)
        val isAhead = angleDifference(userBearing, bearing) < 45f

        if (distance <= settings.alertDistance && isAhead) {
            if (!event.alreadyAlerted) {
                triggerAlert(event)
                event.alreadyAlerted = true
            }
        }
    }
}
```

### 10.2 Alert Behavior

- **Trigger**: When user is within 500m and event is ahead (±45° of travel direction)
- **Sound**: Short distinctive tone (configurable)
- **Vibration**: Two short pulses
- **Visual**: Brief toast/overlay showing event type and distance
- **Cooldown**: Same event does not re-alert for 10 minutes
- **Suppression**: Expired or denied events never alert

### 10.3 Direction Awareness

"Ahead" is defined as within ±45° of the user's current bearing (heading). This prevents alerts for events on the opposite side of a divided highway or behind the user.

If bearing data is unavailable (GPS only, no compass), fall back to computed bearing from last two GPS fixes.

---

## 11. Privacy

### 11.1 Persistent Identity Mode (default)

- Events are signed with the user's configured nsec
- Pubkey is visible on all reports
- Reports can be linked to the same user
- Enables building reputation (future versions)

### 11.2 Ephemeral Key Mode

- A fresh random keypair is generated for each event
- The private key is discarded after signing
- Events cannot be linked to each other or to the user
- Trade-off: No reputation, no ability to update/delete own events

### 11.3 Relay Privacy

- Users should be aware that relay operators can see their IP
- For enhanced privacy: use Tor-capable relays or VPN
- The plugin does not implement Tor by default

---

## 12. Data Flow

### 12.1 Reporting an Event

```
User taps "Report" widget
    → Report dialog opens
    → User selects event type
    → Plugin reads current GPS position
    → Plugin computes geohash (levels 4, 5, 6)
    → Plugin computes expiration (created_at + type_TTL)
    → Plugin assembles Nostr event (kind 1315, canonical tags)
    → Plugin signs event with nsec (or ephemeral key)
    → Plugin publishes to all configured relays
    → Plugin adds marker to OsmAnd map layer
```

### 12.2 Confirming/Denying an Event

```
User taps event marker → context menu appears
    → User taps "Still there" or "Gone"
    → Plugin assembles confirmation event (kind 1316)
    → Plugin signs and publishes
    → Plugin updates effective expiry locally
    → Plugin updates marker opacity/visibility
```

### 12.3 Receiving Events

```
Plugin maintains subscriptions based on user position
    → Relay sends matching events (kind 1315, 1316)
    → Plugin verifies signature
    → Plugin computes effective expiry
    → If active: adds/updates marker on map
    → If expired: removes marker
    → If within alert distance: triggers alert
```

---

## 13. Nostr Protocol Details

### 13.1 Relay Communication (NIP-01)

Standard WebSocket protocol:

```
// Subscribe to events in area
→ ["REQ", "sub1", {"kinds": [1315], "#g": ["u2edc", ...], "since": <ts>}]

// Publish event
→ ["EVENT", <event-json>]

// Close subscription
→ ["CLOSE", "sub1"]
```

### 13.2 Expiration (NIP-40)

The `expiration` tag signals to relays that the event can be garbage-collected after the timestamp. Relays SHOULD NOT serve expired events. This naturally cleans up old road reports.

### 13.3 Unknown Kind Handling (NIP-31)

The `alt` tag provides a human-readable description for clients that don't understand kind 1315/1316:

```
["alt", "Roadstr: police report at 48.856614, 2.352222"]
```

### 13.4 Event ID Computation

```
id = sha256(serialize([0, pubkey, created_at, kind, tags, content]))
```

Where `serialize` produces minimal JSON (no whitespace, proper escaping per NIP-01).

---

## 14. Version Roadmap

### v1.0 — Core Functionality

- OsmAnd plugin with AIDL integration
- Report events (fixed enum types)
- View events as map markers
- Confirm/deny events
- Proximity alerts (500m, direction-aware)
- Multi-relay support
- Geohash-based area queries
- Key management (persistent + ephemeral)
- NIP-40 expiration

### v1.5 — Mesh Network Support

- MeshCore binary encoding/decoding (§4)
- Mesh-to-relay bridge functionality
- Receive events from mesh, verify, publish to Nostr
- Encode own events to binary, broadcast on mesh channel
- Off-grid reporting capability

### v2.0 — Enhanced Features

- Reputation system (trust scoring based on confirmation accuracy)
- Custom event types with text descriptions
- Event clustering (multiple reports at same location)
- Route-aware querying (query ahead on planned route, not just radius)
- Event photos/media attachments
- Multi-language support

---

## 15. Binary Format — Worked Examples

### 15.1 Report Event Example

**Input data:**
- Pubkey: `a1b2c3...` (32 bytes)
- Timestamp: `1700000000` (2023-11-14 22:13:20 UTC)
- Latitude: `48.8566140` → int32: `488566140` → `0x1D1D8E7C`
- Longitude: `2.3522219` → int32: `23522219` → `0x016720AB`
- Type: `0` (police)
- Signature: `d4e5f6...` (64 bytes)

**Binary (hex):**
```
00                              # v1, report, persistent key
a1b2c3...                       # 32 bytes pubkey
65543B80                        # created_at big-endian
1D1D8E7C                        # latitude × 10^7
016720AB                        # longitude × 10^7
00                              # type: police
d4e5f6...                       # 64 bytes signature
```

**Reconstructed Nostr event:**
```json
{
  "id": "<computed sha256>",
  "pubkey": "a1b2c3...",
  "created_at": 1700000000,
  "kind": 1315,
  "tags": [
    ["t", "police"],
    ["g", "u09t"],
    ["g", "u09tu"],
    ["g", "u09tun"],
    ["lat", "48.8566140"],
    ["lon", "2.3522219"],
    ["expiration", "1700007200"],
    ["alt", "Roadstr: police report"]
  ],
  "content": "",
  "sig": "d4e5f6..."
}
```

### 15.2 Confirmation Event Example

**Binary (134 bytes):**
```
08                              # v1, confirmation, persistent key
b2c3d4...                       # 32 bytes pubkey (confirmer)
65544DA0                        # created_at (1 hour later)
<32 bytes referenced event ID>  # event being confirmed
01                              # status: still_there
e5f6a7...                       # 64 bytes signature
```

---

## 16. Error Handling

| Scenario | Behavior |
|----------|----------|
| No relay connection | Queue events locally, publish when connected |
| OsmAnd not installed | Show dialog directing user to install |
| OsmAnd AIDL unavailable | Retry connection with backoff |
| GPS unavailable | Disable report button, show warning |
| Invalid event signature | Silently discard event |
| Expired event received | Ignore, do not display |
| Duplicate event | Deduplicate by event ID |

---

## 17. Security Considerations

- **No injection**: Event content is never interpreted as code
- **Signature verification**: All received events are verified before display
- **Key storage**: nsec stored in Android Keystore (hardware-backed when available)
- **Relay trust**: Relays can withhold events but cannot forge them (signatures)
- **Spam**: v1 has no spam protection beyond NIP-40 expiration. Future versions may add proof-of-work (NIP-13) requirements.
- **Location privacy**: Reporting an event reveals approximate location. Ephemeral keys mitigate identity linkage but not location exposure.

---

## 18. Dependencies

| Dependency | Purpose |
|------------|---------|
| OsmAnd AIDL | Map integration |
| secp256k1-kmp | Schnorr signatures (Nostr) |
| OkHttp | WebSocket connections to relays |
| Geohash library | Coordinate ↔ geohash conversion |
| Android Keystore | Secure key storage |
| kotlinx-serialization | JSON handling |

---

## 19. File Structure (Proposed)

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── kotlin/com/roadstr/
│   │   ├── RoadstrApplication.kt
│   │   ├── RoadstrService.kt          # Foreground service
│   │   ├── nostr/
│   │   │   ├── NostrClient.kt         # Relay WebSocket client
│   │   │   ├── Event.kt               # Nostr event model
│   │   │   ├── EventSigner.kt         # Signing/verification
│   │   │   ├── Subscription.kt        # REQ/CLOSE management
│   │   │   └── BinaryCodec.kt         # Compact binary encoder/decoder
│   │   ├── model/
│   │   │   ├── RoadEvent.kt           # Road event domain model
│   │   │   ├── EventType.kt           # Type enum with TTL/colors
│   │   │   └── Confirmation.kt        # Confirmation model
│   │   ├── osmand/
│   │   │   ├── OsmandAidlHelper.kt    # AIDL wrapper
│   │   │   ├── MapLayerManager.kt     # Marker management
│   │   │   └── WidgetManager.kt       # Widget/button management
│   │   ├── location/
│   │   │   ├── LocationMonitor.kt     # Position tracking
│   │   │   ├── GeohashUtil.kt         # Geohash computation
│   │   │   └── AlertEngine.kt         # Proximity alerts
│   │   ├── ui/
│   │   │   ├── MainActivity.kt        # Settings/status activity
│   │   │   ├── ReportDialogActivity.kt # Report dialog
│   │   │   └── KeyManagementFragment.kt
│   │   └── storage/
│   │       ├── EventCache.kt          # Local event cache
│   │       ├── KeyStore.kt            # Secure key management
│   │       └── Settings.kt            # App preferences
│   ├── aidl/net/osmand/aidl/          # OsmAnd AIDL interfaces
│   └── res/
│       ├── drawable/                   # Icons for markers/widgets
│       ├── layout/                     # UI layouts
│       └── values/                     # Strings, colors
└── build.gradle.kts
```
