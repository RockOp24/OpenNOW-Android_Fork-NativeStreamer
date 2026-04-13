package com.zortos.opennow.streamer

import com.getcapacitor.JSObject
import com.zortos.opennow.GfnPlugin

data class SessionInfo(val sessionId: String? = null)

data class IceCandidatePayload(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
)

class SignalingBridge(private val plugin: GfnPlugin) {
    fun handleSignalingEvent(event: JSObject) {
        when (event.getString("type")) {
            "offer" -> {
                val sdp = event.getString("sdp") ?: return
                onOfferReceived(sdp, SessionInfo())
            }
            "remote-ice" -> {
                val candidateObj = event.getJSObject("candidate") ?: return
                val candidate = candidateObj.getString("candidate") ?: return
                val sdpMid = candidateObj.getString("sdpMid")
                val sdpMLineIndex = candidateObj.optInt("sdpMLineIndex", 0)
                onIceCandidateReceived(
                    IceCandidatePayload(
                        candidate = candidate,
                        sdpMid = sdpMid,
                        sdpMLineIndex = sdpMLineIndex,
                    )
                )
            }
        }
    }

    fun onOfferReceived(sdp: String, session: SessionInfo) {
        session.sessionId
        plugin.handleNativeOfferFromSignaling(sdp)
    }

    fun onIceCandidateReceived(candidate: IceCandidatePayload) {
        plugin.handleNativeIceFromSignaling(
            candidate.candidate,
            candidate.sdpMid,
            candidate.sdpMLineIndex
        )
    }
}
