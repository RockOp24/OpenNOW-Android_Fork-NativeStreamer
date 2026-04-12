package com.zortos.opennow

import android.content.Context
import android.util.Log
import com.getcapacitor.JSObject
import org.webrtc.*
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.PeerConnectionState
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

/**
 * GfnNativeStreamer - Handles the native WebRTC connection for GeForce NOW.
 * This class uses hardware-accelerated MediaCodec for ultra-low latency.
 */
class GfnNativeStreamer(
    private val context: Context,
    private val renderer: SurfaceViewRenderer,
    private val eglContext: EglBase.Context,
    private val onEvent: (String, JSObject) -> Unit
) {
    private val TAG = "GfnNativeStreamer"
    private val executor = Executors.newSingleThreadExecutor()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    init {
        executor.execute {
            initializeFactory()
        }
    }

    private fun initializeFactory() {
        Log.i(TAG, "Initializing PeerConnectionFactory...")
        
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        
        // Use hardware acceleration via MediaCodec
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)
        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .createPeerConnectionFactory()
            
        Log.i(TAG, "PeerConnectionFactory initialized")
    }

    /**
     * Start the native stream with an NVIDIA Offer SDP.
     */
    fun startWithOffer(sdp: String, iceServers: List<PeerConnection.IceServer>) {
        executor.execute {
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                
                override fun onIceConnectionChange(state: IceConnectionState?) {
                    Log.i(TAG, "ICE Connection State: $state")
                }

                override fun onConnectionChange(state: PeerConnectionState?) {
                    Log.i(TAG, "PeerConnection State: $state")
                    val event = JSObject()
                    event.put("state", state?.toString())
                    onEvent("connectionChanged", event)
                }

                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        val event = JSObject()
                        event.put("candidate", it.sdp)
                        event.put("sdpMid", it.sdpMid)
                        event.put("sdpMLineIndex", it.sdpMLineIndex)
                        onEvent("onLocalIceCandidate", event)
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

                override fun onAddStream(stream: MediaStream?) {
                    Log.i(TAG, "Stream added: ${stream?.id}")
                    if (stream?.videoTracks?.isNotEmpty() == true) {
                        val track = stream.videoTracks[0]
                        track.setEnabled(true)
                        track.addSink(renderer)
                    }
                }

                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {
                    Log.i(TAG, "DataChannel received: ${channel?.label()}")
                    if (channel?.label() == "input_1") {
                        dataChannel = channel
                    }
                }

                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Log.i(TAG, "Track added: ${receiver?.track()?.kind()}")
                    receiver?.track()?.let { track ->
                        if (track is VideoTrack) {
                            track.addSink(renderer)
                        }
                    }
                }
            })

            // Set Remote Description (NVIDIA Offer)
            val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    Log.i(TAG, "Remote Description set successfully")
                    createAnswer()
                }
                override fun onCreateFailure(p0: String?) {
                    Log.e(TAG, "Failed to set remote description: $p0")
                }
                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "Failed to set remote description: $p0")
                }
            }, remoteSdp)
        }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                Log.i(TAG, "Local Answer created")
                sdp?.let {
                    // In a real implementation, we would Munge the SDP here to match NVST requirements.
                    // For the initial draft, we'll send it back as-is.
                    peerConnection?.setLocalDescription(this, it)
                    val event = JSObject()
                    event.put("sdp", it.description)
                    onEvent("onAnswerCreated", event)
                }
            }
            override fun onSetSuccess() {
                Log.i(TAG, "Local Description set successfully")
            }
            override fun onCreateFailure(p0: String?) {
                Log.e(TAG, "Failed to create answer: $p0")
            }
            override fun onSetFailure(p0: String?) {
                Log.e(TAG, "Failed to set local description: $p0")
            }
        }, constraints)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        executor.execute {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun stop() {
        executor.execute {
            dataChannel?.close()
            peerConnection?.close()
            factory?.dispose()
            Log.i(TAG, "Streamer stopped and disposed")
        }
    }
}
