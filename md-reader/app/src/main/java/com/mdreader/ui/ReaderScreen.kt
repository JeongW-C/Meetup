package com.mdreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mdreader.ReaderViewModel
import com.mdreader.data.ReaderPrefs
import com.mdreader.data.RecentFile
import com.mdreader.playback.PlaybackState
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    onOpenFile: () -> Unit,
    onOpenDrive: () -> Unit,
    onOpenTtsSettings: () -> Unit,
) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val recent by vm.recentFiles.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.clearMessage()
    }

    val document = state.document
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(document?.title ?: "MD 리더", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (document != null) {
                        IconButton(onClick = vm::closeDocument) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "목록으로")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenFile) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "파일 열기")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "읽기 설정")
                    }
                },
            )
        },
        bottomBar = {
            if (document != null) {
                PlayerBar(
                    state = state,
                    onToggle = vm::togglePlay,
                    onPrevious = vm::previous,
                    onNext = vm::next,
                    onStop = vm::stop,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                document == null -> HomeContent(
                    recent = recent,
                    ttsProblem = state.ttsProblem,
                    onOpenFile = onOpenFile,
                    onOpenDrive = onOpenDrive,
                    onOpenRecent = vm::openRecent,
                    onRemoveRecent = vm::removeRecent,
                    onOpenTtsSettings = onOpenTtsSettings,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    // Without this, pressing play on a phone with no usable voice would do nothing visible.
                    state.ttsProblem?.let { problem ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            TtsProblemCard(problem, onOpenTtsSettings)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        SegmentList(
                            segments = document.segments,
                            current = state.index,
                            documentKey = state.documentKey,
                            fontSize = prefs.fontSize,
                            onSeek = vm::seek,
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsContent(
                state = state,
                prefs = prefs,
                onSpeed = vm::setSpeed,
                onPitch = vm::setPitch,
                onFontSize = vm::setFontSize,
                onVoice = vm::setVoice,
                onSleep = vm::setSleepTimer,
                onOpenTtsSettings = onOpenTtsSettings,
            )
        }
    }
}

@Composable
private fun HomeContent(
    recent: List<RecentFile>,
    ttsProblem: String?,
    onOpenFile: () -> Unit,
    onOpenDrive: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onRemoveRecent: (RecentFile) -> Unit,
    onOpenTtsSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    ".md 또는 .txt 파일을 열면 음성으로 읽어 드립니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenFile) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("파일 열기")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenDrive) {
                    Icon(Icons.Filled.Cloud, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Google Drive에서 열기")
                }
            }
        }
        if (ttsProblem != null) {
            item { TtsProblemCard(ttsProblem, onOpenTtsSettings) }
        }
        if (recent.isNotEmpty()) {
            item {
                Text(
                    "최근 파일",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(recent, key = { it.uri }) { file ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecent(file) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        file.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { onRemoveRecent(file) }) {
                        Icon(Icons.Filled.Close, contentDescription = "목록에서 삭제")
                    }
                }
            }
        }
    }
}

@Composable
private fun TtsProblemCard(problem: String, onOpenTtsSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(problem, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onOpenTtsSettings) { Text("음성 설정 열기") }
        }
    }
}

@Composable
private fun SegmentList(
    segments: List<String>,
    current: Int,
    documentKey: String?,
    fontSize: Float,
    onSeek: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(documentKey) {
        listState.scrollToItem(current.coerceAtLeast(0))
    }
    LaunchedEffect(current) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val shown = visible.any { it.index == current } &&
            visible.lastOrNull()?.index != current
        if (!shown) listState.animateScrollToItem(current.coerceAtLeast(0))
    }
    val highlight = MaterialTheme.colorScheme.primaryContainer
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        itemsIndexed(segments) { index, segment ->
            val active = index == current
            Text(
                text = segment,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.5f).sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(if (active) highlight else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .clickable { onSeek(index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PlayerBar(
    state: PlaybackState,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val count = state.segmentCount
            LinearProgressIndicator(
                progress = { if (count == 0) 0f else (state.index + 1f) / count },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (count == 0) "" else "${state.index + 1} / $count",
                    style = MaterialTheme.typography.labelMedium,
                )
                SleepCountdown(state.sleepAtMillis)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, contentDescription = "정지") }
                IconButton(onClick = onPrevious) { Icon(Icons.Filled.SkipPrevious, contentDescription = "이전 문단") }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "일시정지" else "재생",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, contentDescription = "다음 문단") }
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun SleepCountdown(sleepAtMillis: Long?) {
    if (sleepAtMillis == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val left = ((sleepAtMillis - now) / 1000).coerceAtLeast(0)
    Text(
        String.format(Locale.ROOT, "수면 타이머 %d:%02d", left / 60, left % 60),
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun SettingsContent(
    state: PlaybackState,
    prefs: ReaderPrefs,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onFontSize: (Float) -> Unit,
    onVoice: (String?) -> Unit,
    onSleep: (Int) -> Unit,
    onOpenTtsSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        if (state.ttsProblem != null) {
            TtsProblemCard(state.ttsProblem, onOpenTtsSettings)
            Spacer(Modifier.height(12.dp))
        }

        LabeledSlider("읽기 속도", prefs.speed, 0.5f..2.0f, steps = 14, format = { "%.1f배".format(Locale.ROOT, it) }, onDone = onSpeed)
        LabeledSlider("음 높이", prefs.pitch, 0.5f..2.0f, steps = 14, format = { "%.1f".format(Locale.ROOT, it) }, onDone = onPitch)
        LabeledSlider("글자 크기", prefs.fontSize, 14f..32f, steps = 8, format = { "${it.toInt()}" }, onDone = onFontSize)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("수면 타이머", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val active = state.sleepAtMillis != null
            FilterChip(selected = !active, onClick = { onSleep(0) }, label = { Text("끄기") })
            listOf(15, 30, 60).forEach { minutes ->
                FilterChip(selected = false, onClick = { onSleep(minutes) }, label = { Text("${minutes}분") })
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("목소리", style = MaterialTheme.typography.titleSmall)
        VoiceRow("기본 한국어 목소리", selected = prefs.voiceName == null) { onVoice(null) }
        state.voices.forEach { voice ->
            VoiceRow(voice.label, selected = prefs.voiceName == voice.name) { onVoice(voice.name) }
        }
        if (state.ttsReady && state.voices.isEmpty()) {
            Text(
                "설치된 한국어 목소리 목록을 가져오지 못했습니다. 기본 목소리로 읽습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = onOpenTtsSettings, modifier = Modifier.padding(top = 8.dp)) {
            Text("기기 음성 설정 열기")
        }
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onDone: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(format(current), style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        value = current,
        onValueChange = { current = it },
        valueRange = range,
        steps = steps,
        onValueChangeFinished = { onDone(current) },
    )
}
