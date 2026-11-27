package com.verso.lyrics.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.FileProvider
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.StoryConfig
import com.verso.lyrics.render.LyricFrameRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ExportResult(
    val galleryUri: Uri?,
    val shareUri: Uri,
    val file: File
)

/**
 * Exporta una historia musical (foto + letras sincronizadas + fragmento de audio) a MP4
 * utilizando codificación por hardware nativa (MediaCodec + MediaExtractor + MediaMuxer).
 */
class VideoExporter(private val context: Context) {

    suspend fun exportStory(
        config: StoryConfig,
        lines: List<LyricLine>,
        onProgress: (Float) -> Unit
    ): ExportResult = withContext(Dispatchers.Default) {
        val track = config.track ?: error("Selecciona una canción primero")
        val photoBitmap = config.photoUri?.let { loadBitmap(it, 1080, 1920) }
        val albumArtBitmap = loadBitmap(track.albumArtUri, 256, 256)

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(exportsDir, "verso_${System.currentTimeMillis()}.mp4")

        val clipStartMs = config.clipStartMs.coerceAtLeast(0L)
        val clipDurationMs = config.clipDurationMs.coerceIn(3_000L, 60_000L)

        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            val (width, height, videoEncoder, inputSurface) = createVideoEncoder()
            val activeMuxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer

            val audioSession = prepareAudioPipeline(track.contentUri, clipStartMs)
            val videoTrackHolder = IntArray(1) { -1 }
            val audioTrackHolder = IntArray(1) { -1 }

            // 1) Codificar todos los frames de video (0% .. 65%)
            val totalFrames = ((clipDurationMs / 1000.0) * FPS).toInt().coerceAtLeast(1)
            val frameDurationUs = 1_000_000L / FPS
            val videoBufferInfo = MediaCodec.BufferInfo()
            var encodedVideoFrames = 0L

            val pendingAudioSamples = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
            val pendingVideoSamples = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()

            fun tryStartMuxerAndFlushPending() {
                if (muxerStarted) return
                val videoReady = videoTrackHolder[0] >= 0
                val audioReady = audioSession == null || audioTrackHolder[0] >= 0
                if (videoReady && audioReady) {
                    activeMuxer.start()
                    muxerStarted = true
                    for ((bytes, info) in pendingVideoSamples) {
                        activeMuxer.writeSampleData(videoTrackHolder[0], ByteBuffer.wrap(bytes), info)
                    }
                    pendingVideoSamples.clear()
                    for ((bytes, info) in pendingAudioSamples) {
                        activeMuxer.writeSampleData(audioTrackHolder[0], ByteBuffer.wrap(bytes), info)
                    }
                    pendingAudioSamples.clear()
                }
            }

            fun drainVideoBuffered(endOfStream: Boolean) {
                if (endOfStream) videoEncoder.signalEndOfInputStream()
                while (true) {
                    val outIdx = videoEncoder.dequeueOutputBuffer(videoBufferInfo, if (endOfStream) 10_000L else 0L)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (videoTrackHolder[0] < 0) {
                                videoTrackHolder[0] = activeMuxer.addTrack(videoEncoder.outputFormat)
                                tryStartMuxerAndFlushPending()
                            }
                        }
                        outIdx >= 0 -> {
                            val encoded = videoEncoder.getOutputBuffer(outIdx)
                            if (encoded != null && (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && videoBufferInfo.size > 0) {
                                val normalizedPtsUs = encodedVideoFrames * frameDurationUs
                                encodedVideoFrames++
                                encoded.position(videoBufferInfo.offset)
                                encoded.limit(videoBufferInfo.offset + videoBufferInfo.size)
                                if (muxerStarted) {
                                    val normalizedInfo = MediaCodec.BufferInfo().apply {
                                        set(videoBufferInfo.offset, videoBufferInfo.size, normalizedPtsUs, videoBufferInfo.flags)
                                    }
                                    activeMuxer.writeSampleData(videoTrackHolder[0], encoded, normalizedInfo)
                                } else {
                                    val copy = ByteArray(videoBufferInfo.size)
                                    encoded.get(copy)
                                    val infoCopy = MediaCodec.BufferInfo().apply {
                                        set(0, videoBufferInfo.size, normalizedPtsUs, videoBufferInfo.flags)
                                    }
                                    pendingVideoSamples.add(copy to infoCopy)
                                }
                            }
                            videoEncoder.releaseOutputBuffer(outIdx, false)
                            if ((videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }
            }

            try {
                for (frameIdx in 0 until totalFrames) {
                    ensureActive()
                    val relMs = (frameIdx * 1000L) / FPS
                    val trackPosMs = clipStartMs + relMs

                    val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        inputSurface.lockHardwareCanvas()
                    } else {
                        inputSurface.lockCanvas(null)
                    }
                    try {
                        LyricFrameRenderer.drawFrame(
                            canvas = canvas,
                            width = width,
                            height = height,
                            config = config,
                            lines = lines,
                            positionMs = trackPosMs,
                            photoBitmap = photoBitmap,
                            albumArtBitmap = albumArtBitmap
                        )
                    } finally {
                        inputSurface.unlockCanvasAndPost(canvas)
                    }

                    // Para que el encoderSurface registre el timestamp correcto en API 26+,
                    // MediaCodec surface toma el tiempo del sistema a menos que usemos EGL,
                    // pero en `Surface.lockCanvas` los frames se escriben secuencialmente.
                    // Ajustamos el drenado por frame:
                    drainVideoBuffered(false)
                    onProgress((frameIdx + 1).toFloat() / totalFrames * 0.65f)
                }
                drainVideoBuffered(true)
            } finally {
                runCatching { videoEncoder.stop() }
                runCatching { videoEncoder.release() }
                runCatching { inputSurface.release() }
            }

            // 2) Transcodificar tramo de audio a AAC y multiplexar (65% .. 95%)
            if (audioSession != null) {
                transcodeAudioToMuxer(
                    session = audioSession,
                    clipStartMs = clipStartMs,
                    clipDurationMs = clipDurationMs,
                    muxer = muxer,
                    audioTrackHolder = audioTrackHolder,
                    isMuxerStarted = { muxerStarted },
                    onFormatReady = { tryStartMuxerAndFlushPending() },
                    onPendingSample = { bytes, info -> pendingAudioSamples.add(bytes to info) },
                    onProgress = { p -> onProgress(0.65f + p * 0.30f) },
                    checkActive = { ensureActive() }
                )
            } else {
                tryStartMuxerAndFlushPending()
            }

            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
            muxer = null

            onProgress(0.97f)
            val galleryUri = saveVideoToGallery(outFile)
            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            onProgress(1f)
            ExportResult(galleryUri = galleryUri, shareUri = shareUri, file = outFile)
        } catch (t: Throwable) {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { outFile.delete() }
            throw t
        } finally {
            photoBitmap?.recycle()
            albumArtBitmap?.recycle()
        }
    }

    private data class VideoEncoderBundle(
        val width: Int,
        val height: Int,
        val encoder: MediaCodec,
        val surface: Surface
    )

    private fun createVideoEncoder(): VideoEncoderBundle {
        val resolutions = listOf(1080 to 1920, 720 to 1280)
        var lastError: Throwable? = null
        for ((w, h) in resolutions) {
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (w >= 1080) 8_000_000 else 4_500_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = encoder.createInputSurface()
                encoder.start()
                return VideoEncoderBundle(w, h, encoder, surface)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw IllegalStateException("No se pudo inicializar el codificador de video H.264", lastError)
    }

    private data class AudioSession(
        val extractor: MediaExtractor,
        val trackIndex: Int,
        val inputFormat: MediaFormat,
        val decoder: MediaCodec
    )

    private fun prepareAudioPipeline(uri: Uri, clipStartMs: Long): AudioSession? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var audioIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioIdx = i
                    format = fmt
                    break
                }
            }
            if (audioIdx < 0 || format == null) {
                extractor.release()
                return null
            }
            extractor.selectTrack(audioIdx)
            extractor.seekTo(clipStartMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            AudioSession(extractor, audioIdx, format, decoder)
        } catch (_: Throwable) {
            runCatching { extractor.release() }
            null
        }
    }

    private fun transcodeAudioToMuxer(
        session: AudioSession,
        clipStartMs: Long,
        clipDurationMs: Long,
        muxer: MediaMuxer,
        audioTrackHolder: IntArray,
        isMuxerStarted: () -> Boolean,
        onFormatReady: () -> Unit,
        onPendingSample: (ByteArray, MediaCodec.BufferInfo) -> Unit,
        onProgress: (Float) -> Unit,
        checkActive: () -> Unit
    ) {
        val sampleRate = session.inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val rawChannels = session.inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channels = rawChannels.coerceIn(1, 2)

        val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val clipStartUs = clipStartMs * 1000L
        val clipEndUs = (clipStartMs + clipDurationMs) * 1000L
        val fadeOutUs = 600_000L
        val fadeStartUs = (clipEndUs - fadeOutUs).coerceAtLeast(clipStartUs)

        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var encoderDone = false
        var pcmPtsUs = 0L

        try {
            while (!encoderDone) {
                checkActive()

                if (!extractorDone) {
                    val inIdx = session.decoder.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val buf = session.decoder.getInputBuffer(inIdx)!!
                        val sampleSize = session.extractor.readSampleData(buf, 0)
                        val sampleTime = session.extractor.sampleTime
                        if (sampleSize < 0 || sampleTime > clipEndUs + 200_000L) {
                            session.decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            session.decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, 0)
                            session.extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outIdx = session.decoder.dequeueOutputBuffer(decInfo, 5_000L)
                    if (outIdx >= 0) {
                        val decBuf = session.decoder.getOutputBuffer(outIdx)
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        if (decBuf != null && decInfo.size > 0 && decInfo.presentationTimeUs >= clipStartUs - 50_000L) {
                            val relPtsUs = (decInfo.presentationTimeUs - clipStartUs).coerceAtLeast(pcmPtsUs)
                            val pcmBytes = ByteArray(decInfo.size)
                            decBuf.position(decInfo.offset)
                            decBuf.get(pcmBytes)

                            // Fade-out suave en los últimos 600ms
                            if (decInfo.presentationTimeUs >= fadeStartUs) {
                                val gain = ((clipEndUs - decInfo.presentationTimeUs).toFloat() / fadeOutUs).coerceIn(0f, 1f)
                                applyGainPcm16(pcmBytes, gain)
                            }

                            var offset = 0
                            while (offset < pcmBytes.size) {
                                val encInIdx = encoder.dequeueInputBuffer(10_000L)
                                if (encInIdx >= 0) {
                                    val encBuf = encoder.getInputBuffer(encInIdx)!!
                                    encBuf.clear()
                                    val chunk = minOf(encBuf.remaining(), pcmBytes.size - offset)
                                    encBuf.put(pcmBytes, offset, chunk)
                                    val chunkDurationUs = (chunk.toLong() * 1_000_000L) / (sampleRate * channels * 2L)
                                    encoder.queueInputBuffer(encInIdx, 0, chunk, relPtsUs, 0)
                                    pcmPtsUs = relPtsUs + chunkDurationUs
                                    offset += chunk
                                }
                            }
                            onProgress((relPtsUs.toFloat() / (clipDurationMs * 1000f)).coerceIn(0f, 1f))
                        }

                        session.decoder.releaseOutputBuffer(outIdx, false)
                        if (isEos || decInfo.presentationTimeUs >= clipEndUs) {
                            decoderDone = true
                            val encInIdx = encoder.dequeueInputBuffer(10_000L)
                            if (encInIdx >= 0) {
                                encoder.queueInputBuffer(encInIdx, 0, 0, pcmPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        }
                    }
                }

                // Drenar encoder AAC
                while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 2_000L)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (audioTrackHolder[0] < 0) {
                                audioTrackHolder[0] = muxer.addTrack(encoder.outputFormat)
                                onFormatReady()
                            }
                        }
                        encOutIdx >= 0 -> {
                            val encBuf = encoder.getOutputBuffer(encOutIdx)
                            if (encBuf != null && (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0) {
                                encBuf.position(encInfo.offset)
                                encBuf.limit(encInfo.offset + encInfo.size)
                                if (isMuxerStarted()) {
                                    muxer.writeSampleData(audioTrackHolder[0], encBuf, encInfo)
                                } else {
                                    val copy = ByteArray(encInfo.size)
                                    encBuf.get(copy)
                                    val infoCopy = MediaCodec.BufferInfo().apply {
                                        set(0, encInfo.size, encInfo.presentationTimeUs, encInfo.flags)
                                    }
                                    onPendingSample(copy, infoCopy)
                                }
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { session.decoder.stop() }
            runCatching { session.decoder.release() }
            runCatching { session.extractor.release() }
        }
    }

    private fun applyGainPcm16(pcmBytes: ByteArray, gain: Float) {
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = pcmBytes.size / 2
        for (i in 0 until count) {
            val sample = bb.getShort(i * 2)
            val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            bb.putShort(i * 2, scaled)
        }
    }

    private fun saveVideoToGallery(sourceFile: File): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Verso")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { input -> input.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        uri
    }.getOrNull()

    private fun loadBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (w > reqWidth || h > reqHeight) {
                    val ratio = maxOf(w.toFloat() / reqWidth, h.toFloat() / reqHeight)
                    decoder.setTargetSize((w / ratio).toInt().coerceAtLeast(1), (h / ratio).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > reqWidth * 2 || opts.outHeight / sample > reqHeight * 2) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        }
    }.getOrNull()

    private companion object {
        const val FPS = 30
    }
}
