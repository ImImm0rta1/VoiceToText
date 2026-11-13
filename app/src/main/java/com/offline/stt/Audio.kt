package com.offline.stt

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

fun rms(a: FloatArray): Float {
    var s = 0.0
    for (x in a) s += x * x
    return sqrt(s / a.size).toFloat()
}

/** Mic capture, 16 kHz mono, 100 ms chunks. */
class Recorder {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(onChunk: (FloatArray) -> Unit) {
        running = true
        thread = Thread {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 32000))
            r.startRecording()
            val b = ShortArray(1600)
            while (running) {
                val n = r.read(b, 0, b.size)
                if (n > 0) onChunk(FloatArray(n) { b[it] / 32768f })
            }
            r.stop(); r.release()
        }.also { it.start() }
    }

    fun stop() { running = false; thread?.join(); thread = null }
}

/** Decodes any audio/video file Android can play into 16 kHz mono floats. */
fun decodeTo16k(ctx: Context, uri: Uri): FloatArray {
    val ex = MediaExtractor()
    ex.setDataSource(ctx, uri, null)
    val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
    ex.selectTrack(ti)
    val inFmt = ex.getTrackFormat(ti)
    val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(inFmt, null, null, 0); codec.start()
    var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val info = MediaCodec.BufferInfo()
    val parts = ArrayList<FloatArray>(); var total = 0
    var inDone = false; var outDone = false
    while (!outDone) {
        if (!inDone) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val b = codec.getInputBuffer(i)!!
                val n = ex.readSampleData(b, 0)
                if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
            }
        }
        val o = codec.dequeueOutputBuffer(info, 10_000)
        if (o >= 0) {
            val b = codec.getOutputBuffer(o)!!
            b.position(info.offset); b.limit(info.offset + info.size)
            val s = b.order(ByteOrder.nativeOrder()).asShortBuffer()
            val c = ch
            val frames = s.remaining() / c
            val mono = FloatArray(frames) { f -> var acc = 0f; for (k in 0 until c) acc += s.get(f * c + k); acc / c / 32768f }
            parts.add(mono); total += frames
            codec.releaseOutputBuffer(o, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
        } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = codec.outputFormat
            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
    }
    codec.stop(); codec.release(); ex.release()
    val all = FloatArray(total); var p = 0
    for (x in parts) { x.copyInto(all, p); p += x.size }
    return resample(all, rate)
}

private fun resample(a: FloatArray, from: Int): FloatArray {
    if (from == 16000 || a.isEmpty()) return a
    val n = (a.size * 16000L / from).toInt()
    val k = from / 16000.0
    return FloatArray(n) { i ->
        val p = i * k; val j = p.toInt(); val f = (p - j).toFloat()
        val x = a[minOf(j, a.size - 1)]; val y = a[minOf(j + 1, a.size - 1)]
        x + (y - x) * f
    }
}
