package com.zortos.opennow.streamer

import android.content.Context
import org.webrtc.AudioDeviceModule
import org.webrtc.BuiltinAudioDecoderFactoryFactory
import org.webrtc.BuiltinAudioEncoderFactoryFactory
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.JavaAudioDeviceModule
import org.webrtc.PeerConnectionFactory

object WebRTCFactory {
    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)
            initialized = true
        }
    }

    fun createAudioDeviceModule(context: Context): AudioDeviceModule {
        return JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
    }

    fun createPeerConnectionFactory(context: Context, eglContext: EglBase.Context?): PeerConnectionFactory {
        initialize(context)
        val audioDeviceModule = createAudioDeviceModule(context)
        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioEncoderFactoryFactory(BuiltinAudioEncoderFactoryFactory())
            .setAudioDecoderFactoryFactory(BuiltinAudioDecoderFactoryFactory())
            .createPeerConnectionFactory()
            .also {
                audioDeviceModule.release()
            }
    }
}
