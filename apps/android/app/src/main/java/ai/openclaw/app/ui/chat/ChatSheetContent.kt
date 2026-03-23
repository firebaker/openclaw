package ai.openclaw.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.chat.OutgoingAttachment
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentBorderStrong
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCardSurface
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileCaption2
import ai.openclaw.app.ui.mobileDanger
import ai.openclaw.app.ui.mobileDangerSoft
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatSheetContent(viewModel: MainViewModel) {
  val messages by viewModel.chatMessages.collectAsState()
  val errorText by viewModel.chatError.collectAsState()
  val pendingRunCount by viewModel.pendingRunCount.collectAsState()
  val healthOk by viewModel.chatHealthOk.collectAsState()
  val sessionKey by viewModel.chatSessionKey.collectAsState()
  val mainSessionKey by viewModel.mainSessionKey.collectAsState()
  val thinkingLevel by viewModel.chatThinkingLevel.collectAsState()
  val streamingAssistantText by viewModel.chatStreamingAssistantText.collectAsState()
  val pendingToolCalls by viewModel.chatPendingToolCalls.collectAsState()
  val sessions by viewModel.chatSessions.collectAsState()

  LaunchedEffect(mainSessionKey) {
    viewModel.loadChat(mainSessionKey)
  }

  val context = LocalContext.current
  val resolver = context.contentResolver
  val scope = rememberCoroutineScope()

  // ── TTS: create once, tied to the sheet's lifecycle ──────────────────────
  val ttsHelper = remember { TtsHelper(context) }
  DisposableEffect(Unit) {
    onDispose { ttsHelper.shutdown() }
  }

  // Observe TTS speaking state to pass down to message bubbles (play/stop toggle).
  val isSpeaking by ttsHelper.speaking.collectAsState()

  // Auto-speak: driven by the composer setting autoSpeakNextResponse when silence-timeout sends.
  // Uses streaming TTS — speaks clause-by-clause as the response streams in, rather than
  // waiting for the full response. Stop anytime via the mic/speaker button.
  val autoSpeakNextResponse = remember { androidx.compose.runtime.mutableStateOf(false) }
  val streamSpeakActive = remember { androidx.compose.runtime.mutableStateOf(false) }

  // When autoSpeakNextResponse is set and streaming text starts arriving, begin streaming TTS.
  LaunchedEffect(streamingAssistantText, autoSpeakNextResponse.value) {
    if (autoSpeakNextResponse.value && !streamingAssistantText.isNullOrBlank()) {
      if (!streamSpeakActive.value) {
        // First chunk — reset and start streaming.
        ttsHelper.streamReset()
        streamSpeakActive.value = true
      }
      ttsHelper.streamSpeak(streamingAssistantText!!)
    }
  }

  // When streaming ends (text goes null/blank) and we were actively streaming, flush remainder.
  LaunchedEffect(streamingAssistantText) {
    if (streamSpeakActive.value && streamingAssistantText.isNullOrBlank()) {
      // Stream finished — flush any remaining unspoken text from the final message.
      val latest = messages.lastOrNull()
      if (latest != null && latest.role.trim().lowercase() == "assistant") {
        val text = latest.content
          .filter { it.type == "text" }
          .mapNotNull { it.text }
          .joinToString("\n")
        if (text.isNotBlank()) {
          ttsHelper.streamFlush(text)
        }
      }
      streamSpeakActive.value = false
      autoSpeakNextResponse.value = false
    }
  }

  // ─────────────────────────────────────────────────────────────────────────

  val attachments = remember { mutableStateListOf<PendingImageAttachment>() }

  val pickImages =
    rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
      if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
      scope.launch(Dispatchers.IO) {
        val next =
          uris.take(8).mapNotNull { uri ->
            try {
              loadSizedImageAttachment(resolver, uri)
            } catch (_: Throwable) {
              null
            }
          }
        withContext(Dispatchers.Main) {
          attachments.addAll(next)
        }
      }
    }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    ChatThreadSelector(
      sessionKey = sessionKey,
      sessions = sessions,
      mainSessionKey = mainSessionKey,
      onSelectSession = { key -> viewModel.switchChatSession(key) },
      onCreateSession = { name ->
        viewModel.switchChatSession(name)
      },
    )

    if (!errorText.isNullOrBlank()) {
      ChatErrorRail(errorText = errorText!!)
    }

    ChatMessageListCard(
      messages = messages,
      pendingRunCount = pendingRunCount,
      pendingToolCalls = pendingToolCalls,
      streamingAssistantText = streamingAssistantText,
      healthOk = healthOk,
      onSpeakMessage = { text ->
        if (isSpeaking) ttsHelper.stop() else ttsHelper.speak(text)
      },
      isSpeaking = isSpeaking,
      onStopSpeaking = { ttsHelper.stop() },
      modifier = Modifier.weight(1f, fill = true),
    )

    Row(modifier = Modifier.fillMaxWidth().imePadding()) {
      ChatComposer(
        healthOk = healthOk,
        thinkingLevel = thinkingLevel,
        pendingRunCount = pendingRunCount,
        attachments = attachments,
        ttsHelper = ttsHelper,
        onPickImages = { pickImages.launch("image/*") },
        onRemoveAttachment = { id -> attachments.removeAll { it.id == id } },
        onSetThinkingLevel = { level -> viewModel.setChatThinkingLevel(level) },
        onRefresh = {
          viewModel.refreshChat()
          viewModel.refreshChatSessions(limit = 200)
        },
        onAbort = { viewModel.abortChat() },
        onSend = { text ->
          val outgoing =
            attachments.map { att ->
              OutgoingAttachment(
                type = "image",
                mimeType = att.mimeType,
                fileName = att.fileName,
                base64 = att.base64,
              )
            }
          viewModel.sendChat(message = text, thinking = thinkingLevel, attachments = outgoing)
          attachments.clear()
        },
        // Called by ChatComposer when a silence-timeout auto-send fires.
        // Sets the flag so the LaunchedEffect above speaks the next assistant response.
        onAutoSilenceSend = {
          autoSpeakNextResponse.value = true
        },
      )
    }
  }
}

@Composable
private fun ChatThreadSelector(
  sessionKey: String,
  sessions: List<ChatSessionEntry>,
  mainSessionKey: String,
  onSelectSession: (String) -> Unit,
  onCreateSession: (String) -> Unit,
) {
  val grouped =
    remember(sessionKey, sessions, mainSessionKey) {
      resolveGroupedSessions(sessionKey, sessions, mainSessionKey = mainSessionKey)
    }

  var showCreateDialog by remember { mutableStateOf(false) }
  var showPrimaryMenu by remember { mutableStateOf(false) }
  var showOverflowMenu by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // 1. Active session pill (accent colors)
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = mobileAccent,
      border = BorderStroke(1.dp, mobileAccentBorderStrong),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Text(
        text = friendlySessionName(sessionKey),
        style = mobileCaption1.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      )
    }

    // 2. "+" pill — create new session
    Surface(
      onClick = { showCreateDialog = true },
      shape = RoundedCornerShape(14.dp),
      color = mobileCardSurface,
      border = BorderStroke(1.dp, mobileBorderStrong),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = "New session",
        tint = mobileText,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      )
    }

    // 3. Primary ▾ pill — dropdown for user-facing sessions
    SessionDropdownPill(
      label = "Primary",
      entries = grouped.primary,
      activeKey = sessionKey,
      expanded = showPrimaryMenu,
      onToggle = { showPrimaryMenu = !showPrimaryMenu },
      onDismiss = { showPrimaryMenu = false },
      onSelect = { key ->
        showPrimaryMenu = false
        onSelectSession(key)
      },
    )

    // 4. Overflow ▾ pill — only shown when there are system sessions
    if (grouped.overflow.isNotEmpty()) {
      SessionDropdownPill(
        label = "System",
        entries = grouped.overflow,
        activeKey = sessionKey,
        expanded = showOverflowMenu,
        onToggle = { showOverflowMenu = !showOverflowMenu },
        onDismiss = { showOverflowMenu = false },
        onSelect = { key ->
          showOverflowMenu = false
          onSelectSession(key)
        },
      )
    }
  }

  // Create session dialog
  if (showCreateDialog) {
    CreateSessionDialog(
      onDismiss = { showCreateDialog = false },
      onCreate = { name ->
        showCreateDialog = false
        onCreateSession(name)
      },
    )
  }
}

/** A pill with a dropdown chevron that opens a menu of session entries. */
@Composable
private fun SessionDropdownPill(
  label: String,
  entries: List<ChatSessionEntry>,
  activeKey: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  onDismiss: () -> Unit,
  onSelect: (String) -> Unit,
) {
  // Wrap in a Box-like scope so DropdownMenu anchors correctly
  androidx.compose.foundation.layout.Box {
    Surface(
      onClick = onToggle,
      shape = RoundedCornerShape(14.dp),
      color = mobileCardSurface,
      border = BorderStroke(1.dp, mobileBorderStrong),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = label,
          style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
          color = mobileText,
          maxLines = 1,
        )
        Icon(
          imageVector = Icons.Default.KeyboardArrowDown,
          contentDescription = null,
          tint = mobileTextSecondary,
          modifier = Modifier.padding(0.dp),
        )
      }
    }

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = onDismiss,
      modifier = Modifier.widthIn(min = 180.dp),
    ) {
      for (entry in entries) {
        val name = friendlySessionName(entry.displayName ?: entry.key)
        val isActive = entry.key == activeKey
        DropdownMenuItem(
          text = {
            Text(
              text = name,
              style = mobileCaption1.copy(
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
              ),
              color = if (isActive) mobileAccent else mobileText,
            )
          },
          onClick = { onSelect(entry.key) },
        )
      }
      if (entries.isEmpty()) {
        DropdownMenuItem(
          text = {
            Text(
              text = "No sessions",
              style = mobileCaption1,
              color = mobileTextSecondary,
            )
          },
          onClick = {},
          enabled = false,
        )
      }
    }
  }
}

/** Simple dialog to create a new named session. */
@Composable
private fun CreateSessionDialog(
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var sessionName by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("New Session", style = mobileCallout, color = mobileText) },
    text = {
      OutlinedTextField(
        value = sessionName,
        onValueChange = { sessionName = it },
        label = { Text("Session name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(
        onClick = { onCreate(sessionName.trim()) },
        enabled = sessionName.isNotBlank(),
      ) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun ChatErrorRail(errorText: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = mobileDangerSoft,
    shape = RoundedCornerShape(12.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, mobileDanger),
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "CHAT ERROR",
        style = mobileCaption2.copy(letterSpacing = 0.6.sp),
        color = mobileDanger,
      )
      Text(text = errorText, style = mobileCallout, color = mobileText)
    }
  }
}

data class PendingImageAttachment(
  val id: String,
  val fileName: String,
  val mimeType: String,
  val base64: String,
)
