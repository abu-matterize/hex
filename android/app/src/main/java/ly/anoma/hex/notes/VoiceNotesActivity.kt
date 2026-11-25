package ly.anoma.hex.notes

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.anoma.hex.Action
import ly.anoma.hex.Card
import ly.anoma.hex.DictationBar
import ly.anoma.hex.HexTheme
import ly.anoma.hex.Label
import ly.anoma.hex.R
import ly.anoma.hex.Rule
import ly.anoma.hex.SettingsField
import ly.anoma.hex.SettingsTitle
import ly.anoma.hex.keyboard.speechNote
import ly.anoma.hex.speech.DictationPlace
import ly.anoma.hex.speech.LocalSpeech
import ly.anoma.hex.speech.MicCapture
import ly.anoma.hex.speech.OfflineScreen
import ly.anoma.hex.speech.SpeechPhase
import ly.anoma.hex.speech.SpeechPreferences
import ly.anoma.hex.speech.keepAwake

class VoiceNotesActivity : ComponentActivity(), OfflineScreen {
    private val speech by lazy { SpeechPreferences(this) }
    private val store by lazy { VoiceNoteStore.get(this) }
    private val setup by lazy { getSharedPreferences("setup", MODE_PRIVATE) }
    private lateinit var capture: MicCapture
    private var phase by mutableStateOf(SpeechPhase.Idle)
    private var notice by mutableStateOf<String?>(null)
    private var pending by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)
    private var recordedAt = 0L
    private var recordOnFocus = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else notice = getString(R.string.keyboard_hint_microphone)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VoiceNotes.enabled(this)) {
            finish()
            return
        }
        enableEdgeToEdge()
        capture = MicCapture(LocalSpeech(applicationContext), DictationPlace.VoiceNotes,
            update = { next, reason ->
                phase = next
                notice = speechNote(reason)
                window.keepAwake(next)
            },
            meter = {},
            insert = { text, _, _, _ ->
                pending = text
                save()
            },
            stopAfterPauseMs = 3_000,
        )
        recordOnFocus = savedInstanceState == null && VoiceNotes.startsRecording(intent)
        setContent {
            HexTheme {
                VoiceNotesScreen(store, revision, phase, notice, pending, saving,
                    begin = ::begin, finishRecording = capture::finish, cancel = capture::cancel,
                    retry = ::save, delete = ::delete, back = ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recordOnFocus = VoiceNotes.startsRecording(intent)
        if (hasWindowFocus()) startIfRequested()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startIfRequested()
    }

    private fun startIfRequested() {
        if (!recordOnFocus) return
        recordOnFocus = false
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) begin()
    }

    private fun begin() {
        if (!::capture.isInitialized || !VoiceNotes.enabled(this) || phase != SpeechPhase.Idle || pending != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (setup.getBoolean("asked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else {
                setup.edit().putBoolean("asked", true).apply()
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        recordedAt = System.currentTimeMillis()
        capture.start(speech.options(null))
    }

    private fun save() {
        val text = pending ?: return
        if (saving) return
        saving = true
        val at = recordedAt
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO + NonCancellable) { runCatching { store.add(text, at) } }
            saving = false
            if (result.isSuccess) {
                pending = null
                revision++
                notice = getString(R.string.voice_notes_saved)
            } else notice = getString(R.string.voice_notes_save_failed)
        }
    }

    private fun delete(id: Long) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.delete(id) } }
            if (result.isSuccess) revision++
            else notice = getString(R.string.voice_notes_delete_failed)
        }
    }

    override fun onStart() {
        super.onStart()
        if (VoiceNotes.enabled(this)) revision++ else finish()
    }
    override fun onStop() {
        recordOnFocus = false
        if (::capture.isInitialized) capture.cancel()
        super.onStop()
    }
    override fun onDestroy() {
        if (::capture.isInitialized) capture.close()
        super.onDestroy()
    }
}

@Composable
private fun VoiceNotesScreen(
    store: VoiceNoteStore, revision: Int, phase: SpeechPhase, notice: String?, pending: String?, saving: Boolean,
    begin: () -> Unit, finishRecording: () -> Unit, cancel: () -> Unit, retry: () -> Unit,
    delete: (Long) -> Unit, back: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == SpeechPhase.Opening) {
            query = ""
            selectedId = null
            confirmingDelete = false
        }
    }
    val result by produceState<Result<List<VoiceNote>>?>(null, query, revision) {
        if (query.isNotBlank()) delay(150)
        value = withContext(Dispatchers.IO) { runCatching { store.find(query) } }
    }
    val notes = result?.getOrNull().orEmpty()
    val selected = notes.firstOrNull { it.id == selectedId }
    val context = LocalContext.current
    fun date(note: VoiceNote): String = DateUtils.formatDateTime(context, note.recordedAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
    fun goBack() {
        when {
            confirmingDelete -> confirmingDelete = false
            selectedId != null -> selectedId = null
            else -> back()
        }
    }
    BackHandler(selectedId != null) { goBack() }

    Box(Modifier.fillMaxSize().background(colorResource(R.color.page)).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxHeight()
            .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsTitle(stringResource(R.string.voice_notes), back = ::goBack)
            if (selected != null && phase == SpeechPhase.Idle) {
                Card(Modifier.weight(1f, fill = false)) {
                    SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Label(date(selected), muted = true, size = 13)
                            Label(selected.text, lines = Int.MAX_VALUE)
                        }
                    }
                    Rule()
                    if (confirmingDelete) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Label(stringResource(R.string.voice_notes_delete_confirm), size = 14,
                                lines = 3, mod = Modifier.weight(1f))
                            Action(stringResource(R.string.home_cancel)) { confirmingDelete = false }
                            Action(stringResource(R.string.voice_notes_delete)) {
                                delete(selected.id)
                                confirmingDelete = false
                                selectedId = null
                            }
                        }
                    } else Row(Modifier.padding(horizontal = 4.dp)) {
                        Action(stringResource(R.string.voice_notes_delete)) { confirmingDelete = true }
                    }
                }
            } else {
                val (title, primary) = when {
                    saving -> R.string.voice_notes_saving to null
                    pending != null -> R.string.voice_notes_retry to retry
                    phase == SpeechPhase.Idle -> R.string.voice_notes_record to begin
                    phase == SpeechPhase.Transcribing -> R.string.home_transcribing to null
                    else -> R.string.home_finish to finishRecording
                }
                DictationBar(stringResource(title), primary, cancel = if (phase == SpeechPhase.Idle) null else cancel)
                notice?.let { Label(it, muted = true, size = 14, lines = 3) }
                if (phase == SpeechPhase.Listening)
                    Label(stringResource(R.string.voice_notes_listening), muted = true, size = 14, lines = 3)
                if (pending != null && !saving) Card(Modifier.weight(1f, fill = false)) {
                    SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                        Label(pending, mod = Modifier.padding(16.dp), lines = Int.MAX_VALUE)
                    }
                }
                if (phase == SpeechPhase.Idle && pending == null) {
                    Card {
                        SettingsField(query, stringResource(R.string.voice_notes_search)) { query = it }
                    }
                    when {
                        result?.isFailure == true -> Label(stringResource(R.string.voice_notes_read_failed), muted = true, size = 14)
                        result != null && notes.isEmpty() -> Label(
                            stringResource(if (query.isBlank()) R.string.voice_notes_empty else R.string.voice_notes_no_matches),
                            muted = true, size = 14, lines = 3)
                    }
                    if (notes.isNotEmpty()) Card(Modifier.weight(1f, fill = false)) {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                                if (index > 0) Rule()
                                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { selectedId = note.id }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Label(date(note), muted = true, size = 13)
                                    Label(note.excerpt, lines = 5)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
