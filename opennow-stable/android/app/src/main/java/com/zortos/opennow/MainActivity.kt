package com.zortos.opennow

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    private var nativeSurface: SurfaceViewRenderer? = null
    private var eglBase: EglBase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(GfnPlugin::class.java)
        super.onCreate(savedInstanceState)
        
        // Initialize WebRTC EglBase
        eglBase = EglBase.create()
        
        // Initialize Native SurfaceView behind the WebView
        val root = findViewById<ViewGroup>(android.R.id.content)
        nativeSurface = SurfaceViewRenderer(this)
        nativeSurface?.init(eglBase?.eglBaseContext, null)
        nativeSurface?.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        nativeSurface?.setEnableHardwareScaler(true)
        nativeSurface?.visibility = SurfaceView.GONE // Hidden by default
        
        root.addView(nativeSurface, 0, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Draw edge-to-edge so the WebView fills under system bars.
        // CSS then uses env(safe-area-inset-*) to avoid overlap.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Force transparent system bars at runtime - required for HyperOS/MIUI
        // to correctly populate env(safe-area-inset-*) in the WebView.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    fun getNativeRenderer(): SurfaceViewRenderer? = nativeSurface
    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

    /** Toggles the visibility of the native surface and makes WebView transparent. */
    fun setNativeMode(enabled: Boolean) {
        runOnUiThread {
            nativeSurface?.visibility = if (enabled) android.view.SurfaceView.VISIBLE else android.view.SurfaceView.GONE
            if (enabled) {
                bridge.webView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                bridge.webView.setBackgroundColor(Color.parseColor("#121212")) // Default app background
            }
        }
    }

    /** Called by GfnPlugin.setOrientation to lock or restore screen rotation. */
    fun applyOrientation(mode: String) {
        requestedOrientation = when (mode) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait"  -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else        -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED  // "sensor" / default
        }
    }

    // Receives the opennow://auth?code=... redirect from the system browser
    // after the user completes the NVIDIA login flow.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data ?: return
        // Only forward to plugin if this is a real OAuth callback (has a code param).
        // The bare opennow://auth link is just the return-to-app button -- ignore it.
        if (uri.scheme == "opennow" && uri.host == "auth" && uri.getQueryParameter("code") != null) {
            val plugin = bridge.getPlugin("GfnPlugin")?.getInstance() as? GfnPlugin
            plugin?.handleOAuthRedirect(uri)
        }
    }
}
