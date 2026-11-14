package com.offline.stt

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

const val HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

data class Note(val id: Long, val text: String)

// ---------- ViewModel ----------
class Vm(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val prefs = ctx.getSharedPreferences("p", 0)
    private val engine = WhisperEngine()
    private val rec = Recorder()
    private var chan: Channel<FloatArray>? = null
    private var job: Job? = null
    val dir = File(ctx.filesDir, "models").apply { mkdirs() }

    var text by mutableStateOf("")
    var partial by mutableStateOf("")
    var status by mutableStateOf("")
    var recording by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var level by mutableStateOf(0f)
    var models by mutableStateOf(listOf<String>())
    var selected by mutableStateOf<String?>(null)
    var ready by mutableStateOf(false)
    var themeIdx by mutableStateOf(prefs.getInt("theme", 0).coerceIn(0, THEMES.size - 1))
    var dlName by mutableStateOf<String?>(null)
    var dlPct by mutableStateOf(0)
    private var dlJob: Job? = null

    private val notesDir = File(ctx.filesDir, "transcripts").apply { mkdirs() }
    var notes by mutableStateOf(listOf<Note>())
    var currentId: Long? = null
    var toast by mutableStateOf("")

    init { refresh(); loadNotes() }

    fun setTheme(i: Int) { themeIdx = i; prefs.edit().putInt("theme", i).apply() }

    fun refresh() {
        models = dir.listFiles { f -> f.name.startsWith("ggml-") && f.name.endsWith(".bin") }?.map { it.name }?.sorted().orEmpty()
        val saved = prefs.getString("model", null)
        val pick = models.firstOrNull { it == saved } ?: models.firstOrNull { it.contains("base.en") } ?: models.firstOrNull()
        if (pick != null) select(pick, force = pick != selected) else { selected = null; ready = false }
    }

    fun select(name: String, force: Boolean = true) {
        if (recording || busy) return
        selected = name; prefs.edit().putString("model", name).apply()
        if (!force && ready) return
        ready = false; busy = true; status = "Loading model…"
        viewModelScope.launch(Dispatchers.Default) {
            val ok = engine.load(File(dir, name).absolutePath)
            ready = ok; busy = false
            status = if (ok) "" else "Couldn't load $name (is it a valid ggml model?)"
        }
    }

    fun import(uri: Uri) {
        busy = true; status = "Importing model…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = ctx.contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                } ?: ""
                if (!name.startsWith("ggml-") || !name.endsWith(".bin")) { status = "Pick a file named like ggml-base.en.bin"; return@launch }
                val tmp = File(dir, "$name.part")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(File(dir, name))
                prefs.edit().putString("model", name).apply()
                status = ""
            } catch (e: Exception) { status = "Import failed: ${e.message}" }
            finally { busy = false; withContext(Dispatchers.Main) { refresh() } }
        }
    }

    // ----- in-app model download (only active in the "online" flavor) -----
    fun download(n: String) {
        if (!BuildConfig.CAN_DOWNLOAD || dlJob != null || recording) return
        dlName = n; dlPct = 0; status = ""
        dlJob = viewModelScope.launch(Dispatchers.IO) {
            val name = "ggml-$n.bin"
            val part = File(dir, "$name.part")
            try {
                val c = java.net.URL(HF + name).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                val have = if (part.exists()) part.length() else 0L
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                c.connect()
                val resumed = c.responseCode == 206
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                val start = if (resumed) have else 0L
                val total = start + c.contentLengthLong
                var done = start
                c.inputStream.use { i ->
                    java.io.FileOutputStream(part, resumed).use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = i.read(buf); if (r < 0) break
                            o.write(buf, 0, r); done += r
                            if (total > 0) dlPct = (done * 100 / total).toInt()
                            ensureActive()
                        }
                    }
                }
                part.renameTo(File(dir, name))
                prefs.edit().putString("model", name).apply()
                status = ""
                withContext(Dispatchers.Main) { refresh() }
            } catch (e: CancellationException) {
                status = "Download paused. Tap again to resume."
            } catch (e: Exception) {
                status = "Download failed: ${e.message}"
            } finally { dlName = null; dlJob = null }
        }
    }

    fun cancelDownload() { dlJob?.cancel() }

    private fun append(t: String) { if (t.isNotEmpty()) text = if (text.isBlank()) t else text.trimEnd() + " " + t }
    private fun flat(l: List<FloatArray>): FloatArray { val r = FloatArray(l.sumOf { it.size }); var p = 0; for (x in l) { x.copyInto(r, p); p += x.size }; return r }

    // ----- library (saved transcripts, private app storage) -----
    fun loadNotes() {
        notes = notesDir.listFiles { f -> f.name.endsWith(".txt") }
            ?.mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { Note(it, f.readText()) } }
            ?.sortedByDescending { it.id }.orEmpty()
    }
    fun save(silent: Boolean = false) {
        if (text.isBlank()) return
        val id = currentId ?: System.currentTimeMillis().also { currentId = it }
        File(notesDir, "$id.txt").writeText(text); loadNotes()
        if (!silent) toast = "Saved to Library"
    }
    fun openNote(n: Note) { if (recording || busy) return; save(silent = true); text = n.text; currentId = n.id }
    fun newNote() { if (recording || busy) return; save(silent = true); text = ""; currentId = null }
    fun delete(n: Note) { File(notesDir, "${n.id}.txt").delete(); if (currentId == n.id) currentId = null; loadNotes() }
    fun deleteModel(name: String) { if (recording || busy) return; File(dir, name).delete(); refresh() }

    // ----- live dictation -----
    // Each sentence is transcribed ONCE, when you pause (or after 8 s of run-on speech), on a background worker.
    // Whisper's encoder always works on a fixed 30 s window, so re-transcribing a growing sentence over and over
    // (the old approach) fell far behind real time. Recording never waits for the model.
    var seconds by mutableStateOf(0)
    var wave by mutableStateOf(FloatArray(40))
    var pending by mutableStateOf(0)
    var pauseMs by mutableStateOf(prefs.getInt("pause", 700))
    private val pc = java.util.concurrent.atomic.AtomicInteger()
    fun setPause(ms: Int) { pauseMs = ms; prefs.edit().putInt("pause", ms).apply() }

    fun toggleLive() { if (recording) stopLive() else startLive() }

    private fun startLive() {
        if (!ready || busy) return
        recording = true; seconds = 0; status = ""
        val ch = Channel<FloatArray>(Channel.UNLIMITED); chan = ch
        var samples = 0L
        rec.start {
            ch.trySend(it)
            level = (rms(it) * 8).coerceIn(0f, 1f)
            samples += it.size; seconds = (samples / 16000).toInt()
            wave = wave.copyOfRange(1, wave.size) + level
        }
        job = viewModelScope.launch(Dispatchers.Default) {
            val jobs = Channel<FloatArray>(Channel.UNLIMITED)
            val worker = launch {
                for (a in jobs) { append(engine.transcribe(a, live = true)); pending = pc.decrementAndGet() }
            }
            fun enqueue(l: List<FloatArray>) { if (l.isNotEmpty()) { pending = pc.incrementAndGet(); jobs.trySend(flat(l)) } }
            var seg = ArrayList<FloatArray>()
            var speech = false; var silence = 0; var voicedN = 0; var noise = 0.004f
            val pauseSamples = pauseMs * 16
            for (c in ch) {
                val r = rms(c)
                val voiced = r > maxOf(0.010f, noise * 2.8f)          // adapts to room noise
                if (!voiced) noise = noise * 0.95f + r * 0.05f
                seg.add(c)
                if (voiced) { speech = true; silence = 0; voicedN += c.size } else if (speech) silence += c.size
                if (!speech) { while (seg.size > 3) seg.removeAt(0); continue }   // keep ~300 ms pre-roll
                val n = seg.sumOf { it.size }
                if (silence >= pauseSamples) {
                    if (voicedN >= 4000) enqueue(seg)                  // ignore clicks and coughs
                    seg = ArrayList(); speech = false; silence = 0; voicedN = 0
                } else if (n >= 16000 * 8) {
                    // run-on speech: cut at the quietest 100 ms in the last 1.5 s so words aren't split
                    val from = maxOf(0, seg.size - 15)
                    var best = from
                    for (i in from until seg.size) if (rms(seg[i]) < rms(seg[best])) best = i
                    enqueue(seg.subList(0, best + 1))
                    seg = ArrayList(seg.subList(best + 1, seg.size))
                }
            }
            if (speech && voicedN >= 4000) enqueue(seg)
            jobs.close(); worker.join()
            busy = false; status = ""; save(silent = true)
        }
    }

    private fun stopLive() {
        recording = false; busy = true; level = 0f; wave = FloatArray(40); status = ""
        rec.stop(); chan?.close()
    }

    // ----- file transcription (2-minute chunks with progress) -----
    fun transcribeFile(uri: Uri) {
        if (!ready || busy || recording) return
        busy = true; status = "Decoding audio…"
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val a = decodeTo16k(ctx, uri)
                val step = 16000 * 120; var i = 0
                while (i < a.size) {
                    status = "Transcribing… ${i * 100L / a.size}%"
                    append(engine.transcribe(a.copyOfRange(i, minOf(a.size, i + step))))
                    i += step
                }
                status = ""; save(silent = true)
            } catch (e: Exception) { status = "Failed: ${e.message}" } finally { busy = false }
        }
    }
}

// ---------- UI lives in Screens.kt / Ui.kt ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}
