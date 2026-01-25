# Roadstr Android

Decentralized road event reporting app using Nostr (NIP-99-like custom kinds 1315/1316) and OsmAnd map integration.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
```

Output APK: `app/build/outputs/apk/release/app-release.apk`

## Signing

Release builds are signed automatically. Credentials are read from `keystore.properties` (gitignored) in the project root:

```properties
storeFile=/path/to/keystore.jks
storePassword=...
keyAlias=my-key-alias
keyPassword=...
```

The keystore file itself lives outside the repo at the path specified in `storeFile`.

## Architecture

- **NostrClient** — WebSocket connections to relays with exponential backoff reconnect
- **RoadstrService** — Foreground service: location monitoring, Nostr subscriptions, OsmAnd AIDL integration
- **EventCache** — In-memory cache with 60s cleanup timer; callbacks for map removal on expiry
- **MapLayerManager** — Manages OsmAnd map layer points via AIDL
- **AlertEngine** — Proximity-based notifications for nearby road events

## Key Nostr Kinds

- **1315** — Road event report (type, lat/lon, geohash, expiration)
- **1316** — Confirmation/denial of an existing report

## Testing

```bash
./gradlew test
```
