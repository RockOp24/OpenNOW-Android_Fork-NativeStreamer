package com.zortos.opennow.streamer

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.zortos.opennow.R
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class VideoRendererManager(private val activity: Activity) {
    private var eglBase: EglBase? = null
    private var overlayContainer: FrameLayout? = null
    private var renderer: SurfaceViewRenderer? = null

    fun attachOverlay(eglContext: EglBase.Context?): SurfaceViewRenderer {
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val existingContainer = overlayContainer
        val container = if (existingContainer != null) {
            existingContainer
        } else {
            val inflated = LayoutInflater.from(activity)
                .inflate(R.layout.streamer_surface_layout, contentRoot, false) as FrameLayout
            inflated.visibility = View.VISIBLE
            contentRoot.addView(inflated)
            overlayContainer = inflated
            inflated
        }

        eglBase = eglBase ?: EglBase.create(eglContext)

        if (renderer == null) {
            renderer = SurfaceViewRenderer(activity).apply {
                init(eglBase?.eglBaseContext, null)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setZOrderMediaOverlay(true)
                setMirror(false)
            }
            container.addView(
                renderer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        container.bringToFront()
        container.visibility = View.VISIBLE
        renderer?.visibility = View.VISIBLE
        return renderer as SurfaceViewRenderer
    }

    fun hideOverlay() {
        renderer?.visibility = View.GONE
        overlayContainer?.visibility = View.GONE
    }

    fun onPause() {
        renderer?.pauseVideo()
    }

    fun onResume() {
        renderer?.onResume()
    }

    fun release() {
        renderer?.release()
        renderer = null
        overlayContainer?.let { container ->
            (container.parent as? ViewGroup)?.removeView(container)
        }
        overlayContainer = null
        eglBase?.release()
        eglBase = null
    }
}
