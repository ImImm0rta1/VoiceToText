package com.offline.stt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun Title(t: String, th: Th) = Text(t, color = th.fg, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))

@Composable
fun App(vm: Vm = viewModel()) {
    val th = THEMES[vm.themeIdx]
    val ctx = LocalContext.current
    val view = LocalView.current
    var tab by remember { mutableStateOf(0) }
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    SideEffect {
        val w = (view.context as Activity).window
        WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !th.dark; isAppearanceLightNavigationBars = !th.dark }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleLive() }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::transcribeFile) }
    val onMic: () -> Unit = {
        if (vm.recording || ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleLive()
        else perm.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(tab) { vm.save(silent = true) }
    LaunchedEffect(vm.toast) { if (vm.toast.isNotEmpty()) { delay(1600); vm.toast = "" } }

    val scheme = if (th.dark) darkColorScheme(primary = th.a, onPrimary = th.onA) else lightColorScheme(primary = th.a, onPrimary = th.onA)
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(th.bg)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> Dictate(vm, th, imeOpen, goSettings = { tab = 2 }, onMic = onMic, onFile = { pickAudio.launch(arrayOf("audio/*", "video/*")) })
                        1 -> LibraryScreen(vm, th) { vm.openNote(it); tab = 0 }
                        else -> SettingsScreen(vm, th) { pickModel.launch(arrayOf("*/*")) }
                    }
                }
                if (!imeOpen) Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) { NavPill(th, tab) { tab = it } }
            }
            if (vm.toast.isNotEmpty()) Box(
                Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 10.dp)
                    .clip(RoundedCornerShape(50)).background(th.fg).padding(horizontal = 18.dp, vertical = 9.dp)
            ) { Text(vm.toast, color = if (th.dark) Color.Black else Color.White, fontSize = 14.sp) }
        }
    }
}

// ---------- Dictate ----------
@Composable
fun Dictate(vm: Vm, th: Th, imeOpen: Boolean, goSettings: () -> Unit, onMic: () -> Unit, onFile: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(vm.text.toByteArray()) } }
    }
    val hasText = vm.text.isNotEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Dictate", color = th.fg, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(th.card).border(1.dp, th.border, RoundedCornerShape(50))
                    .clickable(onClick = goSettings).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (vm.ready) Color(0xFF34C759) else Color(0xFFFF9F0A)))
                Spacer(Modifier.width(8.dp))
                Text(vm.selected?.removePrefix("ggml-")?.removeSuffix(".bin") ?: "No model", color = th.fg, fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            IconBtn(Ic.Palette, th, 40.dp) { vm.setTheme((vm.themeIdx + 1) % THEMES.size) }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) { if (hasText) Editor(vm, th) else Hero(vm, th, goSettings) }

        if (hasText) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Copy, th) { clip.setText(AnnotatedString(vm.text)); vm.toast = "Copied" }
            IconBtn(Ic.Share, th) {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.text)
                ctx.startActivity(Intent.createChooser(i, "Share transcript"))
            }
            IconBtn(Ic.Export, th) { export.launch("transcript.txt") }
            Spacer(Modifier.weight(1f))
            IconBtn(Ic.Add, th, enabled = !vm.recording && !vm.busy) { vm.newNote() }
        }

        if (!imeOpen) Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            Labeled(Ic.File, "Open file", th, enabled = vm.ready && !vm.busy && !vm.recording, onClick = onFile)
            val s by animateFloatAsState(1f + if (vm.recording) vm.level * 0.15f else 0f, label = "mic")
            Box(
                Modifier.size(80.dp).scale(s).alpha(if (vm.ready) 1f else 0.4f).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(th.a, th.b)))
                    .clickable(enabled = vm.ready && (!vm.busy || vm.recording), onClick = onMic),
                contentAlignment = Alignment.Center,
            ) { Icon(if (vm.recording) Ic.Stop else Ic.Mic, null, tint = th.onA, modifier = Modifier.size(34.dp)) }
            Labeled(Ic.Save, "Save", th, enabled = vm.text.isNotBlank(), onClick = { vm.save() })
        }
    }
}

@Composable
fun Labeled(icon: ImageVector, label: String, th: Th, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        IconBtn(icon, th, 50.dp, enabled = enabled, onClick = onClick)
        Text(label, color = th.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Hero(vm: Vm, th: Th, goSettings: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Orb(th, 260.dp, vm.level, vm.recording)
        Spacer(Modifier.height(8.dp))
        when {
            vm.models.isEmpty() -> {
                Text("Add a speech model", color = th.fg, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("Pick one once. It stays on your phone and works with no internet.", color = th.sub, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(16.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(th.a, th.b))).clickable(onClick = goSettings).padding(horizontal = 26.dp, vertical = 13.dp)) {
                    Text("Choose a model", color = th.onA, fontWeight = FontWeight.Medium)
                }
            }
            vm.recording -> {
                Text(mmss(vm.seconds), color = th.fg, fontSize = 34.sp, fontWeight = FontWeight.Light)
                Text(if (vm.pending > 0) "Transcribing…" else "Listening…", color = th.sub, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                Wave(th, vm.wave, Modifier.fillMaxWidth().height(48.dp))
            }
            vm.busy -> Text(vm.status.ifEmpty { "Working…" }, color = th.sub, fontSize = 16.sp)
            else -> {
                Text("00:00", color = th.sub, fontSize = 16.sp)
                Text("Tap to start", color = th.fg, fontSize = 30.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(6.dp))
                Text(if (vm.status.isNotEmpty()) vm.status else "Speak naturally. Everything stays on your phone.", color = th.sub, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
    }
}

@Composable
fun Editor(vm: Vm, th: Th) {
    Column(Modifier.fillMaxSize().glass(th, 26.dp).padding(18.dp)) {
        if (vm.recording || vm.busy) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.recording) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                Spacer(Modifier.width(8.dp))
                Text(mmss(vm.seconds), color = th.fg, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Wave(th, vm.wave, Modifier.weight(1f).height(28.dp))
            } else Text(vm.status.ifEmpty { "Finishing…" }, color = th.sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (vm.pending > 0) { Spacer(Modifier.width(10.dp)); Text("Transcribing…", color = th.a, fontSize = 13.sp) }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(vm.text.length) { if (vm.recording || vm.busy) scroll.animateScrollTo(scroll.maxValue) }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            BasicTextField(
                value = vm.text, onValueChange = { vm.text = it },
                textStyle = TextStyle(color = th.fg, fontSize = 18.sp, lineHeight = 28.sp),
                cursorBrush = SolidColor(th.a), modifier = Modifier.fillMaxWidth(),
            )
        }
        val words = vm.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        Text("$words words", color = th.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------- Library ----------
@Composable
fun LibraryScreen(vm: Vm, th: Th, onOpen: (Note) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Library", th)
        Row(Modifier.fillMaxWidth().glass(th, 30.dp).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Search, null, tint = th.sub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                textStyle = TextStyle(color = th.fg, fontSize = 16.sp), cursorBrush = SolidColor(th.a),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> if (q.isEmpty()) Text("Search transcripts…", color = th.sub, fontSize = 16.sp); inner() },
            )
        }
        val list = vm.notes.filter { q.isBlank() || it.text.contains(q, ignoreCase = true) }
        if (list.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (vm.notes.isEmpty()) "Nothing saved yet.\nDictations are saved here automatically." else "No matches", color = th.sub, textAlign = TextAlign.Center, fontSize = 15.sp)
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            list.groupBy {
                when { DateUtils.isToday(it.id) -> "Today"; DateUtils.isToday(it.id + DateUtils.DAY_IN_MILLIS) -> "Yesterday"; else -> "Earlier" }
            }.forEach { (group, rows) ->
                item { Text(group, color = th.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                items(rows, key = { it.id }) { n ->
                    val words = n.text.trim().split(Regex("\\s+"))
                    Row(Modifier.fillMaxWidth().glass(th, 22.dp).clickable { onOpen(n) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(th.a, th.b))), contentAlignment = Alignment.Center) {
                            Icon(Ic.Mic, null, tint = th.onA, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(words.take(7).joinToString(" "), color = th.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                DateUtils.formatDateTime(ctx, n.id, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL) + "  ·  ${words.size} words",
                                color = th.sub, fontSize = 12.sp, maxLines = 1,
                            )
                        }
                        IconBtn(Ic.Delete, th, 38.dp) { vm.delete(n); vm.toast = "Deleted" }
                    }
                }
            }
        }
    }
}

// ---------- Settings ----------
@Composable
fun SettingsScreen(vm: Vm, th: Th, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Title("Settings", th)
        Row(Modifier.fillMaxWidth().glass(th, 22.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(th.a, th.b))), contentAlignment = Alignment.Center) {
                Icon(Ic.Lock, null, tint = th.onA, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Private by design", color = th.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (BuildConfig.CAN_DOWNLOAD) "This build uses the internet only to download models. Your voice and text are never uploaded."
                    else "This build has no internet permission. Your voice and text never leave this phone.",
                    color = th.sub, fontSize = 13.sp,
                )
            }
        }

        Text("Speech models", color = th.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        ModelRow(vm, th, "tiny.en", "Fastest, great for live dictation", "75 MB")
        ModelRow(vm, th, "base.en", "Balanced, recommended", "140 MB")
        ModelRow(vm, th, "small.en", "Most accurate, needs a strong phone", "460 MB")
        val known = listOf("tiny.en", "base.en", "small.en")
        vm.models.filter { m -> known.none { "ggml-$it.bin" == m } }.forEach { m ->
            ModelRow(vm, th, m.removePrefix("ggml-").removeSuffix(".bin"), "Custom model", "${File(vm.dir, m).length() / 1_000_000} MB")
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(th.card).border(1.dp, th.border, RoundedCornerShape(50)).clickable(onClick = onImport).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.File, null, tint = th.fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text("Import a model file…", color = th.fg, fontSize = 14.sp)
        }

        Text("Speech pause", color = th.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text("How long you pause before a sentence is turned into text.", color = th.sub, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Quick" to 450, "Normal" to 700, "Relaxed" to 1100).forEach { (label, ms) ->
                val sel = vm.pauseMs == ms
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .then(if (sel) Modifier.background(Brush.linearGradient(listOf(th.a, th.b))) else Modifier.background(th.card).border(1.dp, th.border, RoundedCornerShape(50)))
                        .clickable { vm.setPause(ms) }.padding(horizontal = 18.dp, vertical = 10.dp)
                ) { Text(label, color = if (sel) th.onA else th.fg, fontSize = 14.sp) }
            }
        }

        Text("Theme", color = th.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            THEMES.forEachIndexed { i, t ->
                val sel = i == vm.themeIdx
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.setTheme(i) }) {
                    Box(
                        Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(t.bg)
                            .border(if (sel) 3.dp else 1.dp, if (sel) th.a else th.border, RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Orb(t, 76.dp, 0f, false) }
                    Text(t.name, color = th.fg, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun ModelRow(vm: Vm, th: Th, n: String, blurb: String, size: String) {
    val ctx = LocalContext.current
    val file = "ggml-$n.bin"
    val installed = file in vm.models
    val selected = vm.selected == file
    val downloading = BuildConfig.CAN_DOWNLOAD && vm.dlName == n
    Column(Modifier.fillMaxWidth().glass(th, 22.dp).clickable(enabled = installed) { vm.select(file) }.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n, color = th.fg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("$blurb · $size", color = th.sub, fontSize = 13.sp)
            }
            when {
                installed -> {
                    IconBtn(Ic.Delete, th, 38.dp) { vm.deleteModel(file) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .then(if (selected) Modifier.background(Brush.linearGradient(listOf(th.a, th.b))) else Modifier.border(2.dp, th.border, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(Ic.Check, null, tint = th.onA, modifier = Modifier.size(18.dp)) }
                }
                downloading -> {
                    Text("${vm.dlPct}%", color = th.a, fontSize = 14.sp); Spacer(Modifier.width(10.dp))
                    IconBtn(Ic.Close, th, 38.dp) { vm.cancelDownload() }
                }
                else -> IconBtn(Ic.Download, th, 44.dp, enabled = !(BuildConfig.CAN_DOWNLOAD && vm.dlName != null), filled = true) {
                    if (BuildConfig.CAN_DOWNLOAD) vm.download(n)
                    else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${HF}ggml-$n.bin")))
                }
            }
        }
        if (downloading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { vm.dlPct / 100f }, modifier = Modifier.fillMaxWidth(), color = th.a, trackColor = th.border)
        }
    }
}
