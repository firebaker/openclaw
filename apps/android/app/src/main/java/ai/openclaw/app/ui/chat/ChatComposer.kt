package ai.openclaw.app.ui.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentBorderStrong
import ai.openclaw.app.ui.mobileAccentSoft
import ai.openclaw.app.ui.mobileBorder
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileCardSurface
import ai.openclaw.app.ui.mobileDanger
import ai.openclaw.app.ui.mobileHeadline
import ai.openclaw.app.ui.mobileSuccess
import ai.openclaw.app.ui.mobileSuccessSoft
import ai.openclaw.app.ui.mobileSurface
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.mobileTextTertiary
import ai.openclaw.app.ui.mobileWarning

/** 3-state enum for the unified mic/speaker button. */
private enum class VoiceState { IDLE, LISTENING, SPEAKING }

@Composable
fun ChatComposer(
  healthOk: Boolean,
  thinkingLevel: String,
  pendingRunCount: Int,
  attachments: List<PendingImageAttachment>,
  // TtsHelper is created in ChatSheetContent and passed in — not owned here.
  ttsHelper: TtsHelper,
  onPickImages: () -> Unit,
  onRemoveAttachment: (id: String) -> Unit,
  onSetThinkingLevel: (level: String) -> Unit,
  onRefresh: () -> Unit,
  onAbort: () -> Unit,
  onSend: (text: String) -> Unit,
  // Called when a silence-timeout auto-send fires — parent sets a flag to speak the next response.
  onAutoSilenceSend: (() -> Unit)? = null,
) {
  var input by rememberSaveable { mutableStateOf("") }
  var showThinkingMenu by remember { mutableStateOf(false) }

  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // TTS speaking state (observed so the unified button updates reactively)
  val isSpeaking by ttsHelper.speaking.collectAsState()

  // SpeechInputHelper instance, scoped to this composable's lifecycle
  val speechHelper = remember { SpeechInputHelper(context, scope) }
  val isListening by speechHelper.isListening.collectAsState()
  val liveTranscript by speechHelper.transcript.collectAsState()

  // Whether the last send was triggered by silence timeout — drives auto-read.
  var lastSendWasAutoSilence by remember { mutableStateOf(false) }

  // Derive the unified 3-state voice button state.
  val voiceState = when {
    isSpeaking -> VoiceState.SPEAKING
    isListening -> VoiceState.LISTENING
    else -> VoiceState.IDLE
  }

  // Mirror live transcript into the text input field while listening.
  if (isListening && liveTranscript.isNotEmpty()) {
    input = liveTranscript
  }

  // Wire up the silence-based auto-send callback.
  // This is the only path that sets lastSendWasAutoSilence = true.
  speechHelper.onSilenceTimeout = {
    val text = input.trim()
    if (text.isNotEmpty()) {
      input = ""
      speechHelper.clearTranscript()
      lastSendWasAutoSilence = true
      onAutoSilenceSend?.invoke()
      onSend(text)
    }
  }

  // When TTS finishes naturally → clear the autoSilence flag (voice returns to IDLE).
  LaunchedEffect(isSpeaking) {
    if (!isSpeaking) {
      lastSendWasAutoSilence = false
    }
  }

  // Mic permission state + launcher (mirrors VoiceTabScreen pattern)
  var hasMicPermission by remember { mutableStateOf(speechHelper.hasMicPermission()) }
  var pendingMicStart by remember { mutableStateOf(false) }
  val requestMicPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    hasMicPermission = granted
    if (granted && pendingMicStart) {
      speechHelper.start()
    }
    pendingMicStart = false
  }

  // Clean up recognizer when the composable leaves composition
  DisposableEffect(Unit) {
    onDispose { speechHelper.stop() }
  }

  val canSend = pendingRunCount == 0 && (input.trim().isNotEmpty() || attachments.isNotEmpty()) && healthOk
  val sendBusy = pendingRunCount > 0

  // Text field border color: green when LISTENING, normal otherwise.
  val textFieldBorderFocused = when (voiceState) {
    VoiceState.LISTENING -> mobileSuccess
    else -> mobileAccent
  }
  val textFieldBorderUnfocused = when (voiceState) {
    VoiceState.LISTENING -> mobileSuccess
    else -> mobileBorder
  }

  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (attachments.isNotEmpty()) {
      AttachmentsStrip(attachments = attachments, onRemoveAttachment = onRemoveAttachment)
    }

    OutlinedTextField(
      value = input,
      onValueChange = { input = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text("Type a message…", style = mobileBodyStyle(), color = mobileTextTertiary) },
      minLines = 2,
      maxLines = 5,
      textStyle = mobileBodyStyle().copy(color = mobileText),
      shape = RoundedCornerShape(14.dp),
      colors = chatTextFieldColors(
        focusedBorder = textFieldBorderFocused,
        unfocusedBorder = textFieldBorderUnfocused,
      ),
    )

    if (!healthOk) {
      Text(
        text = "Gateway is offline. Connect first in the Connect tab.",
        style = mobileCallout,
        color = mobileWarning,
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box {
        Surface(
          onClick = { showThinkingMenu = true },
          shape = RoundedCornerShape(14.dp),
          color = mobileCardSurface,
          border = BorderStroke(1.dp, mobileBorderStrong),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = thinkingLabel(thinkingLevel),
              style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
              color = mobileTextSecondary,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select thinking level", modifier = Modifier.size(18.dp), tint = mobileTextTertiary)
          }
        }

        DropdownMenu(
          expanded = showThinkingMenu,
          onDismissRequest = { showThinkingMenu = false },
          shape = RoundedCornerShape(16.dp),
          containerColor = mobileCardSurface,
          tonalElevation = 0.dp,
          shadowElevation = 8.dp,
          border = BorderStroke(1.dp, mobileBorder),
        ) {
          ThinkingMenuItem("off", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          ThinkingMenuItem("low", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          ThinkingMenuItem("medium", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          ThinkingMenuItem("high", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
        }
      }

      // ── Attach button ─────────────────────────────────────────────────────
      SecondaryActionButton(
        label = "Attach",
        icon = Icons.Default.AttachFile,
        enabled = true,
        compact = true,
        onClick = onPickImages,
      )

      // ── Refresh button ────────────────────────────────────────────────────
      SecondaryActionButton(
        label = "Refresh",
        icon = Icons.Default.Refresh,
        enabled = true,
        compact = true,
        onClick = onRefresh,
      )

      // ── Abort button ──────────────────────────────────────────────────────
      SecondaryActionButton(
        label = "Abort",
        icon = Icons.Default.Stop,
        enabled = pendingRunCount > 0,
        compact = true,
        onClick = onAbort,
      )

      Spacer(modifier = Modifier.weight(1f))

      // ── Unified 3-state mic/speaker button ────────────────────────────────
      // IDLE:      Mic icon, secondary color      → tap starts listening
      // LISTENING: Mic icon in RED (mobileDanger) → tap stops manually (no auto-send/auto-read)
      // SPEAKING:  VolumeUp icon in GREEN, green bg → tap stops TTS
      Button(
        onClick = {
          when (voiceState) {
            VoiceState.IDLE -> {
              if (hasMicPermission) {
                speechHelper.clearTranscript()
                speechHelper.start()
              } else {
                pendingMicStart = true
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
              }
            }
            VoiceState.LISTENING -> {
              // Manual stop — leave transcript for editing, no auto-read on next response
              lastSendWasAutoSilence = false
              speechHelper.stop()
            }
            VoiceState.SPEAKING -> {
              // Stop TTS — voice returns to IDLE naturally as isSpeaking goes false
              ttsHelper.stop()
            }
          }
        },
        enabled = when (voiceState) {
          VoiceState.IDLE -> healthOk
          VoiceState.LISTENING, VoiceState.SPEAKING -> true  // always allow stopping
        },
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = when (voiceState) {
            VoiceState.IDLE -> mobileCardSurface
            VoiceState.LISTENING -> mobileCardSurface
            VoiceState.SPEAKING -> mobileSuccessSoft
          },
          contentColor = when (voiceState) {
            VoiceState.IDLE -> mobileTextSecondary
            VoiceState.LISTENING -> mobileDanger
            VoiceState.SPEAKING -> mobileSuccess
          },
          disabledContainerColor = mobileCardSurface,
          disabledContentColor = mobileTextTertiary,
        ),
        border = BorderStroke(
          1.dp,
          when (voiceState) {
            VoiceState.IDLE -> mobileBorderStrong
            VoiceState.LISTENING -> mobileDanger
            VoiceState.SPEAKING -> mobileSuccess
          },
        ),
      ) {
        Icon(
          imageVector = when (voiceState) {
            VoiceState.IDLE, VoiceState.LISTENING -> Icons.Default.Mic
            VoiceState.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
          },
          contentDescription = when (voiceState) {
            VoiceState.IDLE -> "Start dictation"
            VoiceState.LISTENING -> "Stop dictation"
            VoiceState.SPEAKING -> "Stop speaking"
          },
          modifier = Modifier.size(18.dp),
        )
      }
      // ─────────────────────────────────────────────────────────────────────

      Spacer(modifier = Modifier.width(4.dp))

      // ── Send button ───────────────────────────────────────────────────────
      Button(
        onClick = {
          val text = input
          input = ""
          speechHelper.clearTranscript()
          lastSendWasAutoSilence = false  // manual send never triggers auto-read
          onSend(text)
        },
        enabled = canSend,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = mobileAccent,
            contentColor = Color.White,
            disabledContainerColor = mobileBorderStrong,
            disabledContentColor = mobileTextTertiary,
          ),
        border = BorderStroke(1.dp, if (canSend) mobileAccentBorderStrong else mobileBorderStrong),
      ) {
        if (sendBusy) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "Send",
          style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SecondaryActionButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  enabled: Boolean,
  compact: Boolean = false,
  onClick: () -> Unit,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = if (compact) Modifier.size(44.dp) else Modifier.height(44.dp),
    shape = RoundedCornerShape(14.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = mobileCardSurface,
        contentColor = mobileTextSecondary,
        disabledContainerColor = mobileCardSurface,
        disabledContentColor = mobileTextTertiary,
      ),
    border = BorderStroke(1.dp, mobileBorderStrong),
    contentPadding = if (compact) PaddingValues(0.dp) else ButtonDefaults.ContentPadding,
  ) {
    Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp))
    if (!compact) {
      Spacer(modifier = Modifier.width(5.dp))
      Text(
        text = label,
        style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
        color = if (enabled) mobileTextSecondary else mobileTextTertiary,
      )
    }
  }
}

@Composable
private fun ThinkingMenuItem(
  value: String,
  current: String,
  onSet: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  DropdownMenuItem(
    text = { Text(thinkingLabel(value), style = mobileCallout, color = mobileText) },
    onClick = {
      onSet(value)
      onDismiss()
    },
    trailingIcon = {
      if (value == current.trim().lowercase()) {
        Text("✓", style = mobileCallout, color = mobileAccent)
      } else {
        Spacer(modifier = Modifier.width(10.dp))
      }
    },
  )
}

private fun thinkingLabel(raw: String): String {
  return when (raw.trim().lowercase()) {
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    else -> "Off"
  }
}

@Composable
private fun AttachmentsStrip(
  attachments: List<PendingImageAttachment>,
  onRemoveAttachment: (id: String) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (att in attachments) {
      AttachmentChip(
        fileName = att.fileName,
        onRemove = { onRemoveAttachment(att.id) },
      )
    }
  }
}

@Composable
private fun AttachmentChip(fileName: String, onRemove: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = mobileAccentSoft,
    border = BorderStroke(1.dp, mobileBorderStrong),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = fileName,
        style = mobileCaption1,
        color = mobileText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Surface(
        onClick = onRemove,
        shape = RoundedCornerShape(999.dp),
        color = mobileCardSurface,
        border = BorderStroke(1.dp, mobileBorderStrong),
      ) {
        Text(
          text = "×",
          style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
          color = mobileTextSecondary,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
      }
    }
  }
}

@Composable
private fun chatTextFieldColors(
  focusedBorder: Color = mobileAccent,
  unfocusedBorder: Color = mobileBorder,
) =
  OutlinedTextFieldDefaults.colors(
    focusedContainerColor = mobileSurface,
    unfocusedContainerColor = mobileSurface,
    focusedBorderColor = focusedBorder,
    unfocusedBorderColor = unfocusedBorder,
    focusedTextColor = mobileText,
    unfocusedTextColor = mobileText,
    cursorColor = mobileAccent,
  )

@Composable
private fun mobileBodyStyle() =
  MaterialTheme.typography.bodyMedium.copy(
    fontFamily = ai.openclaw.app.ui.mobileFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  )
