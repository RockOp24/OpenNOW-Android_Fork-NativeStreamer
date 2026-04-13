package com.zortos.opennow

import android.content.Context
import android.util.Log
import com.getcapacitor.JSObject
import livekit.org.webrtc.*
import livekit.org.webrtc.PeerConnection.IceConnectionState
import livekit.org.webrtc.PeerConnection.PeerConnectionState
import livekit.org.webrtc.audio.JavaAudioDeviceModule
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
    private var dataChannelInput1: DataChannel? = null
    private var dataChannelInput2: DataChannel? = null

    fun sendNativeInput(channelName: String, data: ByteArray) {
        val channel = if (channelName == "input_1") dataChannelInput1 else dataChannelInput2
        if (channel?.state() == DataChannel.State.OPEN) {
            val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true)
            channel.send(buffer)
        } else {
            Log.w(TAG, "Cannot send input: DataChannel $channelName is not OPEN (state: ${channel?.state()})")
        }
    }

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

                        (context as? android.app.Activity)?.runOnUiThread {
                            android.widget.Toast.makeText(context, "Gathered ICE Candidate", android.widget.Toast.LENGTH_SHORT).show()
                        }
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
                    // Control channel might be received here, but input channels are created locally
                }

                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Log.i(TAG, "Track added: ${receiver?.track()?.kind()}")
                    receiver?.track()?.let { track ->
                        if (track is VideoTrack) {
                            Log.i(TAG, "Video Track Added - Attaching Sink")
                            track.setEnabled(true)
                            track.addSink(renderer)
                            
                            (context as? android.app.Activity)?.runOnUiThread {
                                android.widget.Toast.makeText(context, "[FOUND] Video Track Attached!", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            })

            // Create DataChannels before setting remote description (matches JS createDataChannels)
            val input1Init = DataChannel.Init().apply {
                ordered = true
            }
            dataChannelInput1 = peerConnection?.createDataChannel("input_1", input1Init)
            dataChannelInput1?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(p0: Long) {}
                override fun onStateChange() {
                    if (dataChannelInput1?.state() == DataChannel.State.OPEN) {
                        Log.i(TAG, "DataChannel input_1 OPEN, sending protocol init [0x02]")
                        val buffer = java.nio.ByteBuffer.allocate(1).put(2.toByte())
                        buffer.flip()
                        dataChannelInput1?.send(DataChannel.Buffer(buffer, true))
                    }
                }
                override fun onMessage(p0: DataChannel.Buffer?) {
                    p0?.data?.let {
                        val arr = ByteArray(it.remaining())
                        it.get(arr)
                        if (arr.size == 1 && arr[0] == 6.toByte()) {
                            Log.i(TAG, "DataChannel input_1 received [0x06], input is ready!")
                            (context as? android.app.Activity)?.runOnUiThread {
                                android.widget.Toast.makeText(context, "Input Ready!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            onEvent("onInputReady", JSObject())
                        }
                    }
                }
            })
            
            val input2Init = DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 0
            }
            dataChannelInput2 = peerConnection?.createDataChannel("input_2", input2Init)

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

                    (context as? android.app.Activity)?.runOnUiThread {
                        android.widget.Toast.makeText(context, "Handshake Answer Generated!", android.widget.Toast.LENGTH_SHORT).show()
                    }
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
            dataChannelInput1?.close()
            dataChannelInput2?.close()
            peerConnection?.close()
            factory?.dispose()
            Log.i(TAG, "Streamer stopped and disposed")
        }
    }
}
