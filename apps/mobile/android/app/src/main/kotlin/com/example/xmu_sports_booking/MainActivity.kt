package com.example.xmu_sports_booking

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var localBridge: AutoCloseable? = null
    private var captureBridge: AutoCloseable? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        localBridge =
            com.example.xmu_sports_booking.local.LocalBridge(
                this,
                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "xmu/local_booking"),
            )
        if (BuildConfig.OWN_SPORTS_AUTH_ENABLED && android.os.Build.VERSION.SDK_INT >= 30) {
            // The flavor flag follows the compiled implementation, independently of application ID.
            val bridge =
                Class.forName("com.example.xmu_sports_booking.CaptureBridge")
                    .getConstructor(FlutterActivity::class.java, MethodChannel::class.java)
            captureBridge =
                bridge.newInstance(
                    this,
                    MethodChannel(
                        flutterEngine.dartExecutor.binaryMessenger,
                        "xmu/own_sports_capture",
                    ),
                ) as AutoCloseable
        }
    }

    override fun onDestroy() {
        localBridge?.close()
        captureBridge?.close()
        super.onDestroy()
    }
}
