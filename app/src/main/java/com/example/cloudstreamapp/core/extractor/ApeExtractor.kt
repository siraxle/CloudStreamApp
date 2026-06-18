package com.example.cloudstreamapp.core.extractor

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val MIME_AUDIO_APE = "audio/x-ape"

/**
 * Extracts compressed frames from Monkey's Audio (.ape) files (version ≥ 3.98)
 * and passes them to FfmpegAudioDecoder with FFmpeg-compatible extradata.
 *
 * Extradata layout (26 bytes, matches libavformat/ape.c codec parameter output):
 *   [0..1]  fileversion       uint16 LE
 *   [2..3]  compressiontype   uint16 LE
 *   [4..5]  formatflags       uint16 LE
 *   [6..9]  blocksperframe    uint32 LE
 *   [10..13] finalframeblocks uint32 LE
 *   [14..17] totalframes      uint32 LE
 *   [18..19] bps              uint16 LE
 *   [20..21] channels         uint16 LE
 *   [22..25] samplerate       uint32 LE
 */
class ApeExtractor : Extractor {

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var headerParsed = false

    private var version = 0
    private var compressionType = 0
    private var formatFlags = 0
    private var sampleRate = 0
    private var channels = 0
    private var bitsPerSample = 0
    private var blocksPerFrame = 0
    private var finalFrameBlocks = 0
    private var totalFrames = 0
    private var firstFrameOffset = 0L
    private var totalAudioBytes = 0L
    private var seekTableRelative = IntArray(0)
    private var currentFrame = 0
    private var durationUs = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val buf = ByteArray(6)
        input.peekFully(buf, 0, 6)
        if (buf[0] != 'M'.code.toByte() || buf[1] != 'A'.code.toByte() ||
            buf[2] != 'C'.code.toByte() || buf[3] != ' '.code.toByte()) return false
        val ver = (buf[5].toInt() and 0xFF) shl 8 or (buf[4].toInt() and 0xFF)
        return ver >= 3980
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    @Throws(java.io.IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerParsed) {
            parseHeader(input)
            headerParsed = true
        }
        if (currentFrame >= totalFrames) return Extractor.RESULT_END_OF_INPUT
        if (seekTableRelative.isEmpty()) return Extractor.RESULT_END_OF_INPUT

        val relOffset = seekTableRelative[currentFrame].toLong() and 0xFFFFFFFFL
        val frameOffset = firstFrameOffset + relOffset
        val frameSize: Int = if (currentFrame + 1 < seekTableRelative.size) {
            val nextRel = seekTableRelative[currentFrame + 1].toLong() and 0xFFFFFFFFL
            (nextRel - relOffset).toInt()
        } else {
            (totalAudioBytes - relOffset).toInt()
        }

        if (frameSize <= 0) {
            currentFrame++
            return Extractor.RESULT_CONTINUE
        }

        if (input.position != frameOffset) {
            seekPosition.position = frameOffset
            return Extractor.RESULT_SEEK
        }

        val timeUs = currentFrame.toLong() * blocksPerFrame * C.MICROS_PER_SECOND / sampleRate
        trackOutput!!.sampleData(input, frameSize, false)
        trackOutput!!.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null)
        currentFrame++
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        if (!headerParsed || blocksPerFrame == 0) {
            currentFrame = 0
            return
        }
        currentFrame = (timeUs * sampleRate / (blocksPerFrame.toLong() * C.MICROS_PER_SECOND))
            .toInt().coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
    }

    override fun release() {}

    @Throws(java.io.IOException::class)
    private fun parseHeader(input: ExtractorInput) {
        // APE Descriptor — fixed 52 bytes for version ≥ 3.98
        val descriptor = ByteArray(52)
        input.readFully(descriptor, 0, 52)
        val desc = ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN)
        desc.position(4)                                       // skip "MAC "
        version            = desc.short.toInt() and 0xFFFF
        desc.short                                             // nPadding
        val descLen        = desc.int
        val headerLen      = desc.int
        val seekTableLen   = desc.int
        val headerDataLen  = desc.int
        val audioLow       = desc.int.toLong() and 0xFFFFFFFFL
        val audioHigh      = desc.int.toLong() and 0xFFFFFFFFL
        totalAudioBytes    = (audioHigh shl 32) or audioLow

        if (descLen > 52) input.skipFully(descLen - 52)

        // APE Header — 24 bytes
        val hdrRead = headerLen.coerceAtMost(24)
        val header = ByteArray(hdrRead)
        input.readFully(header, 0, hdrRead)
        if (headerLen > 24) input.skipFully(headerLen - 24)
        val hdr = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        compressionType  = hdr.short.toInt() and 0xFFFF
        formatFlags      = hdr.short.toInt() and 0xFFFF
        blocksPerFrame   = hdr.int
        finalFrameBlocks = hdr.int
        totalFrames      = hdr.int
        bitsPerSample    = hdr.short.toInt() and 0xFFFF
        channels         = hdr.short.toInt() and 0xFFFF
        sampleRate       = hdr.int

        // Seek table — offsets relative to start of audio data
        val seekEntries = seekTableLen / 4
        val seekBytes = ByteArray(seekTableLen)
        input.readFully(seekBytes, 0, seekTableLen)
        val stBuf = ByteBuffer.wrap(seekBytes).order(ByteOrder.LITTLE_ENDIAN)
        seekTableRelative = IntArray(seekEntries) { stBuf.int }

        if (headerDataLen > 0) input.skipFully(headerDataLen)

        firstFrameOffset = (descLen + headerLen + seekTableLen + headerDataLen).toLong()

        val totalBlocks = (totalFrames - 1).toLong() * blocksPerFrame + finalFrameBlocks
        durationUs = if (sampleRate > 0) totalBlocks * C.MICROS_PER_SECOND / sampleRate else C.TIME_UNSET

        // 26-byte extradata for FFmpeg APE decoder (libavcodec/ape.c)
        val extraData = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).run {
            putShort(version.toShort())
            putShort(compressionType.toShort())
            putShort(formatFlags.toShort())
            putInt(blocksPerFrame)
            putInt(finalFrameBlocks)
            putInt(totalFrames)
            putShort(bitsPerSample.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            array()
        }

        // JNI fallback: store extradata globally so ffmpegInitialize can read it if
        // FfmpegAudioDecoder.getExtraData() returns null for audio/x-ape.
        ApeExtraDataStore.data = extraData

        trackOutput!!.format(
            Format.Builder()
                .setSampleMimeType(MIME_AUDIO_APE)
                .setChannelCount(channels)
                .setSampleRate(sampleRate)
                .setInitializationData(listOf(extraData))
                .build()
        )

        val capturedFirstFrame  = firstFrameOffset
        val capturedDuration    = durationUs
        val capturedSampleRate  = sampleRate.toLong()
        val capturedBlocks      = blocksPerFrame.toLong()
        val capturedSeekTable   = seekTableRelative
        val capturedTotalFrames = totalFrames

        extractorOutput!!.seekMap(object : SeekMap {
            override fun isSeekable() = capturedSeekTable.isNotEmpty()
            override fun getDurationUs() = capturedDuration
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                if (capturedSeekTable.isEmpty()) return SeekMap.SeekPoints(SeekPoint.START)
                val frame = (timeUs * capturedSampleRate / (capturedBlocks * C.MICROS_PER_SECOND))
                    .toInt().coerceIn(0, capturedTotalFrames - 1)
                val frameTimeUs = frame * capturedBlocks * C.MICROS_PER_SECOND / capturedSampleRate
                val filePos = capturedFirstFrame + (capturedSeekTable[frame].toLong() and 0xFFFFFFFFL)
                return SeekMap.SeekPoints(SeekPoint(frameTimeUs, filePos))
            }
        })
    }
}
