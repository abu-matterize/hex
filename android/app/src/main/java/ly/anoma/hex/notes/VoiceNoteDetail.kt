package ly.anoma.hex.notes

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ly.anoma.hex.Action
import ly.anoma.hex.Card
import ly.anoma.hex.Label
import ly.anoma.hex.R

@Composable
internal fun ColumnScope.VoiceNoteDetail(note: VoiceNote, date: String, playback: VoiceNotePlayback,
                                       confirmingDelete: Boolean, confirmDelete: (Boolean) -> Unit, delete: () -> Unit) {
    Card(Modifier.weight(1f, fill = false)) {
        SelectionContainer(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(date, muted = true, size = 13, lines = 2)
                Label(note.text, size = 18, lines = Int.MAX_VALUE)
            }
        }
    }
    note.recordingMillis?.let { RecordingPlayer(note.id, it, playback) }
    if (confirmingDelete) {
        Card {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.voice_notes_delete_confirm), size = 14,
                    lines = 3, mod = Modifier.weight(1f))
                Action(stringResource(R.string.home_cancel)) { confirmDelete(false) }
                Action(stringResource(R.string.voice_notes_delete), onClick = delete)
            }
        }
    } else {
        Box(Modifier.align(Alignment.End).heightIn(min = 48.dp).clickable(role = Role.Button) {
            playback.stop()
            confirmDelete(true)
        }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Label(stringResource(R.string.voice_notes_delete), muted = true, size = 14)
        }
    }
}

@Composable
private fun RecordingPlayer(noteId: Long, durationMillis: Int, playback: VoiceNotePlayback) {
    val active = playback.current == noteId
    var position by remember(noteId) { mutableIntStateOf(0) }
    LaunchedEffect(noteId, active) {
        position = 0
        if (!active) return@LaunchedEffect
        while (isActive) {
            position = playback.positionMillis(noteId).coerceIn(0, durationMillis)
            delay(120)
        }
    }
    val elapsed = if (active) position else 0
    val duration = DateUtils.formatElapsedTime((durationMillis.toLong() + 999) / 1000)
    val time = if (active) stringResource(R.string.voice_notes_playback_time, DateUtils.formatElapsedTime(elapsed / 1000L), duration)
        else duration
    val ink = colorResource(R.color.ink)
    val icon = colorResource(R.color.page)
    Card {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { playback.toggle(noteId) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.size(44.dp).background(ink, CircleShape).padding(14.dp)) {
                if (active) drawRect(icon)
                else drawPath(Path().apply {
                    moveTo(size.width * .16f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width * .16f, size.height)
                    close()
                }, icon)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Label(stringResource(if (active) R.string.voice_notes_stop_playback else R.string.voice_notes_play_recording),
                        size = 15, weight = FontWeight.Medium, lines = 2, mod = Modifier.weight(1f))
                    Label(time, muted = true, size = 13)
                }
                LinearProgressIndicator(progress = { elapsed.toFloat() / durationMillis.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = ink, trackColor = colorResource(R.color.rule), gapSize = 0.dp, drawStopIndicator = {})
            }
        }
        if (playback.failed == noteId) Label(stringResource(R.string.voice_notes_play_failed), muted = true, size = 13,
            lines = 3, mod = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    }
}
