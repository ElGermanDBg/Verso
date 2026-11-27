package com.verso.lyrics.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Envolvente precalculada de energía rítmica (graves / bombos) para un tramo de audio.
 * Permite consultar el nivel de pulso normalizado [0f..1f] en cualquier instante [positionMs]
 * con interpolación lineal entre cuadros, garantizando sincronía idéntica entre preview y MP4.
 */
class BeatEnvelope(
    val startMs: Long,
    val frameStepMs: Long = 33L,
    val levels: FloatArray
) {
    fun pulseAt(positionMs: Long): Float {
        if (levels.isEmpty() || frameStepMs <= 0L) return 0f
        val relMs = (positionMs - startMs).coerceAtLeast(0L)
        val exactIndex = relMs.toFloat() / frameStepMs.toFloat()
        val i0 = exactIndex.toInt().coerceIn(0, levels.lastIndex)
        val i1 = (i0 + 1).coerceAtMost(levels.lastIndex)
        val frac = (exactIndex - i0).coerceIn(0f, 1f)
        return (levels[i0] * (1f - frac) + levels[i1] * frac).coerceIn(0f, 1f)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BeatEnvelope) return false
        return startMs == other.startMs &&
            frameStepMs == other.frameStepMs &&
            levels.contentEquals(other.levels)
    }

    override fun hashCode(): Int {
        var result = startMs.hashCode()
        result = 31 * result + frameStepMs.hashCode()
        result = 31 * result + levels.contentHashCode()
        return result
    }
}

object AudioBeatAnalyzer {

    private const val FRAME_STEP_MS = 33L
    private const val BASS_CUTOFF_HZ = 160.0

    /**
     * Decodifica el tramo [startMs, startMs + durationMs] a PCM, aísla los graves con un filtro
     * pasa-bajos de 1 polo (~160 Hz), calcula RMS por ventana de 33 ms, detecta picos locales
     * sobre media móvil y aplica ataque rápido + decaimiento suave (0.78f).
     */
    suspend fun analyzeClip(
        context: Context,
        uri: Uri,
        startMs: Long,
        durationMs: Long
    ): BeatEnvelope? = withContext(Dispatchers.Default) {
        if (durationMs <= 0L) return@withContext null

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return@withContext null

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            var sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            var channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

            extractor.selectTrack(audioTrackIndex)
            val startUs = startMs.coerceAtLeast(0L) * 1000L
            val endUs = startUs + durationMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val frameCount = ceil(durationMs.toDouble() / FRAME_STEP_MS.toDouble()).toInt().coerceAtLeast(1)
            val sumSqPerFrame = DoubleArray(frameCount)
            val countPerFrame = IntArray(frameCount)

            // Coeficiente del filtro pasa-bajos de 1 polo (~160 Hz)
            var lpAlpha = computeLowPassAlpha(sampleRate)
            var lpState = 0.0

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var currentSampleIndex = 0L
            var anchorUs: Long? = null

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            val sampleTimeUs = extractor.sampleTime
                            if (sampleSize < 0 || sampleTimeUs > endUs + 250_000L) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            lpAlpha = computeLowPassAlpha(sampleRate)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }

                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val ptsUs = bufferInfo.presentationTimeUs
                            if (anchorUs == null) {
                                anchorUs = ptsUs
                                currentSampleIndex = 0L
                            }
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val totalShorts = shortBuf.remaining()
                            val ch = channelCount.coerceAtLeast(1)
                            val numFrames = totalShorts / ch
                            val baseUs = ptsUs

                            for (f in 0 until numFrames) {
                                var mono = 0.0
                                for (c in 0 until ch) {
                                    mono += shortBuf.get().toDouble() / 32768.0
                                }
                                mono /= ch.toDouble()

                                // Filtro pasa-bajos de 1 polo para aislar graves
                                lpState += lpAlpha * (mono - lpState)

                                val sampleTimeUs = baseUs + (f * 1_000_000L) / sampleRate.coerceAtLeast(1)
                                if (sampleTimeUs in startUs until endUs) {
                                    val relMs = (sampleTimeUs - startUs) / 1000L
                                    val frameIdx = (relMs / FRAME_STEP_MS).toInt()
                                    if (frameIdx in 0 until frameCount) {
                                        sumSqPerFrame[frameIdx] += lpState * lpState
                                        countPerFrame[frameIdx]++
                                    }
                                }
                                currentSampleIndex++
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val rms = FloatArray(frameCount)
            for (i in 0 until frameCount) {
                val cnt = countPerFrame[i]
                rms[i] = if (cnt > 0) sqrt(sumSqPerFrame[i] / cnt.toDouble()).toFloat() else 0f
            }

            val levels = computeBeatLevels(rms)
            BeatEnvelope(startMs = startMs, frameStepMs = FRAME_STEP_MS, levels = levels)
        } catch (_: Exception) {
            null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun computeLowPassAlpha(sampleRate: Int): Double {
        val sr = sampleRate.coerceAtLeast(8000).toDouble()
        val dt = 1.0 / sr
        val rc = 1.0 / (2.0 * PI * BASS_CUTOFF_HZ)
        return dt / (rc + dt)
    }

    internal fun computeBeatLevels(rms: FloatArray): FloatArray {
        if (rms.isEmpty()) return FloatArray(0)
        val n = rms.size
        val windowRadius = 10 // ~660 ms total moving average
        val onset = FloatArray(n)
        var maxOnset = 0f

        for (i in 0 until n) {
            val from = max(0, i - windowRadius)
            val to = min(n - 1, i + windowRadius)
            var sum = 0f
            for (j in from..to) {
                sum += rms[j]
            }
            val avg = sum / (to - from + 1).toFloat()
            // Resalta picos de graves por encima de la energía promedio local
            val excess = (rms[i] - avg * 1.08f).coerceAtLeast(0f)
            // Combina energía absoluta suave + exceso de pico para que también pulse en pasajes densos
            val raw = excess * 1.6f + rms[i] * 0.25f
            onset[i] = raw
            if (raw > maxOnset) maxOnset = raw
        }

        if (maxOnset <= 1e-6f) return FloatArray(n) { 0f }

        // Normalización robusta (percentil ~98 para no aplastar el resto por un único pico extremo)
        val sorted = onset.sortedArray()
        val p98 = sorted[((n - 1) * 0.98f).toInt().coerceIn(0, n - 1)]
        val normDenom = max(p98, maxOnset * 0.65f).coerceAtLeast(1e-6f)

        val smoothed = FloatArray(n)
        var env = 0f
        for (i in 0 until n) {
            val target = (onset[i] / normDenom).coerceIn(0f, 1f)
            // Ataque rápido (sube de inmediato al golpe), decaimiento suave (0.78f)
            env = if (target > env) {
                target
            } else {
                max(target, env * 0.78f)
            }
            smoothed[i] = env
        }
        return smoothed
    }
}
