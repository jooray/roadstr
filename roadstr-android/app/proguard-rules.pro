# Keep Nostr event classes for serialization
-keep class com.roadstr.nostr.NostrEvent { *; }
-keep class com.roadstr.model.** { *; }

# Keep secp256k1 JNI
-keep class fr.acinq.secp256k1.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
