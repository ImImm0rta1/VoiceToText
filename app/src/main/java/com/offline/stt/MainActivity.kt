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

// ---------- Themes ----------
data class Th(val name: String, val dark: Boolean, val bg: Brush, val fg: Color, val card: Color, val a: Color, val b: Color, val onA: Color)

val THEMES = listOf(
    Th("Aurora", true, Brush.verticalGradient(listOf(Color(0xFF05030F), Color(0xFF1E0B45))), Color.White, Color(0x22FFFFFF), Color(0xFF6C4CFF), Color(0xFFE040FB), Color.White),
    Th("Mint", false, Brush.verticalGradient(listOf(Color(0xFFEEF6E8), Color(0xFFD4EDC0))), Color(0xFF1B2A1B), Color.White, Color(0xFFA6EE5C), Color(0xFF3DDC84), Color(0xFF10260F)),
    Th("Peach", false, Brush.verticalGradient(listOf(Color(0xFFFFF7F0), Color(0xFFFAD7BC))), Color(0xFF2B1A10), Color(0xAAFFFFFF), Color(0xFFFF8A2B), Color(0xFFFFB27A), Color.White),
    Th("Mono", false, SolidColor(Color(0xFFEFEFEF)), Color.Black, Color.White, Color(0xFF111111), Color(0xFF444444), Color.White),
    Th("Ocean", false, Brush.linearGradient(listOf(Color(0xFFFBD3DC), Color(0xFFCDE7FB))), Color(0xFF1B2140), Color(0xCCFFFFFF), Color(0xFF5B7CFA), Color(0xFFB07CFF), Color.White),
)

const val HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

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

    init { refresh() }

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

    // ----- live dictation: energy-based segmentation + rolling partials -----
    fun toggleLive() { if (recording) stopLive() else startLive() }

    private fun startLive() {
        if (!ready || busy) return
        recording = true; status = "Listening…"
        val ch = Channel<FloatArray>(Channel.UNLIMITED); chan = ch
        rec.start { ch.trySend(it); level = (rms(it) * 6).coerceIn(0f, 1f) }
        job = viewModelScope.launch(Dispatchers.Default) {
            val seg = ArrayList<FloatArray>()
            var n = 0; var speech = false; var silence = 0; var since = 0
            for (c in ch) {
                seg.add(c); n += c.size
                if (rms(c) > 0.012f) { speech = true; silence = 0 } else if (speech) silence += c.size
                if (!speech) { while (seg.size > 3) seg.removeAt(0); n = seg.sumOf { it.size }; continue }
                since += c.size
                if (silence >= 11200 || n >= 16000 * 25) {
                    append(engine.transcribe(flat(seg))); seg.clear(); n = 0; speech = false; silence = 0; since = 0; partial = ""
                } else if (since >= 24000) { since = 0; partial = engine.transcribe(flat(seg)) }
            }
            if (speech && n > 0) append(engine.transcribe(flat(seg)))
            partial = ""; busy = false; status = ""
        }
    }

    private fun stopLive() {
        recording = false; busy = true; level = 0f; status = "Finishing…"
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
                status = ""
            } catch (e: Exception) { status = "Failed: ${e.message}" } finally { busy = false }
        }
    }
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
fun Chip(label: String, th: Th, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(th.card)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    ) { Text(label, color = th.fg.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 14.sp) }
}

@Composable
fun ModelsPanel(vm: Vm, th: Th, onImport: () -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.models.isEmpty()) Text(
            if (BuildConfig.CAN_DOWNLOAD) "No model yet. Tap one below to download it (one time, stays on your phone)."
            else "No model yet. Download one in your browser, then import it here. This app itself has no internet access.", color = th.fg)
        vm.models.forEach { m ->
            val mb = File(vm.dir, m).length() / 1_000_000
            Text((if (m == vm.selected) "●  " else "○  ") + m.removePrefix("ggml-").removeSuffix(".bin") + "  ·  $mb MB",
                color = th.fg, modifier = Modifier.fillMaxWidth().clickable { vm.select(m) }.padding(vertical = 6.dp))
        }
        Chip("Import model file…", th, onClick = onImport)
        Text(if (BuildConfig.CAN_DOWNLOAD) "Download:" else "Download (opens browser):", color = th.fg.copy(alpha = .7f), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("tiny.en" to "75 MB", "base.en" to "140 MB", "small.en" to "460 MB").forEach { (n, s) ->
                if (BuildConfig.CAN_DOWNLOAD) {
                    if (vm.dlName == n) Chip("$n · ${vm.dlPct}% ✕", th) { vm.cancelDownload() }
                    else Chip("$n · $s", th, enabled = vm.dlName == null) { vm.download(n) }
                } else Chip("$n · $s", th) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${HF}ggml-$n.bin"))) }
            }
        }
        Text("tiny/base for live dictation on most phones; small.en for best accuracy on strong phones.", color = th.fg.copy(alpha = .6f), fontSize = 12.sp)
    }
}

@Composable
fun App(vm: Vm = viewModel()) {
    val th = THEMES[vm.themeIdx]
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var dlg by remember { mutableStateOf(0) } // 1 = models, 2 = themes

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleLive() }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::transcribeFile) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(vm.text.toByteArray()) } }
    }

    val scheme = if (th.dark) darkColorScheme(primary = th.a, onPrimary = th.onA) else lightColorScheme(primary = th.a, onPrimary = th.onA)
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(th.bg)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Offline Dictation", color = th.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Chip("Models", th) { dlg = 1 }; Spacer(Modifier.width(8.dp)); Chip("Theme", th) { dlg = 2 }
                }
                if (vm.models.isEmpty()) {
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(th.card).padding(16.dp)) {
                        ModelsPanel(vm, th) { pickModel.launch(arrayOf("*/*")) }
                    }
                } else {
                    OutlinedTextField(
                        value = vm.text, onValueChange = { vm.text = it },
                        placeholder = { Text("Your transcript appears here. Tap to edit.") },
                        modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = th.fg, unfocusedTextColor = th.fg,
                            focusedContainerColor = th.card, unfocusedContainerColor = th.card,
                            focusedBorderColor = th.a, unfocusedBorderColor = Color.Transparent,
                            focusedPlaceholderColor = th.fg.copy(alpha = .5f), unfocusedPlaceholderColor = th.fg.copy(alpha = .5f)),
                    )
                    if (vm.partial.isNotEmpty()) Text(vm.partial, color = th.fg.copy(alpha = .55f), fontSize = 15.sp)
                    if (vm.status.isNotEmpty()) Text(vm.status, color = th.fg.copy(alpha = .7f), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Copy", th) { clip.setText(AnnotatedString(vm.text)) }
                        Chip("Share", th) {
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.text)
                            ctx.startActivity(Intent.createChooser(i, "Share transcript"))
                        }
                        Chip("Save .txt", th) { save.launch("transcript.txt") }
                        Chip("Clear", th) { vm.text = "" }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                        Chip("Open file", th, enabled = vm.ready && !vm.busy && !vm.recording) { pickAudio.launch(arrayOf("audio/*", "video/*")) }
                        val s by animateFloatAsState(1f + if (vm.recording) vm.level * 0.35f else 0f, label = "orb")
                        Box(
                            Modifier.size(84.dp).scale(s).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(th.a, th.b)))
                                .clickable(enabled = vm.ready && (!vm.busy || vm.recording)) {
                                    if (vm.recording || ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleLive()
                                    else perm.launch(Manifest.permission.RECORD_AUDIO)
                                },
                            contentAlignment = Alignment.Center
                        ) { Text(if (vm.recording) "■" else "🎤", color = th.onA, fontSize = 30.sp) }
                        Text(vm.selected?.removePrefix("ggml-")?.removeSuffix(".bin") ?: "", color = th.fg.copy(alpha = .6f), fontSize = 13.sp, modifier = Modifier.width(72.dp))
                    }
                }
            }
        }
        if (dlg == 1) AlertDialog(onDismissRequest = { dlg = 0 }, confirmButton = { TextButton({ dlg = 0 }) { Text("Done") } },
            title = { Text("Models") }, text = { ModelsPanel(vm, th.copy(fg = MaterialTheme.colorScheme.onSurface, card = MaterialTheme.colorScheme.surfaceVariant)) { pickModel.launch(arrayOf("*/*")) } })
        if (dlg == 2) AlertDialog(onDismissRequest = { dlg = 0 }, confirmButton = { TextButton({ dlg = 0 }) { Text("Done") } },
            title = { Text("Theme") }, text = {
                Column { THEMES.forEachIndexed { i, t ->
                    Text((if (i == vm.themeIdx) "●  " else "○  ") + t.name, modifier = Modifier.fillMaxWidth().clickable { vm.setTheme(i) }.padding(vertical = 10.dp))
                } }
            })
    }
}
