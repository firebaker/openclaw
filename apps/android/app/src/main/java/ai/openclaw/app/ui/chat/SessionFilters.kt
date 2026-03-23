package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatSessionEntry

private const val RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L

/**
 * Derive a human-friendly label from a raw session key.
 * Examples:
 *   "telegram:g-agent-main-main" -> "Main"
 *   "agent:main:main" -> "Main"
 *   "discord:g-server-channel" -> "Server Channel"
 *   "my-custom-session" -> "My Custom Session"
 */
fun friendlySessionName(key: String): String {
  // Strip common prefixes like "telegram:", "agent:", "discord:" etc.
  val stripped = key.substringAfterLast(":")

  // Remove leading "g-" prefix (gateway artifact)
  val cleaned = if (stripped.startsWith("g-")) stripped.removePrefix("g-") else stripped

  // Split on hyphens/underscores, title-case each word, collapse "main main" -> "Main"
  val words = cleaned.split('-', '_').filter { it.isNotBlank() }.map { word ->
    word.replaceFirstChar { it.uppercaseChar() }
  }.distinct()

  val result = words.joinToString(" ")
  return result.ifBlank { key }
}

/** System/background session key patterns that belong in the overflow bucket. */
private val OVERFLOW_PATTERNS = listOf(
  "heartbeat",
  "cron",
  "memory",
  "ready-report",
  "system",
  "automated",
  "schedule",
)

/**
 * Classify a session key as overflow (system/background) or primary (user-facing).
 * Returns true if the session belongs in the overflow bucket.
 */
fun isOverflowSession(key: String): Boolean {
  val lower = key.lowercase()
  return OVERFLOW_PATTERNS.any { pattern -> lower.contains(pattern) }
}

/**
 * Grouped session lists for the 4-pill thread selector.
 * [primary] contains user-facing sessions (agent:main:main pinned first).
 * [overflow] contains system/background sessions.
 */
data class GroupedSessions(
  val primary: List<ChatSessionEntry>,
  val overflow: List<ChatSessionEntry>,
)

/**
 * Resolve all sessions into primary and overflow groups.
 * The main session is always pinned at the top of primary.
 * Deduplicates by key and filters to recently-active sessions.
 */
fun resolveGroupedSessions(
  currentSessionKey: String,
  sessions: List<ChatSessionEntry>,
  mainSessionKey: String,
  nowMs: Long = System.currentTimeMillis(),
): GroupedSessions {
  val mainKey = mainSessionKey.trim().ifEmpty { "main" }
  val current = currentSessionKey.trim().let { if (it == "main" && mainKey != "main") mainKey else it }
  val aliasKey = if (mainKey == "main") null else "main"
  val cutoff = nowMs - RECENT_WINDOW_MS
  val sorted = sessions.sortedByDescending { it.updatedAtMs ?: 0L }

  // Deduplicate and filter to recent
  val recent = mutableListOf<ChatSessionEntry>()
  val seen = mutableSetOf<String>()
  for (entry in sorted) {
    if (aliasKey != null && entry.key == aliasKey) continue
    if (!seen.add(entry.key)) continue
    if ((entry.updatedAtMs ?: 0L) < cutoff) continue
    recent.add(entry)
  }

  // Ensure main and current session are always present
  val all = mutableListOf<ChatSessionEntry>()
  val included = mutableSetOf<String>()

  val mainEntry = sorted.firstOrNull { it.key == mainKey }
  if (mainEntry != null) {
    all.add(mainEntry)
    included.add(mainKey)
  } else if (current == mainKey) {
    all.add(ChatSessionEntry(key = mainKey, updatedAtMs = null))
    included.add(mainKey)
  }

  for (entry in recent) {
    if (included.add(entry.key)) {
      all.add(entry)
    }
  }

  if (current.isNotEmpty() && !included.contains(current)) {
    all.add(ChatSessionEntry(key = current, updatedAtMs = null))
  }

  // Split into primary (main pinned first) and overflow
  val primary = mutableListOf<ChatSessionEntry>()
  val overflow = mutableListOf<ChatSessionEntry>()

  for (entry in all) {
    if (entry.key == mainKey) {
      // Main always goes to primary, pinned at position 0
      primary.add(0, entry)
    } else if (isOverflowSession(entry.key)) {
      overflow.add(entry)
    } else {
      primary.add(entry)
    }
  }

  return GroupedSessions(primary = primary, overflow = overflow)
}

// Keep the old function for backward compatibility
fun resolveSessionChoices(
  currentSessionKey: String,
  sessions: List<ChatSessionEntry>,
  mainSessionKey: String,
  nowMs: Long = System.currentTimeMillis(),
): List<ChatSessionEntry> {
  val grouped = resolveGroupedSessions(currentSessionKey, sessions, mainSessionKey, nowMs)
  return grouped.primary + grouped.overflow
}
