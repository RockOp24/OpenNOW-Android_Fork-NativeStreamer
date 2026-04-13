package com.zortos.opennow.streamer

import android.content.Context
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.audio.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

class NativeStreamer(private val context: Context) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSink: VideoSink? = null
    private var eglBase: EglBase? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    fun initialize(eglContext: EglBase.Context?) {
        if (eglBase == null) {
            eglBase = EglBase.create(eglContext)
        }
        if (peerConnectionFactory == null) {
            peerConnectionFactory = WebRTCFactory.createPeerConnectionFactory(context, eglBase?.eglBaseContext)
        }
    }

    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        observer: PeerConnection.Observer,
    ) {
        val factory = peerConnectionFactory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            enableCpuOveruseDetection = true
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            keyType = PeerConnection.KeyType.ECDSA
        }

        val wrappedObserver = object : PeerConnection.Observer by observer {
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack?.removeSink(videoSink)
                    remoteVideoTrack = track
                    videoSink?.let { sink ->
                        remoteVideoTrack?.addSink(sink)
                    }
                }
                observer.onTrack(transceiver)
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack?.removeSink(videoSink)
                    remoteVideoTrack = track
                    videoSink?.let { sink ->
                        remoteVideoTrack?.addSink(sink)
                    }
                }
                observer.onAddTrack(receiver, mediaStreams)
            }
        }

        peerConnection = factory.createPeerConnection(rtcConfig, wrappedObserver)
        peerConnection?.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        peerConnection?.addTransceiver(
            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )
        if (localAudioSource == null) {
            localAudioSource = factory.createAudioSource(MediaConstraints())
        }
        if (localAudioTrack == null) {
            localAudioTrack = factory.createAudioTrack("native_mic_track", localAudioSource)
        }
        localAudioTrack?.let { track ->
            track.setEnabled(true)
            peerConnection?.addTrack(track, listOf("native_audio_stream"))
        }
        peerConnection?.createDataChannel("input", org.webrtc.DataChannel.Init())
        peerConnection?.setAudioPlayout(true)
        peerConnection?.setAudioRecording(true)
    }

    fun setRemoteDescription(sdp: SessionDescription, callback: () -> Unit) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
            override fun onSetSuccess() = callback()
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) = Unit
        }, sdp)
    }

    fun createAnswer(callback: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                val answer = sessionDescription ?: return
                val preferred = SessionDescription(
                    answer.type,
                    preferVideoCodecs(answer.description, listOf("AV1", "H265", "H264"))
                )
                applyCodecPreferences(pc)
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
                    override fun onSetSuccess() = callback(preferred)
                    override fun onCreateFailure(error: String?) = Unit
                    override fun onSetFailure(error: String?) = Unit
                }, preferred)
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) = Unit
        }, constraints)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun attachVideoRenderer(surfaceView: SurfaceViewRenderer) {
        remoteVideoTrack?.removeSink(videoSink)
        videoSink = surfaceView
        remoteVideoTrack?.addSink(surfaceView)
    }

    fun dispose() {
        remoteVideoTrack?.removeSink(videoSink)
        videoSink = null
        remoteVideoTrack = null
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        peerConnection?.dispose()
        peerConnection = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
    }

    private fun preferVideoCodecs(sdp: String, codecNames: List<String>): String {
        val lines = sdp.split("\r\n").toMutableList()
        val payloadToCodec = mutableMapOf<String, String>()
        for (line in lines) {
            if (line.startsWith("a=rtpmap:")) {
                val body = line.removePrefix("a=rtpmap:")
                val payload = body.substringBefore(" ").trim()
                val codec = body.substringAfter(" ").substringBefore("/").trim()
                payloadToCodec[payload] = codec
            }
        }

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("m=video ")) continue
            val parts = line.split(" ").toMutableList()
            if (parts.size <= 3) continue
            val payloads = parts.subList(3, parts.size).toList()
            val prioritized = mutableListOf<String>()
            for (codecName in codecNames) {
                prioritized += payloads.filter { payloadToCodec[it]?.contains(codecName, ignoreCase = true) == true }
            }
            val ordered = (prioritized + payloads).distinct()
            lines[i] = (parts.subList(0, 3) + ordered).joinToString(" ")
            break
        }
        return lines.joinToString("\r\n")
    }

    private fun applyCodecPreferences(pc: PeerConnection) {
        for (transceiver in pc.transceivers) {
            if (transceiver.mediaType != MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) continue
            val codecs = transceiver.receiver.parameters.codecs ?: continue
            val preferred = mutableListOf<RtpParameters.Codec>()
            preferred += codecs.filter { it.name.contains("AV1", ignoreCase = true) }
            preferred += codecs.filter { it.name.contains("H265", ignoreCase = true) || it.name.contains("HEVC", ignoreCase = true) }
            preferred += codecs.filter { it.name.contains("H264", ignoreCase = true) }
            preferred += codecs.filterNot { codec -> preferred.any { it.payloadType == codec.payloadType } }
            if (preferred.isNotEmpty()) {
                transceiver.setCodecPreferences(preferred)
            }
        }
    }
}
