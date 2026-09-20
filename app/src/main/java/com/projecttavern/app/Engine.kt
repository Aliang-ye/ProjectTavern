package com.projecttavern.app

data class PromptBuilt(
    val messages: List<Pair<String, String>>,
    val debug: String,
)

object Engine {
    fun matchEntries(text: String, entries: List<WorldBookEntry>, roll: Boolean = true): List<WorldBookEntry> {
        val src = text.lowercase()
        return entries.filter { e ->
            if (!e.enabled) return@filter false
            if (e.constant) return@filter passProbability(e, roll)
            val primary = e.keys.any { k -> k.isNotBlank() && src.contains(k.lowercase()) }
            if (!primary) return@filter false
            val secondaryOk = e.secondaryKeys.none { it.isNotBlank() } ||
                e.secondaryKeys.any { k -> k.isNotBlank() && src.contains(k.lowercase()) }
            secondaryOk && passProbability(e, roll)
        }.sortedByDescending { it.priority }
    }

    private fun passProbability(e: WorldBookEntry, roll: Boolean): Boolean {
        if (!roll) return true
        val p = e.probability.coerceIn(0, 100)
        if (p >= 100) return true
        if (p <= 0) return false
        return (1..100).random() <= p
    }

    fun lore(w: WorldBook): String {
        val rows = listOf(
            "Overview" to w.description,
            "Geography" to w.geography,
            "History" to w.history,
            "Institutions" to w.institutions,
            "Culture" to w.culture,
            "Personal notes" to w.personalNotes,
        )
        return rows.filter { it.second.isNotBlank() }.joinToString("\n\n") { "[${it.first}] ${it.second.trim()}" }
    }

    fun visible(convId: String, tipId: String?): List<ChatMessage> {
        if (tipId == null) return emptyList()
        val byId = HashMap<String, ChatMessage>()
        for (m in Store.state.messages) {
            if (m.conversationId == convId) byId[m.id] = m
        }
        val path = mutableListOf<ChatMessage>()
        var cur: String? = tipId
        val guard = mutableSetOf<String>()
        while (cur != null && guard.add(cur)) {
            val m = byId[cur] ?: break
            path.add(m)
            cur = m.parentId
        }
        return path.reversed()
    }

    fun siblings(message: ChatMessage): List<ChatMessage> {
        return Store.state.messages
            .filter { it.conversationId == message.conversationId && it.parentId == message.parentId && it.role == message.role }
            .sortedBy { it.createdAt }
    }

    fun leafOf(message: ChatMessage): ChatMessage {
        val latestChild = HashMap<String, ChatMessage>()
        for (m in Store.state.messages) {
            val parent = m.parentId ?: continue
            if (m.conversationId != message.conversationId) continue
            val prev = latestChild[parent]
            if (prev == null || m.createdAt >= prev.createdAt) latestChild[parent] = m
        }
        var cur = message
        val guard = mutableSetOf<String>()
        while (guard.add(cur.id)) {
            cur = latestChild[cur.id] ?: break
        }
        return cur
    }

    fun display(m: ChatMessage): String {
        val g = m.generations.getOrNull(m.generationIndex) ?: m.generations.firstOrNull()
        return g?.content ?: m.content
    }

    fun fillMacros(text: String, userName: String, charName: String): String {
        return text
            .replace(Regex("\\{\\{\\s*user\\s*\\}\\}", RegexOption.IGNORE_CASE), userName)
            .replace(Regex("\\{\\{\\s*char\\s*\\}\\}", RegexOption.IGNORE_CASE), charName)
    }

    fun formatRpText(raw: String): CharSequence {
        if (!raw.contains("*")) return raw
        return try {
            val escaped = raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val formatted = escaped.replace(Regex("\\*([^*\\n]+)\\*")) { matchResult ->
                "<i>${matchResult.groupValues[1]}</i>"
            }.replace("\n", "<br/>")
            android.text.SpannableStringBuilder(android.text.Html.fromHtml(formatted, android.text.Html.FROM_HTML_MODE_COMPACT))
        } catch (_: Exception) {
            raw
        }
    }

    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    internal fun trimHistoryForContext(history: List<ChatMessage>, preset: Preset?, reservedTokens: Int = 0): List<ChatMessage> {
        if (preset == null || preset.contextLimit <= 0) return history
        val budget = (preset.contextLimit - reservedTokens.coerceAtLeast(0)).coerceAtLeast(512)
        if (history.size <= 4) return history
        var total = 0
        val kept = ArrayDeque<ChatMessage>()
        for (m in history.asReversed()) {
            val text = if (m.role == "assistant") display(m) else m.content
            val tokens = estimateTokens(text) + 8
            if (total + tokens > budget && kept.size >= 4) break
            kept.addFirst(m)
            total += tokens
        }
        return kept
    }

    fun memoryFor(conversationId: String): Memory? {
        return Store.state.memories.find { it.conversationId == conversationId && it.type == "CHAT_SUMMARY" }
    }

    fun upsertMemory(conversationId: String, content: String) {
        val now = Store.now()
        val existing = memoryFor(conversationId)
        if (existing != null) {
            existing.content = content
            existing.updatedAt = now
        } else {
            Store.state.memories.add(Memory(Store.nid(), conversationId, "CHAT_SUMMARY", content, now))
        }
        Store.persist()
    }

    fun shouldSummarize(conversationId: String, tipMessageId: String?): Boolean {
        if (!Store.state.autoSummary) return false
        val history = visible(conversationId, tipMessageId)
        if (history.size < 8) return false
        val mem = memoryFor(conversationId)
        val lastCreated = history.lastOrNull()?.createdAt ?: 0L
        if (mem != null && lastCreated - mem.updatedAt < 60_000) return false
        val since = history.count { it.createdAt > (mem?.updatedAt ?: 0L) }
        return since >= 6 || (mem == null && history.size >= 8)
    }

    fun summaryPrompt(conversationId: String, tipMessageId: String?): List<Pair<String, String>> {
        val history = visible(conversationId, tipMessageId).takeLast(16)
        val prev = memoryFor(conversationId)?.content.orEmpty()
        val locale = Store.state.locale
        val instruction = if (locale == "en") {
            "Summarize this roleplay so far in under 180 words. Keep names, locations, open promises, and the current situation. Do not write new plot."
        } else {
            "用不超过 180 字总结目前的角色扮演。保留人名、地点、未兑现的约定和当前处境。不要写新剧情。"
        }
        val body = buildString {
            if (prev.isNotBlank()) append("Previous summary:\n").append(prev).append("\n\n")
            append("Transcript:\n")
            history.forEach { m ->
                val who = if (m.role == "assistant") "Char" else "User"
                append(who).append(": ").append(if (m.role == "assistant") display(m) else m.content).append("\n")
            }
        }
        return listOf("system" to instruction, "user" to body)
    }

    fun build(conversationId: String, tipMessageId: String? = null, followConversationTip: Boolean = true): PromptBuilt {
        val s = Store.state
        val conv = s.conversations.find { it.id == conversationId } ?: return PromptBuilt(emptyList(), "")
        val ch = s.characters.find { it.id == conv.characterId }
        val preset = s.presets.find { it.id == conv.presetId } ?: Store.localePresets().firstOrNull()
        val effectiveTip = tipMessageId ?: if (followConversationTip) conv.tipMessageId else null
        val history = visible(conversationId, effectiveTip)
        val worldIds = if (conv.worldBookIds.isNotEmpty()) conv.worldBookIds else {
            s.characterWorldBooks.filter { it.characterId == conv.characterId }.map { it.worldBookId }
        }
        val worlds = s.worldBooks.filter { it.id in worldIds }
        val histText = history.joinToString("\n") { if (it.role == "assistant") display(it) else it.content }
        val entries = matchEntries(histText, s.entries.filter { it.worldBookId in worldIds }, roll = true)
        val before = entries.filter { it.insertionPosition == "before_char" }
        val after = entries.filter { it.insertionPosition != "before_char" }
        val lock = if (s.locale == "en") Store.t("lockEn") else Store.t("lockZh")
        val persona = s.personas.find { it.id == conv.personaId }
            ?: s.personas.find { it.id == s.activePersonaId }
            ?: s.personas.firstOrNull()
        val userName = persona?.name?.ifBlank { null } ?: s.userName.ifBlank { if (s.locale == "en") "You" else "你" }
        val userPersona = persona?.description?.ifBlank { null } ?: s.userPersona
        val charName = ch?.name ?: if (worlds.isNotEmpty()) {
            val w0 = worlds.first()
            w0.willName.ifBlank { if (s.locale == "en") "World Will" else "世界意志" }
        } else ""
        fun fill(t: String) = fillMacros(t, userName, charName)

        val sys = StringBuilder()
        sys.append("## LANGUAGE\n").append(lock).append("\n\n")
        sys.append("## PERSONA\nThe user is ").append(userName)
        if (userPersona.isNotBlank()) sys.append("\n").append(userPersona)
        sys.append("\n\n")
        memoryFor(conversationId)?.content?.takeIf { it.isNotBlank() }?.let {
            sys.append("## MEMORY\n").append(it.trim()).append("\n\n")
        }
        if (preset != null && preset.systemPrompt.isNotBlank()) {
            sys.append("## SYSTEM\n").append(preset.systemPrompt).append("\n\n")
        }
        if (before.isNotEmpty()) {
            sys.append("## LORE (before character)\n")
            before.forEach { e -> sys.append("[").append(e.name).append("] ").append(fill(e.content)).append("\n\n") }
        }
        if (ch != null) {
            sys.append("## CHARACTER\nName: ").append(ch.name)
            if (ch.description.isNotBlank()) sys.append("\nDescription: ").append(fill(ch.description))
            if (ch.personality.isNotBlank()) sys.append("\nPersonality: ").append(fill(ch.personality))
            if (ch.scenario.isNotBlank()) sys.append("\nScenario: ").append(fill(ch.scenario))
            if (ch.systemPrompt.isNotBlank()) sys.append("\nInstructions: ").append(fill(ch.systemPrompt))
            if (ch.exampleDialogues.isNotBlank()) sys.append("\nExample Dialogues:\n").append(fill(ch.exampleDialogues))
            sys.append("\n\n")
        } else if (worlds.isNotEmpty()) {
            val primaryWorld = worlds.first()
            val willName = primaryWorld.willName.ifBlank { if (s.locale == "en") "World Will" else "世界意志" }
            sys.append("## WORLD WILL (NARRATOR)\nName: ").append(willName)
            sys.append(" of [").append(primaryWorld.name).append("]")
            if (primaryWorld.willDescription.isNotBlank()) sys.append("\nDescription: ").append(fill(primaryWorld.willDescription))
            if (primaryWorld.willScenario.isNotBlank()) sys.append("\nScenario: ").append(fill(primaryWorld.willScenario))
            if (primaryWorld.willSystemPrompt.isNotBlank()) sys.append("\nInstructions: ").append(fill(primaryWorld.willSystemPrompt))
            sys.append("\n\n")
        }

        if (conv.storyId != null) {
            val story = s.stories.find { it.id == conv.storyId }
            if (story != null) {
                sys.append("## STORY\nTitle: ").append(story.name)
                if (story.description.isNotBlank()) sys.append("\nPlot: ").append(fill(story.description))
                sys.append("\n\n")
                val parts = s.participants.filter { it.storyId == story.id && it.enabled && it.characterId != ch?.id }
                if (parts.isNotEmpty()) {
                    sys.append("## CAST\n")
                    for (p in parts) {
                        val pch = s.characters.find { it.id == p.characterId } ?: continue
                        val roleLabel = when (p.role) {
                            "MAIN_CHARACTER" -> if (s.locale == "en") "Protagonist" else "主角"
                            "COMPANION" -> if (s.locale == "en") "Companion" else "同伴"
                            else -> if (s.locale == "en") "NPC" else "NPC"
                        }
                        sys.append("- [").append(pch.name).append("] (").append(roleLabel).append("): ")
                            .append(fill(pch.description.ifBlank { pch.personality })).append("\n")
                    }
                    sys.append("\n")
                }
            }
        }

        for (w in worlds) {
            val l = lore(w)
            if (l.isNotBlank()) sys.append("## WORLD\n[").append(w.name).append("]\n").append(l).append("\n\n")
        }
        if (after.isNotEmpty()) {
            sys.append("## LORE (after character)\n")
            after.forEach { e -> sys.append("[").append(e.name).append("] ").append(fill(e.content)).append("\n\n") }
        }

        val sysText = sys.toString().trim()
        val contextHistory = trimHistoryForContext(history, preset, estimateTokens(sysText) + 64)
        val messages = mutableListOf("system" to sysText)
        for (m in contextHistory) {
            val text = if (m.role == "assistant") display(m).trim() else m.content.trim()
            if (text.isBlank()) continue
            val role = if (m.role == "assistant") "assistant" else "user"
            messages.add(role to text)
        }
        val debug = buildString {
            appendLine("blocks=${messages.size} worlds=${worlds.size} entries=${entries.size} history=${contextHistory.size}/${history.size}")
            appendLine("tokens≈${messages.sumOf { estimateTokens(it.second) }}  memory=${if (memoryFor(conversationId)?.content.isNullOrBlank()) "off" else "on"}")
            if (entries.isNotEmpty()) {
                appendLine("injected:")
                entries.forEach { e ->
                    append(" • ").append(e.name).append(" [").append(e.insertionPosition).append("] p").append(e.priority)
                    if (e.constant) append(" constant")
                    appendLine()
                }
            }
            appendLine()
            appendLine("----- system -----")
            append(sys.toString().trim().take(4000))
        }
        return PromptBuilt(messages, debug)
    }
}
