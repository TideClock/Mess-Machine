# The engine is plain Java with no reflection; nothing extra to keep.
# accounts, Slice C: OkHttp (the live websocket only) names optional security providers that are not on Android
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
