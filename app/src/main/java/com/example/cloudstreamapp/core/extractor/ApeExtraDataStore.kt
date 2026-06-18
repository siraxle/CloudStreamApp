package com.example.cloudstreamapp.core.extractor

/**
 * Holds the most recently parsed APE extradata so the JNI can read it
 * when FfmpegAudioDecoder passes null (because getExtraData() has no APE case).
 *
 * Written by ApeExtractor.parseHeader() before format() is called;
 * read by ffmpegInitialize() in ffmpeg_jni.cc via GetStaticFieldID("data","[B").
 */
object ApeExtraDataStore {
    @JvmField @Volatile var data: ByteArray? = null
}
