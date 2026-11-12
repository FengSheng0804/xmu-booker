-keep class com.example.xmu_sports_booking.CaptureBridge {
    public <init>(io.flutter.embedding.android.FlutterActivity, io.flutter.plugin.common.MethodChannel);
    public void close();
}
# Conscrypt's pre-Android-5 socket adapters reference platform-private classes absent from modern SDKs.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

-keep class ai.onnxruntime.** { *; }

# Pairing uses reflection and native entry points, including TLS exporter methods.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.spake2.** { *; }
-keep class org.conscrypt.** { *; }
