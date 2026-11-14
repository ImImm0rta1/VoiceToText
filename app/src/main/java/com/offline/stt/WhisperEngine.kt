package com.offline.stt

class WhisperEngine {
    private var h = 0L
    private external fun nInit(path: String): Long
    private external fun nFree(h: Long)
    private external fun nRun(h: Long, audio: FloatArray, threads: Int, audioCtx: Int): ByteArray

    companion object { init { System.loadLibrary("sttjni") } }

    @Synchronized fun load(path: String): Boolean {
        if (h != 0L) nFree(h)
        h = nInit(path)
        return h != 0L
    }

    @Synchronized fun transcribe(audio: FloatArray, live: Boolean = false): String {
        if (h == 0L || audio.size < 8000) return ""
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        // live: the encoder only needs to look at as much audio as we have, not a fixed 30 s window (much faster)
        val ctx = if (live) (audio.size / 320 + 150).coerceIn(256, 1500) else 0
        val raw = String(nRun(h, audio, threads, ctx), Charsets.UTF_8)
        // drop non-speech tags like [BLANK_AUDIO] or (music)
        return raw.replace(Regex("""\[[^\]]*]|\([^)]*\)"""), "").trim()
    }
}
